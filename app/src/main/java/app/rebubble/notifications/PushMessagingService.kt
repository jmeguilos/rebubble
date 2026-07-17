package app.rebubble.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * Manifest-registered FCM entry point (`com.google.firebase.MESSAGING_EVENT`).
 *
 * Firebase instantiates this service against the **default** [com.google.firebase.FirebaseApp]
 * context. That is why [FirebaseBootstrapper] / [DefaultFirebaseRuntime] initialize the default
 * app (mirroring upstream `BlueBubblesFirebaseMessagingService` + `FirebaseAuthHandler`), not a
 * named `"rebubble"` app.
 *
 * All message handling is delegated to [PushHandler]; token refresh re-registers via
 * [FirebaseBootstrapper.registerDevice].
 */
@AndroidEntryPoint
class PushMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushHandler: PushHandler

    @Inject
    lateinit var bootstrapper: FirebaseBootstrapper

    @Inject
    @Named("fcm")
    lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.d(LOG_TAG, "FCM message received type=${data["type"]}")
        scope.launch {
            pushHandler.handle(data)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(LOG_TAG, "FCM token refreshed; re-registering device")
        scope.launch {
            when (val result = bootstrapper.registerDevice(token)) {
                is FcmSetupResult.Success -> Log.d(LOG_TAG, "token re-registered")
                is FcmSetupResult.Failure ->
                    Log.w(LOG_TAG, "token re-register failed at ${result.step}", result.cause)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "PushMessagingService"
    }
}
