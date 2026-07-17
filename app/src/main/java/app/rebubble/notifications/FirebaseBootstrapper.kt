package app.rebubble.notifications

import android.content.Context
import android.os.Build
import android.util.Log
import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.apiCall
import app.rebubble.data.remote.api.apiCallNullable
import app.rebubble.data.remote.dto.requests.FcmDeviceRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [FirebaseBootstrapper.setup]. Failures are typed so callers (T16/T19) can surface a
 * banner; the app remains fully functional via periodic sync either way.
 */
sealed interface FcmSetupResult {
    data class Success(val token: String) : FcmSetupResult

    data class Failure(val step: Step, val cause: Throwable) : FcmSetupResult {
        enum class Step {
            FETCH_CLIENT,
            MAP_OPTIONS,
            INIT_FIREBASE,
            FETCH_TOKEN,
            REGISTER_DEVICE,
        }
    }
}

/**
 * Runtime Firebase bootstrap for Rebubble:
 * 1. `GET /fcm/client` → map to [FirebaseOptionsParams]
 * 2. Initialize default [com.google.firebase.FirebaseApp] (idempotent)
 * 3. Fetch FCM token
 * 4. `POST /fcm/device { name: Build.MODEL, identifier: token }`
 *
 * Every step failure becomes [FcmSetupResult.Failure] — never throws to the caller.
 */
@Singleton
class FirebaseBootstrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: BlueBubblesApi,
    private val runtime: FirebaseRuntime,
) {

    /**
     * Best-effort FCM setup. Safe to call repeatedly (idempotent Firebase init + device
     * re-registration). Returns [FcmSetupResult]; does not throw.
     */
    suspend fun setup(): FcmSetupResult {
        val json = try {
            apiCall { api.fcmClient() }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "GET /fcm/client failed", e)
            return FcmSetupResult.Failure(FcmSetupResult.Failure.Step.FETCH_CLIENT, e)
        }

        val params = try {
            fcmClientToFirebaseOptions(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "fcm client mapping failed", e)
            return FcmSetupResult.Failure(FcmSetupResult.Failure.Step.MAP_OPTIONS, e)
        }

        try {
            runtime.ensureInitialized(context, params)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Firebase init failed", e)
            return FcmSetupResult.Failure(FcmSetupResult.Failure.Step.INIT_FIREBASE, e)
        }

        val token = try {
            runtime.fetchToken()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "FCM token fetch failed", e)
            return FcmSetupResult.Failure(FcmSetupResult.Failure.Step.FETCH_TOKEN, e)
        }

        return registerDevice(token)
    }

    /**
     * Re-registers an FCM token with the BlueBubbles server (used by [setup] and
     * [PushMessagingService.onNewToken]). Best-effort; returns typed failure, never throws.
     */
    suspend fun registerDevice(token: String): FcmSetupResult {
        return try {
            apiCallNullable {
                api.addFcmDevice(
                    FcmDeviceRequest(
                        name = Build.MODEL,
                        identifier = token,
                    ),
                )
            }
            Log.d(LOG_TAG, "FCM device registered")
            FcmSetupResult.Success(token)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "POST /fcm/device failed", e)
            FcmSetupResult.Failure(FcmSetupResult.Failure.Step.REGISTER_DEVICE, e)
        }
    }

    private companion object {
        const val LOG_TAG = "FirebaseBootstrapper"
    }
}
