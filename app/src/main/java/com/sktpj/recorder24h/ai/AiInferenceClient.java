package com.sktpj.recorder24h.ai;

import android.content.Context;

public final class AiInferenceClient {
    private AiInferenceClient() {
    }

    public static boolean isConfigured(Context context) {
        return AiProviderStore.isConfigured(context);
    }

    public static String modelId(Context context) {
        return AiProviderStore.modelId(context);
    }

    public static boolean requiresNetwork(Context context) {
        return !AiProviderStore.isLocalGemma(context);
    }

    public static OpenAiLunaClient.Response analyzeHourly(
            Context context, AiAnalysisRepository.SourceWindow source) throws Exception {
        return runPrioritized(
                context,
                AiAnalysisScheduler.KIND_HOURLY,
                source.periodStartMs,
                source.periodEndMs,
                () -> AiProviderStore.isLocalGemma(context)
                        ? Gemma4LocalClient.analyzeHourly(context, source)
                        : OpenAiLunaClient.analyzeHourly(requireApiKey(context), source));
    }

    public static OpenAiLunaClient.Response analyzeDaily(
            Context context, AiAnalysisRepository.SourceWindow source) throws Exception {
        return runPrioritized(
                context,
                AiAnalysisScheduler.KIND_DAILY,
                source.periodStartMs,
                source.periodEndMs,
                () -> AiProviderStore.isLocalGemma(context)
                        ? Gemma4LocalClient.analyzeDaily(context, source)
                        : OpenAiLunaClient.analyzeDaily(requireApiKey(context), source));
    }

    public static OpenAiLunaClient.Response analyzeRollup(
            Context context, String kind, AiRollupRepository.RollupSource source) throws Exception {
        return runPrioritized(
                context,
                kind,
                source.periodStartMs,
                source.periodEndMs,
                () -> AiProviderStore.isLocalGemma(context)
                        ? Gemma4LocalClient.analyzeRollup(context, kind, source)
                        : OpenAiLunaClient.analyzeRollup(requireApiKey(context), kind, source));
    }

    private static OpenAiLunaClient.Response runPrioritized(
            Context context,
            String kind,
            long periodStartMs,
            long periodEndMs,
            AnalysisCall call) throws Exception {
        String queueId = AiAnalysisScheduler.targetQueueId(kind, periodStartMs, periodEndMs);
        AiPriorityGate.Turn turn = AiPriorityGate.awaitTurn(context, queueId);
        if (turn == null) {
            throw new IllegalStateException("AI queue target is no longer available");
        }
        long startedAtMs = System.currentTimeMillis();
        try {
            OpenAiLunaClient.Response response = call.run();
            if (AiAnalysisScheduler.KIND_HOURLY.equals(kind)) {
                AiHourlyDetailPreserver.preserve(response);
            }
            AiProcessingDurationStore.record(
                    context,
                    kind,
                    periodStartMs,
                    periodEndMs,
                    System.currentTimeMillis() - startedAtMs);
            return response;
        } finally {
            turn.close();
        }
    }

    private static String requireApiKey(Context context) throws Exception {
        String apiKey = OpenAiKeyStore.load(context);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("OpenAI API key is not configured");
        }
        return apiKey;
    }

    private interface AnalysisCall {
        OpenAiLunaClient.Response run() throws Exception;
    }
}
