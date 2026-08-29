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

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

public class WhatsappNotificationService extends NotificationListenerService {
    private static final String TAG = "AssistenteWhatsAppIA";
    private static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";

    // O WhatsApp pode republicar/actualizar várias vezes a mesma notificação.
    private static final long DUPLICATE_WINDOW_MS = 2L * 60L * 1000L;
    private static final long OUTGOING_SUPPRESSION_MS = 4_000L;
    private static final long FIRST_PREFIX_WINDOW_MS = 30L * 60L * 1000L;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Future<?>> pendingByConversation = new ConcurrentHashMap<>();
    private final Map<String, String> notificationToConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final Map<String, Long> suppressConversationUntil = new ConcurrentHashMap<>();
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
        // Segurança: nunca processar o WhatsApp pessoal (com.whatsapp).
        if (sbn == null || !WHATSAPP_BUSINESS.equals(sbn.getPackageName())) return;

        String mode = Prefs.string(this, Prefs.MODE, Prefs.MODE_OFF);
        if (Prefs.MODE_OFF.equals(mode)) return;
        if (Prefs.MODE_STANDBY.equals(mode) && isInteractive()) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        boolean isGroup = extras != null && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false);
        if (isGroup && !Prefs.bool(this, Prefs.REPLY_GROUPS, false)) return;

        String sender = extractSender(notification);
        String conversation = conversationKey(notification, sender);
        notificationToConversation.put(sbn.getKey(), conversation);

        MessageData messageData = extractLatestMessage(notification);
        if (messageData == null || messageData.text == null || messageData.text.isBlank()) return;

        long now = System.currentTimeMillis();

        // Em MessagingStyle, uma mensagem sem remetente representa normalmente a mensagem do próprio utilizador.
        // Isto cancela uma resposta pendente quando o utilizador responde manualmente e impede loops após a resposta da IA.
        if (messageData.outgoingLikely) {
            suppressConversationUntil.put(conversation, now + OUTGOING_SUPPRESSION_MS);
            cancelPendingConversation(conversation);
            return;
        }

        Long suppressedUntil = suppressConversationUntil.get(conversation);
        if (suppressedUntil != null) {
            if (now < suppressedUntil) return;
            suppressConversationUntil.remove(conversation, suppressedUntil);
        }

        ReplyTarget target = findReplyTarget(notification);
        if (target == null) return;

        String fingerprint = fingerprint(conversation, messageData);
        Long previous = seen.putIfAbsent(fingerprint, now);
        if (previous != null && now - previous < DUPLICATE_WINDOW_MS) return;
        if (previous != null) seen.put(fingerprint, now);
        cleanupSeen(now);

        // Uma conversa só pode ter UMA resposta pendente.
        // Se chegar uma nova mensagem real antes do envio, substitui a anterior pela mais recente.
        Future<?> old = pendingByConversation.remove(conversation);
        if (old != null) old.cancel(true);

        Future<?> future = executor.submit(() -> processReply(conversation, sender, messageData.text, target));
        pendingByConversation.put(conversation, future);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WHATSAPP_BUSINESS.equals(sbn.getPackageName())) return;
        String conversation = notificationToConversation.remove(sbn.getKey());
        // Se o utilizador está com o telefone activo e a notificação desaparece, é muito provável
        // que tenha aberto/respondido à conversa. Nesse caso a IA não deve responder depois.
        if (conversation != null && isInteractive()) {
            cancelPendingConversation(conversation);
        }
    }

    private void processReply(String conversation, String sender, String incoming, ReplyTarget target) {
        try {
            int min = Math.max(0, Prefs.integer(this, Prefs.MIN_DELAY, 8));
            int max = Math.max(min, Prefs.integer(this, Prefs.MAX_DELAY, 15));
            int delaySeconds = min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
            long sendAt = System.currentTimeMillis() + delaySeconds * 1000L;

            Prefs.lastActivity(this, "A preparar uma única resposta para " + safeSender(sender) + "…");
            String answer = OpenAIClient.generate(this, sender, incoming);
            if (Thread.currentThread().isInterrupted()) return;

            long remaining = sendAt - System.currentTimeMillis();
            if (remaining > 0) Thread.sleep(remaining);
            if (Thread.currentThread().isInterrupted()) return;

            String mode = Prefs.string(this, Prefs.MODE, Prefs.MODE_OFF);
            if (Prefs.MODE_OFF.equals(mode)) return;
            if (Prefs.MODE_STANDBY.equals(mode) && isInteractive()) return;

            String outgoing = applyPrefix(sender, answer);
            suppressConversationUntil.put(conversation, System.currentTimeMillis() + OUTGOING_SUPPRESSION_MS);
            sendRemoteReply(target, outgoing);

            Prefs.lastError(this, "");
            Prefs.lastActivity(this, "Resposta única enviada para " + safeSender(sender));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Prefs.lastError(this, msg);
            Prefs.lastActivity(this, "Não foi possível responder a " + safeSender(sender));
            Log.e(TAG, "Falha ao gerar/enviar resposta: " + msg, e);
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
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "AssistenteWhatsAppIA:EnviarResposta");
                wakeLock.acquire(15_000L);
            }

            Intent intent = new Intent();
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            Bundle results = new Bundle();
            for (RemoteInput input : target.remoteInputs) {
                if (input.getAllowFreeFormInput()) {
                    results.putCharSequence(input.getResultKey(), text);
                }
            }
            RemoteInput.addResultsToIntent(target.remoteInputs, intent, results);
            target.pendingIntent.send(this, 0, intent);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private ReplyTarget findReplyTarget(Notification notification) {
        Notification.Action[] actions = notification.actions;
        if (actions == null) return null;

        ReplyTarget fallback = null;
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null) continue;
            RemoteInput[] inputs = action.getRemoteInputs();
            if (inputs == null || inputs.length == 0) continue;

            boolean hasFreeForm = false;
            for (RemoteInput input : inputs) {
                if (input != null && input.getAllowFreeFormInput()) {
                    hasFreeForm = true;
                    break;
                }
            }
            if (!hasFreeForm) continue;

            ReplyTarget candidate = new ReplyTarget(action.actionIntent, inputs);
            if (Build.VERSION.SDK_INT >= 28
                    && action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) {
                return candidate;
            }
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    private String extractSender(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return "Contacto";
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null || title.toString().isBlank() ? "Contacto" : title.toString().trim();
    }

    private MessageData extractLatestMessage(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;

        Object rawMessages = extras.get(Notification.EXTRA_MESSAGES);
        if (rawMessages instanceof Object[]) {
            Object[] messages = (Object[]) rawMessages;
            for (int i = messages.length - 1; i >= 0; i--) {
                if (!(messages[i] instanceof Bundle)) continue;
                Bundle b = (Bundle) messages[i];
                CharSequence messageText = b.getCharSequence("text");
                if (messageText == null || messageText.toString().isBlank()) continue;

                boolean hasSenderField = b.containsKey("sender") || b.containsKey("sender_person");
                CharSequence sender = b.getCharSequence("sender");
                Object senderPerson = null;
                try {
                    senderPerson = b.getParcelable("sender_person");
                } catch (Exception ignored) {
                }
                boolean senderPresent = (sender != null && !sender.toString().isBlank()) || senderPerson != null;
                boolean outgoingLikely = hasSenderField && !senderPresent;
                long timestamp = b.getLong("time", 0L);
                return new MessageData(messageText.toString().trim(), timestamp, outgoingLikely);
            }
        }

        // Fallback para versões/formas de notificação que não exponham EXTRA_MESSAGES.
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && !text.toString().isBlank()) {
            return new MessageData(text.toString().trim(), 0L, false);
        }

        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (bigText != null && !bigText.toString().isBlank()) {
            return new MessageData(bigText.toString().trim(), 0L, false);
        }
        return null;
    }

    private String conversationKey(Notification notification, String sender) {
        String shortcutId = notification.getShortcutId();
        if (shortcutId != null && !shortcutId.isBlank()) return "shortcut:" + shortcutId;
        return "sender:" + normalize(sender == null ? "Contacto" : sender);
    }

    private String fingerprint(String conversation, MessageData message) {
        String base = conversation + "|" + normalize(message.text);
        if (message.timestamp > 0L) return base + "|" + message.timestamp;
        return base;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return text;
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private void cancelPendingConversation(String conversation) {
        Future<?> future = pendingByConversation.remove(conversation);
        if (future != null) future.cancel(true);
    }

    private void cancelPending() {
        for (Future<?> future : pendingByConversation.values()) {
            if (future != null) future.cancel(true);
        }
        pendingByConversation.clear();
    }

    private void cleanupSeen(long now) {
        if (seen.size() < 200) return;
        seen.entrySet().removeIf(entry -> now - entry.getValue() > 15L * 60L * 1000L);
    }

    private String safeSender(String sender) {
        return sender == null || sender.isBlank() ? "o contacto" : sender;
    }

    private static final class MessageData {
        final String text;
        final long timestamp;
        final boolean outgoingLikely;

        MessageData(String text, long timestamp, boolean outgoingLikely) {
            this.text = text;
            this.timestamp = timestamp;
            this.outgoingLikely = outgoingLikely;
        }
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
