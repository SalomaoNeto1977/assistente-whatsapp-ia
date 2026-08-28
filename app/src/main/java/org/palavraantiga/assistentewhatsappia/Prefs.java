package org.palavraantiga.assistentewhatsappia;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String NAME = "assistente_whatsapp_ia";
    static final String MODE = "mode";
    static final String API_KEY = "api_key";
    static final String MODEL = "model";
    static final String MIN_DELAY = "min_delay";
    static final String MAX_DELAY = "max_delay";
    static final String PREFIX = "prefix";
    static final String PREFIX_MODE = "prefix_mode";
    static final String INSTRUCTIONS = "instructions";
    static final String REPLY_GROUPS = "reply_groups";
    static final String LAST_ERROR = "last_error";
    static final String LAST_ACTIVITY = "last_activity";

    static final String MODE_OFF = "off";
    static final String MODE_ALWAYS = "always";
    static final String MODE_STANDBY = "standby";

    static final String PREFIX_ALWAYS = "always";
    static final String PREFIX_FIRST = "first";
    static final String PREFIX_NEVER = "never";

    static final String DEFAULT_INSTRUCTIONS =
            "És um assistente que responde a mensagens de WhatsApp em nome do utilizador. " +
            "Responde em português de Portugal, salvo se a pessoa escrever claramente noutra língua. " +
            "Sê educado, natural, útil e relativamente conciso. Não inventes informações. " +
            "Se não souberes uma resposta com segurança, diz de forma simples que a questão será vista pessoalmente.\n\n" +
            "CONHECIMENTO E REGRAS DO UTILIZADOR:\n" +
            "Escreve aqui tudo o que queres que o assistente saiba: horários, serviços, respostas habituais, " +
            "regras, assuntos a evitar, forma de tratamento, contactos e outros detalhes.";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static String string(Context c, String key, String fallback) {
        return get(c).getString(key, fallback);
    }

    static int integer(Context c, String key, int fallback) {
        return get(c).getInt(key, fallback);
    }

    static boolean bool(Context c, String key, boolean fallback) {
        return get(c).getBoolean(key, fallback);
    }

    static void lastError(Context c, String text) {
        get(c).edit().putString(LAST_ERROR, text == null ? "" : text).apply();
    }

    static void lastActivity(Context c, String text) {
        get(c).edit().putString(LAST_ACTIVITY, text == null ? "" : text).apply();
    }
}
