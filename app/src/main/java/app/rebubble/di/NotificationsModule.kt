package app.rebubble.di

import app.rebubble.notifications.DefaultFirebaseRuntime
import app.rebubble.notifications.FirebaseRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Named
import javax.inject.Singleton

/**
 * FCM / Firebase runtime wiring. No google-services plugin — [FirebaseRuntime] initializes from
 * the server-supplied `/fcm/client` payload at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationsModule {

    @Provides
    @Singleton
    fun provideFirebaseRuntime(): FirebaseRuntime = DefaultFirebaseRuntime()

    @Provides
    @Singleton
    @Named("fcm")
    fun provideFcmScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
