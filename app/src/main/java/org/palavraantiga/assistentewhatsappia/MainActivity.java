package org.palavraantiga.assistentewhatsappia;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Spinner modeSpinner;
    private Spinner prefixModeSpinner;
    private EditText apiKeyField;
    private EditText modelField;
    private EditText minDelayField;
    private EditText maxDelayField;
    private EditText prefixField;
    private EditText instructionsField;
    private CheckBox groupsCheck;
    private TextView accessStatus;
    private TextView runtimeStatus;
    private EditText testMessage;
    private TextView testResult;
    private Button testButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Assistente WhatsApp IA");
        buildUi();
        loadSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("🤖 Assistente WhatsApp IA");
        title.setTextSize(25);
        title.setTextColor(Color.rgb(25, 25, 25));
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Responde automaticamente às mensagens recebidas pelas notificações do WhatsApp, sem root e sem abrir o WhatsApp.");
        intro.setTextSize(15);
        intro.setPadding(0, dp(8), 0, dp(18));
        root.addView(intro);

        accessStatus = new TextView(this);
        accessStatus.setTextSize(16);
        root.addView(accessStatus);

        Button accessButton = new Button(this);
        accessButton.setText("Dar acesso às notificações");
        accessButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Abre Definições > Notificações > Acesso às notificações.", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(accessButton);

        addHeading(root, "Funcionamento");
        addLabel(root, "Modo");
        modeSpinner = spinner(new String[]{"Desligado", "Sempre ativo", "Apenas em standby / ecrã bloqueado"});
        root.addView(modeSpinner);

        addLabel(root, "Delay mínimo (segundos)");
        minDelayField = numberField();
        root.addView(minDelayField);

        addLabel(root, "Delay máximo (segundos)");
        maxDelayField = numberField();
        root.addView(maxDelayField);

        groupsCheck = new CheckBox(this);
        groupsCheck.setText("Responder também em grupos (desativado por segurança)");
        groupsCheck.setPadding(0, dp(10), 0, 0);
        root.addView(groupsCheck);

        addHeading(root, "Identificação das respostas");
        addLabel(root, "Prefixo — podes escrever um nome, emoji ou ambos");
        prefixField = textField(false);
        prefixField.setHint("🤖 Assistente:");
        root.addView(prefixField);

        addLabel(root, "Mostrar o prefixo");
        prefixModeSpinner = spinner(new String[]{"Em todas as respostas", "Só na primeira resposta da conversa", "Nunca"});
        root.addView(prefixModeSpinner);

        addHeading(root, "OpenAI");
        addLabel(root, "Chave API");
        apiKeyField = textField(false);
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setHint("sk-…");
        root.addView(apiKeyField);

        TextView keyNote = new TextView(this);
        keyNote.setText("A chave fica no armazenamento privado desta app no teu telefone. Esta versão é para uso pessoal; não distribuas a APK já configurada com a tua chave.");
        keyNote.setTextSize(13);
        keyNote.setPadding(0, dp(5), 0, dp(5));
        root.addView(keyNote);

        addLabel(root, "Modelo");
        modelField = textField(false);
        modelField.setHint("gpt-5-mini");
        root.addView(modelField);

        addHeading(root, "Ensinar a IA");
        TextView help = new TextView(this);
        help.setText("Aqui podes explicar quem é o assistente, como deve falar, respostas habituais, horários, regras, assuntos a evitar e tudo o que ele precisa de saber.");
        help.setTextSize(14);
        help.setPadding(0, 0, 0, dp(6));
        root.addView(help);

        instructionsField = textField(true);
        instructionsField.setMinLines(12);
        instructionsField.setGravity(android.view.Gravity.TOP);
        root.addView(instructionsField);

        Button save = new Button(this);
        save.setText("Guardar definições");
        save.setOnClickListener(v -> {
            saveSettings();
            Toast.makeText(this, "Definições guardadas.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = dp(14);
        root.addView(save, saveParams);

        addHeading(root, "Teste da IA");
        addLabel(root, "Mensagem de teste (não é enviada ao WhatsApp)");
        testMessage = textField(true);
        testMessage.setMinLines(3);
        testMessage.setHint("Olá, a que horas estão abertos?");
        root.addView(testMessage);

        testButton = new Button(this);
        testButton.setText("Gerar resposta de teste");
        testButton.setOnClickListener(v -> runTest());
        root.addView(testButton);

        testResult = new TextView(this);
        testResult.setTextSize(15);
        testResult.setPadding(0, dp(10), 0, 0);
        root.addView(testResult);

        addHeading(root, "Estado");
        runtimeStatus = new TextView(this);
        runtimeStatus.setTextSize(14);
        root.addView(runtimeStatus);

        setContentView(scroll);
    }

    private void loadSettings() {
        SharedPreferences p = Prefs.get(this);
        String mode = p.getString(Prefs.MODE, Prefs.MODE_OFF);
        modeSpinner.setSelection(Prefs.MODE_ALWAYS.equals(mode) ? 1 : Prefs.MODE_STANDBY.equals(mode) ? 2 : 0);

        apiKeyField.setText(p.getString(Prefs.API_KEY, ""));
        modelField.setText(p.getString(Prefs.MODEL, "gpt-5-mini"));
        minDelayField.setText(String.valueOf(p.getInt(Prefs.MIN_DELAY, 8)));
        maxDelayField.setText(String.valueOf(p.getInt(Prefs.MAX_DELAY, 15)));
        prefixField.setText(p.getString(Prefs.PREFIX, "🤖 Assistente:"));
        instructionsField.setText(p.getString(Prefs.INSTRUCTIONS, Prefs.DEFAULT_INSTRUCTIONS));
        groupsCheck.setChecked(p.getBoolean(Prefs.REPLY_GROUPS, false));

        String prefixMode = p.getString(Prefs.PREFIX_MODE, Prefs.PREFIX_ALWAYS);
        prefixModeSpinner.setSelection(Prefs.PREFIX_FIRST.equals(prefixMode) ? 1 : Prefs.PREFIX_NEVER.equals(prefixMode) ? 2 : 0);
        refreshStatus();
    }

    private void saveSettings() {
        int min = parseInt(minDelayField.getText().toString(), 8);
        int max = parseInt(maxDelayField.getText().toString(), 15);
        min = Math.max(0, min);
        max = Math.max(min, max);
        minDelayField.setText(String.valueOf(min));
        maxDelayField.setText(String.valueOf(max));

        String mode = modeSpinner.getSelectedItemPosition() == 1
                ? Prefs.MODE_ALWAYS
                : modeSpinner.getSelectedItemPosition() == 2 ? Prefs.MODE_STANDBY : Prefs.MODE_OFF;
        String prefixMode = prefixModeSpinner.getSelectedItemPosition() == 1
                ? Prefs.PREFIX_FIRST
                : prefixModeSpinner.getSelectedItemPosition() == 2 ? Prefs.PREFIX_NEVER : Prefs.PREFIX_ALWAYS;

        Prefs.get(this).edit()
                .putString(Prefs.MODE, mode)
                .putString(Prefs.API_KEY, apiKeyField.getText().toString().trim())
                .putString(Prefs.MODEL, modelField.getText().toString().trim())
                .putInt(Prefs.MIN_DELAY, min)
                .putInt(Prefs.MAX_DELAY, max)
                .putString(Prefs.PREFIX, prefixField.getText().toString().trim())
                .putString(Prefs.PREFIX_MODE, prefixMode)
                .putString(Prefs.INSTRUCTIONS, instructionsField.getText().toString().trim())
                .putBoolean(Prefs.REPLY_GROUPS, groupsCheck.isChecked())
                .apply();
    }

    private void runTest() {
        saveSettings();
        String message = testMessage.getText().toString().trim();
        if (message.isEmpty()) {
            Toast.makeText(this, "Escreve primeiro uma mensagem de teste.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apiKeyField.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "Configura primeiro a chave da OpenAI.", Toast.LENGTH_LONG).show();
            return;
        }

        testButton.setEnabled(false);
        testResult.setText("A gerar resposta…");
        new Thread(() -> {
            try {
                String answer = OpenAIClient.generate(this, "Contacto de teste", message);
                runOnUiThread(() -> {
                    testResult.setText("Resposta: " + answer);
                    testButton.setEnabled(true);
                });
            } catch (Exception e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> {
                    testResult.setText("Erro: " + error);
                    testButton.setEnabled(true);
                });
            }
        }, "openai-test").start();
    }

    private void refreshStatus() {
        boolean enabled = isNotificationListenerEnabled();
        accessStatus.setText(enabled
                ? "✅ Acesso às notificações: autorizado"
                : "⚠️ Acesso às notificações: falta autorizar");
        accessStatus.setTextColor(enabled ? Color.rgb(0, 115, 50) : Color.rgb(180, 90, 0));

        SharedPreferences p = Prefs.get(this);
        String activity = p.getString(Prefs.LAST_ACTIVITY, "Ainda não houve respostas automáticas.");
        String error = p.getString(Prefs.LAST_ERROR, "");
        StringBuilder status = new StringBuilder(activity == null || activity.isBlank()
                ? "Ainda não houve respostas automáticas."
                : activity);
        if (error != null && !error.isBlank()) status.append("\nÚltimo erro: ").append(error);
        runtimeStatus.setText(status.toString());
    }

    private boolean isNotificationListenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null || enabled.isBlank()) return false;
        String[] components = enabled.split(":");
        for (String component : components) {
            ComponentName name = ComponentName.unflattenFromString(component);
            if (name != null && getPackageName().equals(name.getPackageName())) return true;
        }
        return false;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    private EditText textField(boolean multiline) {
        EditText field = new EditText(this);
        field.setTextSize(16);
        field.setSingleLine(!multiline);
        if (multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return field;
    }

    private EditText numberField() {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        return field;
    }

    private void addHeading(LinearLayout root, String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextSize(19);
        heading.setTextColor(Color.rgb(20, 20, 20));
        heading.setPadding(0, dp(22), 0, dp(6));
        root.addView(heading);
    }

    private void addLabel(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(9), 0, 0);
        root.addView(label);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
