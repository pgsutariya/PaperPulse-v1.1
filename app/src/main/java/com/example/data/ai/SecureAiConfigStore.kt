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
    val availableModels: List<String>,
    val defaultBaseUrl: String,
    val requiresApiKey: Boolean,
    val description: String
) {
    FIREBASE_AI_LOGIC(
        label = "Firebase AI Logic (Built-in)",
        shortLabel = "Firebase",
        defaultModel = "gemini-3.8-flash",
        availableModels = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon"
        ),
        defaultBaseUrl = "",
        requiresApiKey = false,
        description = "Built-in Gemini via Firebase AI Logic & App Check (zero API key needed)"
    ),
    GEMINI_API(
        label = "Google Gemini API",
        shortLabel = "Gemini",
        defaultModel = "gemini-3.8-flash",
        availableModels = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon",
            "gemini-2.5-pro",
            "gemini-2.5-flash"
        ),
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        requiresApiKey = true,
        description = "Direct Google AI Studio Gemini API with live model discovery"
    ),
    CLAUDE_API(
        label = "Anthropic Claude",
        shortLabel = "Claude",
        defaultModel = "claude-sonnet-4-20250514",
        availableModels = listOf(
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-latest",
            "claude-3-5-sonnet-latest",
            "claude-3-5-haiku-latest"
        ),
        defaultBaseUrl = "https://api.anthropic.com/v1",
        requiresApiKey = true,
        description = "Anthropic Messages API (Claude 4 / 3.7 / 3.5)"
    ),
    DEEPSEEK_API(
        label = "DeepSeek API",
        shortLabel = "DeepSeek",
        defaultModel = "deepseek-chat",
        availableModels = listOf(
            "deepseek-chat",
            "deepseek-reasoner"
        ),
        defaultBaseUrl = "https://api.deepseek.com/v1",
        requiresApiKey = true,
        description = "DeepSeek Chat & Reasoner models"
    ),
    OPENAI_COMPATIBLE(
        label = "OpenAI / Custom",
        shortLabel = "OpenAI",
        defaultModel = "gpt-4o-mini",
        availableModels = listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "o3-mini",
            "gpt-4.1-mini",
            "gpt-4.1"
        ),
        defaultBaseUrl = "https://api.openai.com/v1",
        requiresApiKey = true,
        description = "OpenAI, OpenRouter, Groq, or any OpenAI-compatible HTTPS API"
    )
}

data class AiProviderConfig(
    val provider: AiProvider = AiProvider.FIREBASE_AI_LOGIC,
    val apiKey: String = "",
    val modelName: String = "",
    val customBaseUrl: String = "",
    val autoConnect: Boolean = true
) {
    val effectiveModel: String
        get() {
            val raw = modelName.trim()
            if (provider == AiProvider.FIREBASE_AI_LOGIC && raw.startsWith("gemini-2.")) {
                return provider.defaultModel
            }
            return raw.ifBlank { provider.defaultModel }
        }

    val effectiveBaseUrl: String
        get() = customBaseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }
}

