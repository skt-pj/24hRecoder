package com.sktpj.recorder24h.ai;

import android.content.Context;
import android.content.SharedPreferences;

public final class AiProviderStore {
    public static final String PROVIDER_OPENAI_LUNA = "openai_luna";
    public static final String PROVIDER_GEMMA4_LOCAL = "gemma4_local";

    private static final String PREFS = "ai_provider";
    private static final String KEY_PROVIDER = "provider";

    private AiProviderStore() {
    }

    public static String getProvider(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String provider = preferences.getString(KEY_PROVIDER, PROVIDER_OPENAI_LUNA);
        if (PROVIDER_GEMMA4_LOCAL.equals(provider)) {
            return PROVIDER_GEMMA4_LOCAL;
        }
        return PROVIDER_OPENAI_LUNA;
    }

    public static void setProvider(Context context, String provider) {
        String normalized = PROVIDER_GEMMA4_LOCAL.equals(provider)
                ? PROVIDER_GEMMA4_LOCAL : PROVIDER_OPENAI_LUNA;
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PROVIDER, normalized)
                .apply();
    }

    public static boolean isLocalGemma(Context context) {
        return PROVIDER_GEMMA4_LOCAL.equals(getProvider(context));
    }

    public static boolean isConfigured(Context context) {
        if (isLocalGemma(context)) {
            return Gemma4ModelManager.isReady(context);
        }
        return OpenAiKeyStore.hasKey(context);
    }

    public static String modelId(Context context) {
        return isLocalGemma(context) ? Gemma4LocalClient.MODEL_ID : OpenAiLunaClient.MODEL;
    }
}
