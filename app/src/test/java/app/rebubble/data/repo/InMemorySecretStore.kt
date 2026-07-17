package app.rebubble.data.repo

/**
 * Plain in-memory [SecretStore] fake for tests. `EncryptedSecretStore` (the production
 * implementation) needs the Android keystore and so can only be exercised on-device/emulator;
 * this fake stands in for it in JVM/Robolectric unit tests.
 */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getString(key: String): String? = values[key]

    override fun remove(key: String) {
        values.remove(key)
    }
}
