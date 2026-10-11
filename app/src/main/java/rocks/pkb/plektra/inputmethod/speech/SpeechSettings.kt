package rocks.pkb.plektra.inputmethod.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import rocks.pkb.plektra.SettingsManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SpeechSettings {
    val durationOptionsMs = listOf(60_000L, 180_000L, 300_000L, 600_000L, 900_000L, 0L)
    const val DEFAULT_MAX_DURATION_MS = 180_000L
    private const val CREDENTIALS = "speech_credentials"
    private const val KEY_ALIAS = "plektra_speech_credentials"
    private fun prefs(context: Context) = SettingsManager.getPreferences(context)

    fun provider(context: Context): SpeechProvider = SpeechProvider.entries.firstOrNull {
        it.name == prefs(context).getString("speech_provider", null)
    } ?: SpeechProvider.SYSTEM

    fun setProvider(context: Context, provider: SpeechProvider) {
        prefs(context).edit().putString("speech_provider", provider.name).apply()
    }

    fun model(context: Context, provider: SpeechProvider): SpeechModel {
        if (!provider.isCloud) return SpeechModel(provider.name, provider.name)
        val defaults = if (provider == SpeechProvider.OPENAI) SpeechModels.openAiDefaults else SpeechModels.openRouterDefaults
        val id = prefs(context).getString("speech_model_${provider.name}", null) ?: defaults.first().id
        val protocol = SpeechProtocol.entries.firstOrNull {
            it.name == prefs(context).getString("speech_protocol_${provider.name}", null)
        } ?: SpeechProtocol.TRANSCRIPTION
        val name = prefs(context).getString("speech_model_name_${provider.name}", id) ?: id
        return defaults.firstOrNull { it.id == id } ?: SpeechModel(id, name, protocol)
    }

    fun setModel(context: Context, provider: SpeechProvider, model: SpeechModel) {
        prefs(context).edit().putString("speech_model_${provider.name}", model.id)
            .putString("speech_model_name_${provider.name}", model.name)
            .putString("speech_protocol_${provider.name}", model.protocol.name).apply()
    }

    fun continuousPreview(context: Context): Boolean =
        prefs(context).getBoolean("speech_continuous_preview", false)

    fun setContinuousPreview(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("speech_continuous_preview", enabled).apply()
    }

    fun silenceMs(context: Context): Long = prefs(context).getLong("speech_silence_ms", 3000L).let {
        if (it in listOf(0L, 1500L, 3000L, 5000L)) it else 3000L
    }

    fun maxDurationMs(context: Context): Long =
        prefs(context).getLong("speech_max_duration_ms", DEFAULT_MAX_DURATION_MS).let {
            if (it in durationOptionsMs) it else DEFAULT_MAX_DURATION_MS
        }

    fun setMaxDurationMs(context: Context, ms: Long) {
        require(ms in durationOptionsMs)
        prefs(context).edit().putLong("speech_max_duration_ms", ms).apply()
    }

    fun setSilenceMs(context: Context, ms: Long) {
        require(ms in listOf(0L, 1500L, 3000L, 5000L))
        prefs(context).edit().putLong("speech_silence_ms", ms).apply()
    }

    fun hasKey(context: Context, provider: SpeechProvider): Boolean =
        context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE).contains(provider.name)

    fun removeKey(context: Context, provider: SpeechProvider) {
        context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE).edit().remove(provider.name).apply()
    }

    @Synchronized
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun saveKey(context: Context, provider: SpeechProvider, key: String) {
        require(provider.isCloud)
        val trimmed = key.trim()
        require(trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE).edit().putString(provider.name, encoded).commit())
    }

    fun readKey(context: Context, provider: SpeechProvider): String? {
        val encoded = context.getSharedPreferences(CREDENTIALS, Context.MODE_PRIVATE).getString(provider.name, null) ?: return null
        val parts = encoded.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
