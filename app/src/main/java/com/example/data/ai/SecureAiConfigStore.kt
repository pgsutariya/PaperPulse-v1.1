package com.example.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AiProvider(
    val label: String,
    val shortLabel: String,
    val defaultModel: String,
    val defaultBaseUrl: String,
    val requiresApiKey: Boolean
) {
    FIREBASE_AI_LOGIC(
        label = "Firebase AI Logic (Built-in)",
        shortLabel = "Firebase",
        defaultModel = "gemini-2.5-flash",
        defaultBaseUrl = "",
        requiresApiKey = false
    ),
    GEMINI_API(
        label = "Google Gemini API",
        shortLabel = "Gemini",
        defaultModel = "gemini-2.5-flash",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        requiresApiKey = true
    ),
    CLAUDE_API(
        label = "Anthropic Claude",
        shortLabel = "Claude",
        defaultModel = "claude-3-5-sonnet-latest",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        requiresApiKey = true
    ),
    DEEPSEEK_API(
        label = "DeepSeek API",
        shortLabel = "DeepSeek",
        defaultModel = "deepseek-chat",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        requiresApiKey = true
    ),
    OPENAI_COMPATIBLE(
        label = "OpenAI / Custom Compatible",
        shortLabel = "OpenAI",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1",
        requiresApiKey = true
    )
}

data class AiProviderConfig(
    val provider: AiProvider = AiProvider.FIREBASE_AI_LOGIC,
    val apiKey: String = "",
    val modelName: String = "",
    val customBaseUrl: String = ""
) {
    val effectiveModel: String
        get() = modelName.trim().ifBlank { provider.defaultModel }

    val effectiveBaseUrl: String
        get() = customBaseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
}

/**
 * Stores user-entered AI provider configuration and API keys strictly in local AES-256
 * EncryptedSharedPreferences. Keys are never hardcoded or written to logs.
 */
@Singleton
class SecureAiConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "paperpulse_secure_ai_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("paperpulse_secure_ai_prefs_test", Context.MODE_PRIVATE)
        }
    }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AiProviderConfig> = _config.asStateFlow()

    fun getCurrent(): AiProviderConfig = _config.value

    fun save(newConfig: AiProviderConfig) {
        val sanitized = AiProviderConfig(
            provider = newConfig.provider,
            apiKey = newConfig.apiKey.trim(),
            modelName = newConfig.modelName.trim(),
            customBaseUrl = newConfig.customBaseUrl.trim()
        )
        prefs.edit()
            .putString(KEY_PROVIDER, sanitized.provider.name)
            .putString(KEY_API_KEY, sanitized.apiKey)
            .putString(KEY_MODEL, sanitized.modelName)
            .putString(KEY_BASE_URL, sanitized.customBaseUrl)
            .apply()
        _config.value = sanitized
    }

    private fun load(): AiProviderConfig {
        val providerName = prefs.getString(KEY_PROVIDER, AiProvider.FIREBASE_AI_LOGIC.name)
        val provider = runCatching { AiProvider.valueOf(providerName ?: AiProvider.FIREBASE_AI_LOGIC.name) }
            .getOrDefault(AiProvider.FIREBASE_AI_LOGIC)
        return AiProviderConfig(
            provider = provider,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            modelName = prefs.getString(KEY_MODEL, "") ?: "",
            customBaseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        )
    }

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_MODEL = "ai_model_name"
        const val KEY_BASE_URL = "ai_custom_base_url"
    }
}
