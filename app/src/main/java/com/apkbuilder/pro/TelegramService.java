package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;

public class TelegramService {
    private OkHttpClient client;

    public TelegramService() {
        this.client = new OkHttpClient();
    }

    /**
     * Sends a message and returns the message_id for later editing.
     * @return The message ID as a String, or null on failure.
     */
    public String sendMessageWithId(String botToken, String chatId, String message) throws IOException {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("chat_id", chatId);
            requestBody.put("text", message);
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
            if (response.code() == 200) {
                String body = response.body().string();
                JSONObject jsonResponse = new JSONObject(body);
                if (jsonResponse.optBoolean("ok", false)) {
                    // Extract message_id from the result object
                    return jsonResponse.getJSONObject("result").optString("message_id");
                }
            }
            return null;
        } catch (Exception e) {
            throw new IOException("Error sending message: " + e.getMessage());
        }
    }
    
    /**
     * Edits an existing message in the Telegram chat.
     */
    public boolean editMessage(String botToken, String chatId, String messageId, String newText) throws IOException {
        String url = "https://api.telegram.org/bot" + botToken + "/editMessageText";
        
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("chat_id", chatId);
            requestBody.put("message_id", messageId); // Use the message_id to target the message
            requestBody.put("text", newText);
            requestBody.put("parse_mode", "HTML");
        } catch (Exception e) {
            throw new IOException("Error creating request body for edit: " + e.getMessage());
        }

        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            // Telegram returns 200 for success
            return response.code() == 200;
        }
    }

    // Keep the original sendMessage for compatibility if needed, but the new one is better
    // You should rename your old 'sendMessage' or delete it if you only use sendMessageWithId
    public boolean sendMessage(String botToken, String chatId, String message) throws IOException {
        return sendMessageWithId(botToken, chatId, message) != null;
    }

    public boolean testConnection(String botToken, String chatId) throws IOException {
        String testMessage = "🤖 <b>APK Builder Pro Test</b>\n\nYour setup is working correctly! You will receive build statuses and APK files here.";
        return sendMessage(botToken, chatId, testMessage);
    }
}
