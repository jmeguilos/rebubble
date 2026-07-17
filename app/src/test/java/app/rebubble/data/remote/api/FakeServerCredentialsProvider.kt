package app.rebubble.data.remote.api

/** Simple in-memory [ServerCredentialsProvider] fake for tests. */
class FakeServerCredentialsProvider(
    var urlValue: String? = null,
    var passwordValue: String? = null
) : ServerCredentialsProvider {
    override fun url(): String? = urlValue
    override fun password(): String? = passwordValue
}
