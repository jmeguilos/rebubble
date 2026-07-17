package app.rebubble.data.remote.api

import app.rebubble.data.remote.dto.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import retrofit2.HttpException

/**
 * Thrown when the BlueBubbles server responds with 401 Unauthorized — the configured password
 * is missing, wrong, or has been rotated server-side.
 */
class AuthError(message: String = "Unauthorized") : Exception(message)

/**
 * Thrown when the BlueBubbles server responds with a non-2xx status (other than 401) whose body
 * parses as an [Envelope] with a typed `error` block (or, if it doesn't parse, falls back to
 * Retrofit's own HTTP status message).
 */
class ApiException(
    val status: Int,
    val errorType: String?,
    val errorMessage: String?
) : Exception("HTTP $status${errorType?.let { " [$it]" } ?: ""}: ${errorMessage ?: "unknown error"}")

private val apiCallJson = Json { ignoreUnknownKeys = true }

/**
 * Thin wrapper around a [BlueBubblesApi] suspend call that unwraps the response [Envelope] and
 * maps failures to typed errors:
 *  - 401 -> [AuthError]
 *  - other non-2xx (with or without a parseable error envelope) -> [ApiException]
 *  - [java.io.IOException] (network failure) passes through unchanged, since it is never caught
 *    here
 *
 * Deliberately a plain suspend function rather than a Retrofit `CallAdapter` — this is the
 * simplest thing that satisfies the contract (YAGNI); a `CallAdapter` would only be worth the
 * extra machinery if callers needed the mapping wired automatically into every interface
 * method's return type.
 *
 * Retrofit's suspend-function support throws [HttpException] for non-2xx responses (the
 * interface methods declare `Envelope<T>` directly rather than `Response<T>`), so that's the
 * only exception type this function needs to translate; any other exception (in particular
 * [java.io.IOException] for network failures) is not caught and simply propagates.
 */
suspend fun <T> apiCall(block: suspend () -> Envelope<T>): T {
    val envelope = try {
        block()
    } catch (e: HttpException) {
        val status = e.code()
        if (status == 401) {
            throw AuthError()
        }

        val body = e.response()?.errorBody()?.string()
        val parsedError = body?.let {
            runCatching { apiCallJson.decodeFromString<Envelope<JsonElement>>(it) }.getOrNull()?.error
        }

        throw ApiException(
            status = status,
            errorType = parsedError?.type,
            errorMessage = parsedError?.message ?: e.message()
        )
    }

    @Suppress("UNCHECKED_CAST")
    return envelope.data as T
}
