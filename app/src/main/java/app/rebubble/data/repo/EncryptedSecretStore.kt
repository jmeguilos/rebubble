package app.rebubble.data.repo

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [SecretStore], backed by `EncryptedSharedPreferences` ("rebubble_secrets") with a
 * keystore-backed [MasterKey].
 *
 * This class needs the real Android Keystore (via [MasterKey.Builder]) and is therefore only
 * exercised on a device/emulator, never by the JVM/Robolectric unit test suite — those tests use
 * an in-memory [SecretStore] fake instead (`InMemorySecretStore` in the test sources). There is
 * no unit test coverage of this class's actual encryption behavior; that seam is deliberately as
 * thin as possible (four one-line methods delegating to `EncryptedSharedPreferences`) to minimize
 * what's only verifiable on-device.
 *
 * `EncryptedSharedPreferences`/`MasterKey` are marked deprecated as of `androidx.security-crypto`
 * 1.1.0 (in favor of a lower-level, more flexible Tink-based API); this class is deliberately
 * thin enough to swap out for the replacement API in a follow-up without touching [SecretStore]
 * or its callers.
 */
@Suppress("DEPRECATION")
@Singleton
class EncryptedSecretStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SecretStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "rebubble_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
