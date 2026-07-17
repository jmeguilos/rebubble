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
     * Ensures the default [FirebaseApp] exists for [params]. Idempotent: if a default app is
     * already present, returns without re-initializing (upstream early-out via
     * `FirebaseApp.getInstance()`).
     */
    fun ensureInitialized(context: Context, params: FirebaseOptionsParams)

    /** Fetches an FCM registration token from the default [FirebaseMessaging] instance. */
    suspend fun fetchToken(): String
}

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

        try {
            FirebaseApp.getInstance()
            Log.d(LOG_TAG, "Firebase default app already initialized")
            return
        } catch (_: IllegalStateException) {
            // Not initialized yet — continue.
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
