package app.rebubble.di

import app.rebubble.BuildConfig
import app.rebubble.notifications.FirebaseBootstrapper
import app.rebubble.ui.settings.SettingsAppVersionProvider
import app.rebubble.ui.settings.SettingsFcmSetupAction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object SettingsModule {
    @Provides
    fun provideSettingsFcmSetupAction(bootstrapper: FirebaseBootstrapper): SettingsFcmSetupAction =
        SettingsFcmSetupAction { bootstrapper.setup() }

    @Provides
    fun provideSettingsAppVersionProvider(): SettingsAppVersionProvider =
        SettingsAppVersionProvider { BuildConfig.VERSION_NAME }
}
