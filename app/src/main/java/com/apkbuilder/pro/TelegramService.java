package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;

public class TelegramService {
    private OkHttpClient client;

    public TelegramService() {
        // OkHttpClient is created once and reused
        this.client = new OkHttpClient();
    }

    /**
     * Sends a message to a Telegram chat.
     */
    public boolean sendMessage(String botToken, String chatId, String message) throws IOException {
        // Input validation is recommended here for robustness
        if (botToken == null || chatId == null || message == null || botToken.isEmpty() || chatId.isEmpty()) {
            throw new IllegalArgumentException("Bot token, chat ID, and message cannot be null or empty.");
        }
        
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("chat_id", chatId);
            requestBody.put("text", message);
            // Use MarkdownV2 for a more modern look, or keep HTML
            requestBody.put("parse_mode", "HTML"); 
        } catch (Exception e) {
            throw new IOException("Error creating request body: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Telegram API returns 200 for success, with a 'ok: true' in the body.
            // Checking the HTTP code is sufficient for a quick check.
            if (response.code() == 200) {
                 return true;
            } else {
                // Log or process error body for detailed error reporting
                // String errorBody = response.body().string();
                return false;
            }
        }
    }

    /**
     * Sends a connection test message.
     */
    public boolean testConnection(String botToken, String chatId) throws IOException {
        String testMessage = "🤖 <b>APK Builder Pro Test</b>\n\nYour setup is working correctly! You will receive build statuses and APK files here.";
        return sendMessage(botToken, chatId, testMessage);
    }
}
