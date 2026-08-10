package com.sktpj.recorder24h.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public final class OpenAiLunaClient {
    public static final String MODEL = "gpt-5.6-luna";
    private static final String RESPONSES_URL = "https://api.openai.com/v1/responses";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private OpenAiLunaClient() {
    }

    public static Response analyzeHourly(String apiKey, AiAnalysisRepository.SourceWindow source)
            throws Exception {
        String prompt = buildHourlyPrompt(source);
        return request(apiKey, prompt, "low", "hourly_analysis", hourlySchema(), 8_000);
    }

    public static Response analyzeDaily(String apiKey, AiAnalysisRepository.SourceWindow source)
            throws Exception {
        String prompt = buildDailyPrompt(source);
        return request(apiKey, prompt, "medium", "daily_analysis", dailySchema(), 24_000);
    }

    public static Response analyzeRollup(String apiKey, String kind,
                                         AiRollupRepository.RollupSource source)
            throws Exception {
        if (!AiAnalysisScheduler.KIND_WEEKLY.equals(kind)
                && !AiAnalysisScheduler.KIND_MONTHLY.equals(kind)
                && !AiAnalysisScheduler.KIND_YEARLY.equals(kind)) {
            throw new IllegalArgumentException("Unsupported rollup kind: " + kind);
        }
        String prompt = buildRollupPrompt(kind, source);
        return request(apiKey, prompt, "medium",
                kind + "_analysis", rollupSchema(), 24_000);
    }

    private static Response request(String apiKey, String prompt, String reasoningEffort,
                                    String schemaName, JSONObject schema, int maxOutputTokens)
            throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException(401, false, "OpenAI API key is not configured");
        }

        JSONObject request = new JSONObject();
        request.put("model", MODEL);
        request.put("store", false);
        request.put("max_output_tokens", maxOutputTokens);
        request.put("reasoning", new JSONObject().put("effort", reasoningEffort));
        request.put("instructions",
                "You analyze a private 24-hour recorder notebook. Use only the supplied source data as evidence. " +
                "Do not invent missing events, people, places, durations, intentions, counts, or conclusions. " +
                "If evidence is absent, return an empty array or an empty string as appropriate. " +
                "Return only the requested JSON structure.");
        request.put("input", prompt);
        request.put("text", new JSONObject()
                .put("verbosity", "low")
                .put("format", new JSONObject()
                        .put("type", "json_schema")
                        .put("name", schemaName)
                        .put("strict", true)
                        .put("schema", schema)));

        HttpURLConnection connection = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int status = connection.getResponseCode();
        String responseText = readBody(status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream());
        if (status < 200 || status >= 300) {
            String message = extractErrorMessage(responseText);
            boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
            throw new ApiException(status, retryable, message);
        }

        JSONObject raw = new JSONObject(responseText);
        String responseStatus = raw.optString("status", "");
        if (!"completed".equals(responseStatus)) {
            JSONObject error = raw.optJSONObject("error");
            String message = error == null
                    ? "OpenAI response status: " + responseStatus
                    : error.optString("message", "OpenAI response failed");
            throw new ApiException(status, false, message);
        }

        String outputText = extractOutputText(raw);
        if (outputText == null || outputText.trim().isEmpty()) {
            throw new ApiException(status, false, "OpenAI response did not contain output_text");
        }

        JSONObject analysis;
        try {
            analysis = new JSONObject(outputText);
        } catch (Exception error) {
            throw new ApiException(status, false, "OpenAI structured output was not valid JSON");
        }
        JSONObject usage = raw.optJSONObject("usage");
        return new Response(
                raw.optString("id", null),
                analysis,
                usage == null ? null : new JSONObject(usage.toString()));
    }

    private static String buildHourlyPrompt(AiAnalysisRepository.SourceWindow source) {
        return "Analyze the following one-hour transcript window.\n" +
                "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
                "{\"summary\":string,\"topics\":[string],\"keyEvents\":[{\"time\":string,\"event\":string}]," +
                "\"ideas\":[string],\"decisions\":[string],\"todos\":[{\"task\":string,\"evidence\":string}]," +
                "\"people\":[string],\"places\":[string],\"notableQuotes\":[{\"time\":string,\"text\":string}]," +
                "\"unresolved\":[string]}\n" +
                "Keep the summary concise. Preserve timestamps when they are useful. " +
                "For quotes, only copy wording that is actually present in the transcript.\n\n" +
                "TRANSCRIPT:\n" + AiAnalysisRepository.promptTranscript(source);
    }

    private static String buildDailyPrompt(AiAnalysisRepository.SourceWindow source) {
        return "Analyze the following full-day transcript from the original transcript data. " +
                "Do not treat hourly summaries as source evidence.\n" +
                "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
                "{\"summary\":string,\"mindMap\":[{\"id\":string,\"label\":string,\"parentId\":string}]," +
                "\"timeline\":[{\"time\":string,\"event\":string}],\"topics\":[{\"name\":string,\"summary\":string}]," +
                "\"keyEvents\":[string],\"decisions\":[string],\"todos\":[{\"task\":string,\"status\":string,\"evidence\":string}]," +
                "\"ideas\":[string],\"unresolved\":[string],\"people\":[{\"name\":string,\"summary\":string}]," +
                "\"places\":[{\"name\":string,\"summary\":string}]," +
                "\"timeAllocation\":[{\"category\":string,\"minutes\":integer,\"evidence\":string}]," +
                "\"recurringTopics\":[{\"topic\":string,\"count\":integer,\"summary\":string}],\"searchIndex\":[string]}\n" +
                "Mind map rules: create one root node with parentId as an empty string, use unique stable ids inside this response, " +
                "and connect all other nodes through parentId. " +
                "Time allocation rules: count only intervals supported by transcript timestamps; do not force the categories to total 24 hours. " +
                "Recurring topic counts must represent evidence occurrences in this supplied transcript only.\n\n" +
                "TRANSCRIPT:\n" + AiAnalysisRepository.promptTranscript(source);
    }

    private static String buildRollupPrompt(String kind, AiRollupRepository.RollupSource source) {
        String label;
        if (AiAnalysisScheduler.KIND_WEEKLY.equals(kind)) {
            label = "weekly";
        } else if (AiAnalysisScheduler.KIND_MONTHLY.equals(kind)) {
            label = "monthly";
        } else {
            label = "yearly";
        }

        return "Create a " + label + " notebook by aggregating the supplied lower-level AI notes. " +
                "These notes are the only available source because raw audio/transcripts may already be deleted. " +
                "Do not claim evidence that is not present in the supplied notes.\n" +
                "The output MUST be JSON and MUST follow this JSON format exactly:\n" +
                "{\"summary\":string,\"highlights\":[string]," +
                "\"topics\":[{\"name\":string,\"summary\":string,\"count\":integer}]," +
                "\"decisions\":[string],\"todos\":[{\"task\":string,\"status\":string,\"evidence\":string}]," +
                "\"ideas\":[string],\"unresolved\":[string]," +
                "\"people\":[{\"name\":string,\"summary\":string}]," +
                "\"places\":[{\"name\":string,\"summary\":string}]," +
                "\"timeAllocation\":[{\"category\":string,\"minutes\":integer,\"evidence\":string}]," +
                "\"trends\":[{\"label\":string,\"summary\":string}]," +
                "\"mindMap\":[{\"id\":string,\"label\":string,\"parentId\":string}]," +
                "\"searchIndex\":[string]}\n" +
                "Rules: highlight only cross-note or period-level information supported by the source notes. " +
                "Topic counts must count supporting source notes, not invented occurrences. " +
                "Time allocation may only aggregate evidence-backed minutes already present in source notes; " +
                "do not force it to cover the full period. " +
                "For trends, describe only changes or repeated patterns that can be supported by multiple source notes. " +
                "For the mind map, create one root with empty parentId and connect all other nodes to it or its descendants.\n\n" +
                "SOURCE NOTES:\n" + AiRollupRepository.promptSource(source);
    }

    private static JSONObject hourlySchema() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("summary", stringSchema());
        properties.put("topics", arraySchema(stringSchema()));
        properties.put("keyEvents", arraySchema(objectSchema(new JSONObject()
                .put("time", stringSchema())
                .put("event", stringSchema()))));
        properties.put("ideas", arraySchema(stringSchema()));
        properties.put("decisions", arraySchema(stringSchema()));
        properties.put("todos", arraySchema(objectSchema(new JSONObject()
                .put("task", stringSchema())
                .put("evidence", stringSchema()))));
        properties.put("people", arraySchema(stringSchema()));
        properties.put("places", arraySchema(stringSchema()));
        properties.put("notableQuotes", arraySchema(objectSchema(new JSONObject()
                .put("time", stringSchema())
                .put("text", stringSchema()))));
        properties.put("unresolved", arraySchema(stringSchema()));
        return objectSchema(properties);
    }

    private static JSONObject dailySchema() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("summary", stringSchema());
        properties.put("mindMap", arraySchema(objectSchema(new JSONObject()
                .put("id", stringSchema())
                .put("label", stringSchema())
                .put("parentId", stringSchema()))));
        properties.put("timeline", arraySchema(objectSchema(new JSONObject()
                .put("time", stringSchema())
                .put("event", stringSchema()))));
        properties.put("topics", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("keyEvents", arraySchema(stringSchema()));
        properties.put("decisions", arraySchema(stringSchema()));
        properties.put("todos", arraySchema(objectSchema(new JSONObject()
                .put("task", stringSchema())
                .put("status", stringSchema())
                .put("evidence", stringSchema()))));
        properties.put("ideas", arraySchema(stringSchema()));
        properties.put("unresolved", arraySchema(stringSchema()));
        properties.put("people", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("places", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("timeAllocation", arraySchema(objectSchema(new JSONObject()
                .put("category", stringSchema())
                .put("minutes", integerSchema())
                .put("evidence", stringSchema()))));
        properties.put("recurringTopics", arraySchema(objectSchema(new JSONObject()
                .put("topic", stringSchema())
                .put("count", integerSchema())
                .put("summary", stringSchema()))));
        properties.put("searchIndex", arraySchema(stringSchema()));
        return objectSchema(properties);
    }

    private static JSONObject rollupSchema() throws Exception {
        JSONObject properties = new JSONObject();
        properties.put("summary", stringSchema());
        properties.put("highlights", arraySchema(stringSchema()));
        properties.put("topics", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema())
                .put("count", integerSchema()))));
        properties.put("decisions", arraySchema(stringSchema()));
        properties.put("todos", arraySchema(objectSchema(new JSONObject()
                .put("task", stringSchema())
                .put("status", stringSchema())
                .put("evidence", stringSchema()))));
        properties.put("ideas", arraySchema(stringSchema()));
        properties.put("unresolved", arraySchema(stringSchema()));
        properties.put("people", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("places", arraySchema(objectSchema(new JSONObject()
                .put("name", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("timeAllocation", arraySchema(objectSchema(new JSONObject()
                .put("category", stringSchema())
                .put("minutes", integerSchema())
                .put("evidence", stringSchema()))));
        properties.put("trends", arraySchema(objectSchema(new JSONObject()
                .put("label", stringSchema())
                .put("summary", stringSchema()))));
        properties.put("mindMap", arraySchema(objectSchema(new JSONObject()
                .put("id", stringSchema())
                .put("label", stringSchema())
                .put("parentId", stringSchema()))));
        properties.put("searchIndex", arraySchema(stringSchema()));
        return objectSchema(properties);
    }

    private static JSONObject stringSchema() throws Exception {
        return new JSONObject().put("type", "string");
    }

    private static JSONObject integerSchema() throws Exception {
        return new JSONObject().put("type", "integer").put("minimum", 0);
    }

    private static JSONObject arraySchema(JSONObject items) throws Exception {
        return new JSONObject().put("type", "array").put("items", items);
    }

    private static JSONObject objectSchema(JSONObject properties) throws Exception {
        JSONArray required = new JSONArray();
        Iterator<String> keys = properties.keys();
        while (keys.hasNext()) required.put(keys.next());
        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", required)
                .put("additionalProperties", false);
    }

    private static String extractOutputText(JSONObject response) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) return null;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type"))) {
                    return part.optString("text", null);
                }
                if ("refusal".equals(part.optString("type"))) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String readBody(InputStream input) throws Exception {
        if (input == null) return "";
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private static String extractErrorMessage(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject error = root.optJSONObject("error");
            if (error != null) return error.optString("message", "OpenAI API request failed");
        } catch (Exception ignored) {
        }
        return raw == null || raw.trim().isEmpty() ? "OpenAI API request failed" : raw;
    }

    public static final class Response {
        public final String responseId;
        public final JSONObject analysis;
        public final JSONObject usage;

        Response(String responseId, JSONObject analysis, JSONObject usage) {
            this.responseId = responseId;
            this.analysis = analysis;
            this.usage = usage;
        }
    }

    public static final class ApiException extends Exception {
        public final int statusCode;
        public final boolean retryable;

        ApiException(int statusCode, boolean retryable, String message) {
            super(message);
            this.statusCode = statusCode;
            this.retryable = retryable;
        }
    }
}
