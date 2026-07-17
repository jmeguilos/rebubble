package app.rebubble.di

import app.rebubble.data.sync.Reconciler
import app.rebubble.notifications.FirebaseBootstrapper
import app.rebubble.ui.onboarding.OnboardingFcmSetupAction
import app.rebubble.ui.onboarding.OnboardingReconcileAction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object OnboardingModule {
    @Provides
    fun provideOnboardingReconcileAction(reconciler: Reconciler): OnboardingReconcileAction =
        OnboardingReconcileAction { reconciler.reconcile() }

    @Provides
    fun provideOnboardingFcmSetupAction(bootstrapper: FirebaseBootstrapper): OnboardingFcmSetupAction =
        OnboardingFcmSetupAction { bootstrapper.setup() }
}
