package org.palavraantiga.assistentewhatsappia;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OpenAIClient {
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";

    private OpenAIClient() {}

    static String generate(Context context, String sender, String message) throws Exception {
        String key = Prefs.string(context, Prefs.API_KEY, "").trim();
        if (key.isEmpty()) {
            throw new IllegalStateException("Falta configurar a chave da OpenAI.");
        }

        String model = Prefs.string(context, Prefs.MODEL, "gpt-5-mini").trim();
        if (model.isEmpty()) model = "gpt-5-mini";

        String instructions = Prefs.string(context, Prefs.INSTRUCTIONS, Prefs.DEFAULT_INSTRUCTIONS).trim();

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("instructions", instructions);
        body.put("input",
                "Mensagem recebida no WhatsApp" +
                (sender == null || sender.isBlank() ? "" : " de \"" + sender + "\"") +
                ":\n\n" + message +
                "\n\nResponde apenas com o texto que deve ser enviado no WhatsApp, sem explicar o teu raciocínio e sem acrescentar aspas.");
        body.put("max_output_tokens", 500);

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }

        int status = connection.getResponseCode();
        InputStream input = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String raw = readAll(input);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            String messageText = "Erro OpenAI HTTP " + status;
            try {
                JSONObject errorJson = new JSONObject(raw).optJSONObject("error");
                if (errorJson != null && !errorJson.optString("message").isBlank()) {
                    messageText += ": " + errorJson.optString("message");
                }
            } catch (Exception ignored) {
            }
            throw new IllegalStateException(messageText);
        }

        JSONObject response = new JSONObject(raw);
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("A OpenAI não devolveu texto utilizável.");
        }
        return text.trim();
    }

    private static String extractText(JSONObject response) {
        String convenience = response.optString("output_text", "");
        if (!convenience.isBlank()) return convenience;

        JSONArray output = response.optJSONArray("output");
        if (output == null) return null;

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    String text = part.optString("text", "");
                    if (!text.isBlank()) {
                        if (result.length() > 0) result.append('\n');
                        result.append(text);
                    }
                }
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
