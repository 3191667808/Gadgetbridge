package nodomain.freeyourgadget.gadgetbridge.activities.endurain

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException

@Suppress("DEPRECATION")
class DreeveTokenManager(context: Context) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(DreeveTokenManager::class.java)
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val tokenPreferences = createPreferences(context)

    private fun createPreferences(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                "dreeve_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            LOG.warn("Unable to decrypt Dreeve token preferences, resetting them instead\n", e)
            context.getSharedPreferences("dreeve_tokens", Context.MODE_PRIVATE)
                .edit(commit = true) { clear() }
            EncryptedSharedPreferences.create(
                context,
                "dreeve_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun saveToken(apiToken: String) {
        tokenPreferences.edit {
            putString("api_token", apiToken)
        }
    }

    fun clearTokens() {
        tokenPreferences.edit { clear() }
    }

    fun getAPIToken(): String? = tokenPreferences.getString("api_token", null)

    fun isLoggedIn(): Boolean {
        return getAPIToken() != null && getAPIToken()?.startsWith("drv_") == true
    }
}