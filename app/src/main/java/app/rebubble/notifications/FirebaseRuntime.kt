package app.rebubble.notifications

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Thin seam over Firebase SDK calls so [FirebaseBootstrapper] stays unit-testable without Play
 * Services. The production [DefaultFirebaseRuntime] is device-verified (Play Services + real FCM
 * token); unit tests inject a fake.
 *
 * **Default app (not named):** mirrors upstream BlueBubbles
 * (`FirebaseAuthHandler.kt` → `FirebaseApp.initializeApp(context, options)` with no name) so
 * [FirebaseMessaging.getInstance] and [com.google.firebase.messaging.FirebaseMessagingService]
 * bind to the same app. A named `"rebubble"` app would require
 * `FirebaseMessaging.getInstance(app)` and still leave the messaging service on DEFAULT — see
 * task-14-report.md.
 */
interface FirebaseRuntime {
    /**
     * Ensures the default [FirebaseApp] exists for [params].
     *
     * **Server switch:** if a default app already exists but its `projectId` / `applicationId`
     * differ from [params], deletes it and re-initializes (see [needsReinit]). That delete+reinit
     * path is the supported way to move to another BlueBubbles Firebase project in-process.
     *
     * **[FirebaseMessagingService] caveat:** the service binds to the default app only. After
     * delete+reinit the default app is the new project, so subsequent `onMessageReceived` /
     * `onNewToken` callbacks apply to that project — but any in-flight work against the old app
     * is invalidated, and callers must re-fetch and re-register the token (as
     * [FirebaseBootstrapper.setup] already does after [ensureInitialized]).
     */
    fun ensureInitialized(context: Context, params: FirebaseOptionsParams)

    /** Fetches an FCM registration token from the default [FirebaseMessaging] instance. */
    suspend fun fetchToken(): String
}

/**
 * Returns true when an existing default FirebaseApp (described by [current]) must be deleted and
 * re-initialized before using [incoming] — i.e. when `projectId` or `applicationId` differ.
 * Pure / unit-testable; used by [DefaultFirebaseRuntime.ensureInitialized].
 */
fun needsReinit(current: FirebaseOptionsParams, incoming: FirebaseOptionsParams): Boolean =
    current.projectId != incoming.projectId || current.applicationId != incoming.applicationId

/**
 * Production Firebase adapter. Requires Google Play Services; token fetch needs a real device /
 * emulator with Play Services (not reliably headless under Robolectric).
 */
class DefaultFirebaseRuntime : FirebaseRuntime {
    override fun ensureInitialized(context: Context, params: FirebaseOptionsParams) {
        val playStatus = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playStatus != ConnectionResult.SUCCESS) {
            throw IllegalStateException("Google Play Services is not available (status=$playStatus)")
        }

        val existing = try {
            FirebaseApp.getInstance()
        } catch (_: IllegalStateException) {
            null
        }

        if (existing != null) {
            val current = FirebaseOptionsParams(
                apiKey = existing.options.apiKey.orEmpty(),
                applicationId = existing.options.applicationId,
                projectId = existing.options.projectId.orEmpty(),
                gcmSenderId = existing.options.gcmSenderId.orEmpty(),
                storageBucket = existing.options.storageBucket,
                databaseUrl = existing.options.databaseUrl,
            )
            if (!needsReinit(current, params)) {
                Log.d(LOG_TAG, "Firebase default app already initialized")
                return
            }
            Log.d(
                LOG_TAG,
                "Firebase project changed (${current.projectId} → ${params.projectId}); " +
                    "deleting default app for reinit",
            )
            existing.delete()
        }

        val options = FirebaseOptions.Builder()
            .setApiKey(params.apiKey)
            .setApplicationId(params.applicationId)
            .setProjectId(params.projectId)
            .setGcmSenderId(params.gcmSenderId)
            .apply {
                params.databaseUrl?.let { setDatabaseUrl(it) }
                params.storageBucket?.let { setStorageBucket(it) }
            }
            .build()

        FirebaseApp.initializeApp(context, options)
        Log.d(LOG_TAG, "Firebase default app initialized for project=${params.projectId}")
    }

    override suspend fun fetchToken(): String {
        return FirebaseMessaging.getInstance().token.await()
    }

    private companion object {
        const val LOG_TAG = "FirebaseRuntime"
    }
}