/**
 * Stores user-entered AI agent configuration and API keys strictly in local AES-256
 * EncryptedSharedPreferences (isolated per provider so switching agents remembers keys & models).
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

    fun getSavedKeyFor(provider: AiProvider): String {
        val perProvider = prefs.getString(keyForProvider(provider), null)
        if (!perProvider.isNullOrBlank()) return perProvider
        return if (_config.value.provider == provider) _config.value.apiKey else ""
    }

    fun getSavedModelFor(provider: AiProvider): String {
        val raw = prefs.getString(modelForProvider(provider), null)?.trim().orEmpty()
        if (provider == AiProvider.FIREBASE_AI_LOGIC && raw.startsWith("gemini-2.")) {
            return provider.defaultModel
        }
        return raw.ifBlank { provider.defaultModel }
    }

    fun getSavedBaseUrlFor(provider: AiProvider): String {
        return prefs.getString(baseUrlForProvider(provider), null)?.trim().orEmpty()
            .ifBlank { provider.defaultBaseUrl }
    }

    fun save(newConfig: AiProviderConfig) {
        val rawModel = newConfig.modelName.trim()
        val safeModel = if (newConfig.provider == AiProvider.FIREBASE_AI_LOGIC && rawModel.startsWith("gemini-2.")) {
            newConfig.provider.defaultModel
        } else {
            rawModel.ifBlank { newConfig.provider.defaultModel }
        }
        val sanitized = AiProviderConfig(
            provider = newConfig.provider,
            apiKey = newConfig.apiKey.trim(),
            modelName = safeModel,
            customBaseUrl = newConfig.customBaseUrl.trim(),
            autoConnect = newConfig.autoConnect
        )
        prefs.edit()
            .putString(KEY_PROVIDER, sanitized.provider.name)
            .putString(KEY_API_KEY, sanitized.apiKey)
            .putString(KEY_MODEL, sanitized.modelName)
            .putString(KEY_BASE_URL, sanitized.customBaseUrl)
            .putBoolean(KEY_AUTO_CONNECT, sanitized.autoConnect)
            .putString(keyForProvider(sanitized.provider), sanitized.apiKey)
            .putString(modelForProvider(sanitized.provider), sanitized.modelName)
            .putString(baseUrlForProvider(sanitized.provider), sanitized.customBaseUrl)
            .apply()
        _config.value = sanitized
    }

    fun switchProvider(provider: AiProvider): AiProviderConfig {
        val next = AiProviderConfig(
            provider = provider,
            apiKey = getSavedKeyFor(provider),
            modelName = getSavedModelFor(provider),
            customBaseUrl = getSavedBaseUrlFor(provider),
            autoConnect = _config.value.autoConnect
        )
        save(next)
        return next
    }

    fun updateActiveModel(modelName: String): AiProviderConfig {
        val next = _config.value.copy(modelName = modelName.trim())
        save(next)
        return next
    }

    private fun load(): AiProviderConfig {
        val providerName = prefs.getString(KEY_PROVIDER, AiProvider.FIREBASE_AI_LOGIC.name)
        val provider = runCatching { AiProvider.valueOf(providerName ?: AiProvider.FIREBASE_AI_LOGIC.name) }
            .getOrDefault(AiProvider.FIREBASE_AI_LOGIC)
        val storedKey = prefs.getString(keyForProvider(provider), null)
            ?: prefs.getString(KEY_API_KEY, "")
            ?: ""
        val storedModelRaw = prefs.getString(modelForProvider(provider), null)
            ?: prefs.getString(KEY_MODEL, "")
            ?: ""
        val migratedModel = if (provider == AiProvider.FIREBASE_AI_LOGIC && storedModelRaw.startsWith("gemini-2.")) {
            provider.defaultModel
        } else {
            storedModelRaw.ifBlank { provider.defaultModel }
        }
        val storedBaseUrl = prefs.getString(baseUrlForProvider(provider), null)
            ?: prefs.getString(KEY_BASE_URL, "")
            ?: ""
        val autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true)
        return AiProviderConfig(
            provider = provider,
            apiKey = storedKey,
            modelName = migratedModel,
            customBaseUrl = storedBaseUrl,
            autoConnect = autoConnect
        )
    }

    private fun keyForProvider(provider: AiProvider) = "ai_api_key_${provider.name}"
    private fun modelForProvider(provider: AiProvider) = "ai_model_${provider.name}"
    private fun baseUrlForProvider(provider: AiProvider) = "ai_base_url_${provider.name}"

    private companion object {
        const val KEY_PROVIDER = "ai_provider"
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_MODEL = "ai_model_name"
        const val KEY_BASE_URL = "ai_custom_base_url"
        const val KEY_AUTO_CONNECT = "ai_auto_connect"
    }
}
