package app.rebubble.di

import app.rebubble.data.remote.api.ServerCredentialsProvider
import app.rebubble.data.repo.EncryptedSecretStore
import app.rebubble.data.repo.SecretStore
import app.rebubble.data.repo.ServerConfigRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds `data.repo` interfaces to their production implementations.
 *
 * [ServerCredentialsProvider] here supersedes T3's null/null placeholder binding that used to
 * live in [NetworkModule]: [ServerConfigRepository] is the real, persisted implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindServerCredentialsProvider(impl: ServerConfigRepository): ServerCredentialsProvider

    @Binds
    @Singleton
    abstract fun bindSecretStore(impl: EncryptedSecretStore): SecretStore
}
