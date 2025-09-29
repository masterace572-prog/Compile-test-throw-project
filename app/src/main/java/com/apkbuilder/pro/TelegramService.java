package com.apkbuilder.pro;

import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;

public class TelegramService {
    private OkHttpClient client;

    public TelegramService() {
        this.client = new OkHttpClient();
    }

    public boolean sendMessage(String botToken, String chatId, String message) throws IOException {
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
            return response.code() == 200;
        }
    }

    public boolean testConnection(String botToken, String chatId) throws IOException {
        return sendMessage(botToken, chatId, "🤖 APK Builder Pro Test\n\nYour setup is working correctly! You will receive APK files here when builds complete.");
    }
}