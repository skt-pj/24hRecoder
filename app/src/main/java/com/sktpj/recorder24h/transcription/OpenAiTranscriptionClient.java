package com.sktpj.recorder24h.transcription;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class OpenAiTranscriptionClient {
    public static final String MODEL = "gpt-4o-mini-transcribe";
    private static final String ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 8 * 60_000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    public Response transcribe(File audioFile, String apiKey) throws IOException {
        String boundary = "----24hRecoder" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        connection.setChunkedStreamingMode(64 * 1024);

        try {
            try (OutputStream raw = new BufferedOutputStream(connection.getOutputStream())) {
                writeField(raw, boundary, "model", MODEL);
                writeFile(raw, boundary, "file", audioFile, "audio/mp4");
                raw.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                raw.flush();
            }

            int code = connection.getResponseCode();
            InputStream bodyStream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readBody(bodyStream);
            if (code >= 200 && code < 300) {
                try {
                    JSONObject json = new JSONObject(body);
                    return new Response(code, json.optString("text", ""), null);
                } catch (Exception parseError) {
                    return new Response(code, null, "Invalid transcription response");
                }
            }
            return new Response(code, null, extractErrorMessage(body));
        } finally {
            connection.disconnect();
        }
    }

    public static boolean isRetryableHttpCode(int code) {
        return code == 408 || code == 409 || code == 425 || code == 429 || code >= 500;
    }

    private static void writeField(OutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(OutputStream out, String boundary, String fieldName, File file,
                                  String contentType) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\""
                + file.getName().replace("\"", "_") + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));

        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int remaining = MAX_RESPONSE_BYTES - total;
                if (remaining <= 0) {
                    break;
                }
                int copy = Math.min(read, remaining);
                out.write(buffer, 0, copy);
                total += copy;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isEmpty()) {
            return "OpenAI API request failed";
        }
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return message.length() > 500 ? message.substring(0, 500) : message;
                }
            }
        } catch (Exception ignored) {
        }
        return "OpenAI API request failed";
    }

    public static final class Response {
        public final int httpCode;
        public final String text;
        public final String errorMessage;

        Response(int httpCode, String text, String errorMessage) {
            this.httpCode = httpCode;
            this.text = text;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return httpCode >= 200 && httpCode < 300 && text != null;
        }
    }
}
