package app.rebubble.data.outbox

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Shared auto-retry policy for outbox workers ([SendTextWorker], [SendAttachmentWorker]).
 *
 * Server sendCache dedup is **in-flight only**: a completed send is not deduped, so auto-retry
 * after an ambiguous outcome can double-send. Only failures that prove the HTTP request never
 * reached the BlueBubbles server are safe to [androidx.work.ListenableWorker.Result.retry].
 */
internal object OutboxRetryPolicy {

    /**
     * Whether an [IOException] proves the HTTP request never reached the BlueBubbles server.
     *
     * Safe: [ConnectException], [UnknownHostException], [SSLHandshakeException] /
     * handshake-[SSLException], and [SocketTimeoutException] whose message contains `"connect"`.
     * All other IOExceptions (read timeouts, connection reset mid-response, `NO_RESPONSE`-style)
     * are ambiguous → caller marks FAILED.
     */
    fun isSafeToRetry(e: IOException): Boolean {
        return when (e) {
            is ConnectException -> true
            is UnknownHostException -> true
            is SSLHandshakeException -> true
            is SSLException -> e.message?.contains("handshake", ignoreCase = true) == true
            is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
            else -> false
        }
    }
}
