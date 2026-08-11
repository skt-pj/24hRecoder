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
        if (AiProviderStore.isLocalGemma(context)) {
            return Gemma4LocalClient.analyzeHourly(context, source);
        }
        return OpenAiLunaClient.analyzeHourly(requireApiKey(context), source);
    }

    public static OpenAiLunaClient.Response analyzeDaily(
            Context context, AiAnalysisRepository.SourceWindow source) throws Exception {
        if (AiProviderStore.isLocalGemma(context)) {
            return Gemma4LocalClient.analyzeDaily(context, source);
        }
        return OpenAiLunaClient.analyzeDaily(requireApiKey(context), source);
    }

    public static OpenAiLunaClient.Response analyzeRollup(
            Context context, String kind, AiRollupRepository.RollupSource source) throws Exception {
        if (AiProviderStore.isLocalGemma(context)) {
            return Gemma4LocalClient.analyzeRollup(context, kind, source);
        }
        return OpenAiLunaClient.analyzeRollup(requireApiKey(context), kind, source);
    }

    private static String requireApiKey(Context context) throws Exception {
        String apiKey = OpenAiKeyStore.load(context);
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("OpenAI API key is not configured");
        }
        return apiKey;
    }
}
