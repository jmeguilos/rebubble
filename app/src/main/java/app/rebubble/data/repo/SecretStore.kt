package app.rebubble.data.repo

/**
 * Minimal key/value secret storage seam.
 *
 * Exists to abstract away `EncryptedSharedPreferences` (the production implementation,
 * [EncryptedSecretStore]), which needs the Android keystore and so can only be exercised
 * on-device/emulator — never in a JVM/Robolectric unit test. Tests use a plain in-memory fake
 * (`InMemorySecretStore`, in the test sources) instead.
 */
interface SecretStore {
    fun putString(key: String, value: String)
    fun getString(key: String): String?
    fun remove(key: String)
}
