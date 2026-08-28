package org.palavraantiga.assistentewhatsappia;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

public class WhatsappNotificationService extends NotificationListenerService {
    private static final String TAG = "AssistenteWhatsAppIA";
    private static final long DUPLICATE_WINDOW_MS = 7_000L;
    private static final long FIRST_PREFIX_WINDOW_MS = 30L * 60L * 1000L;
    private static final String WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b";

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Future<?>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPrefixBySender = new ConcurrentHashMap<>();

    private BroadcastReceiver screenReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        && Prefs.MODE_STANDBY.equals(Prefs.string(context, Prefs.MODE, Prefs.MODE_OFF))) {
                    cancelPending();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        cancelPending();
        executor.shutdownNow();
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !isWhatsAppBusiness(sbn.getPackageName())) return;

        String mode = Prefs.string(this, Prefs.MODE, Prefs.MODE_OFF);
        if (Prefs.MODE_OFF.equals(mode)) return;
        if (Prefs.MODE_STANDBY.equals(mode) && isInteractive()) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        boolean isGroup = extras != null && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        if (isGroup && !Prefs.bool(this, Prefs.REPLY_GROUPS, false)) return;

        String message = extractMessage(notification);
        if (message == null || message.isBlank()) return;

        ReplyTarget target = findReplyTarget(notification);
        if (target == null) return;

        String sender = extractSender(notification);
        String signature = sbn.getKey() + "|" + message;
        long now = System.currentTimeMillis();
        Long previous = seen.put(signature, now);
        if (previous != null && now - previous < DUPLICATE_WINDOW_MS) return;
        cleanupSeen(now);

        Future<?> old = pending.remove(sbn.getKey());
        if (old != null) old.cancel(true);

        Future<?> future = executor.submit(() -> processReply(sender, message, target));
        pending.put(sbn.getKey(), future);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        Future<?> future = pending.remove(sbn.getKey());
        if (future != null) future.cancel(true);
    }

    private void processReply(String sender, String incoming, ReplyTarget target) {
        try {
            int min = Math.max(0, Prefs.integer(this, Prefs.MIN_DELAY, 8));
            int max = Math.max(min, Prefs.integer(this, Prefs.MAX_DELAY, 15));
            int delaySeconds = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            long sendAt = System.currentTimeMillis() + delaySeconds * 1000L;

            Prefs.lastActivity(this, "A preparar resposta para " + safeSender(sender) + "…");
            String answer = OpenAIClient.generate(this, sender, incoming);
            if (Thread.currentThread().isInterrupted()) return;

            long remaining = sendAt - System.currentTimeMillis();
            if (remaining > 0) Thread.sleep(remaining);
            if (Thread.currentThread().isInterrupted()) return;

            String mode = Prefs.string(this, Prefs.MODE, Prefs.MODE_OFF);
            if (Prefs.MODE_OFF.equals(mode)) return;
            if (Prefs.MODE_STANDBY.equals(mode) && isInteractive()) return;

            String outgoing = applyPrefix(sender, answer);
            sendRemoteReply(target, outgoing);
            Prefs.lastError(this, "");
            Prefs.lastActivity(this, "Resposta enviada para " + safeSender(sender));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Prefs.lastError(this, msg);
            Prefs.lastActivity(this, "Não foi possível responder a " + safeSender(sender));
            Log.e(TAG, "Falha ao gerar/enviar resposta: " + msg);
        }
    }

    private String applyPrefix(String sender, String answer) {
        String mode = Prefs.string(this, Prefs.PREFIX_MODE, Prefs.PREFIX_ALWAYS);
        if (Prefs.PREFIX_NEVER.equals(mode)) return answer;

        String prefix = Prefs.string(this, Prefs.PREFIX, "🤖 Assistente:").trim();
        if (prefix.isEmpty()) return answer;

        if (Prefs.PREFIX_FIRST.equals(mode)) {
            String key = sender == null ? "desconhecido" : sender;
            long now = System.currentTimeMillis();
            long previous = lastPrefixBySender.getOrDefault(key, 0L);
            lastPrefixBySender.put(key, now);
            if (now - previous < FIRST_PREFIX_WINDOW_MS) return answer;
        }
        return prefix + " " + answer;
    }

    private void sendRemoteReply(ReplyTarget target, String text) throws PendingIntent.CanceledException {
        Intent intent = new Intent();
        Bundle results = new Bundle();
        for (RemoteInput input : target.remoteInputs) {
            if (input.getAllowFreeFormInput()) {
                results.putCharSequence(input.getResultKey(), text);
            }
        }
        RemoteInput.addResultsToIntent(target.remoteInputs, intent, results);
        target.pendingIntent.send(this, 0, intent);
    }

    private ReplyTarget findReplyTarget(Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) return null;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0) continue;
            for (RemoteInput input : inputs) {
                if (input.getAllowFreeFormInput()) {
                    return new ReplyTarget(action.actionIntent, inputs);
                }
            }
        }
        return null;
    }

    private String extractSender(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return "Contacto";
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null || title.toString().isBlank() ? "Contacto" : title.toString().trim();
    }

    private String extractMessage(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;

        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && !text.toString().isBlank()) return text.toString().trim();

        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (bigText != null && !bigText.toString().isBlank()) return bigText.toString().trim();

        Object rawMessages = extras.get(Notification.EXTRA_MESSAGES);
        if (rawMessages instanceof Object[]) {
            Object[] messages = (Object[]) rawMessages;
            for (int i = messages.length - 1; i >= 0; i--) {
                if (messages[i] instanceof Bundle) {
                    CharSequence messageText = ((Bundle) messages[i]).getCharSequence("text");
                    if (messageText != null && !messageText.toString().isBlank()) {
                        return messageText.toString().trim();
                    }
                }
            }
        }
        return null;
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private boolean isWhatsAppBusiness(String packageName) {
        return WHATSAPP_BUSINESS_PACKAGE.equals(packageName);
    }

    private void cancelPending() {
        for (Future<?> future : pending.values()) {
            if (future != null) future.cancel(true);
        }
        pending.clear();
    }

    private void cleanupSeen(long now) {
        if (seen.size() < 100) return;
        seen.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
    }

    private String safeSender(String sender) {
        return sender == null || sender.isBlank() ? "o contacto" : sender;
    }

    private static final class ReplyTarget {
        final PendingIntent pendingIntent;
        final RemoteInput[] remoteInputs;

        ReplyTarget(PendingIntent pendingIntent, RemoteInput[] remoteInputs) {
            this.pendingIntent = pendingIntent;
            this.remoteInputs = remoteInputs;
        }
    }
}
