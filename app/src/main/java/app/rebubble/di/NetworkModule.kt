package app.rebubble.di

import app.rebubble.data.remote.api.BlueBubblesApi
import app.rebubble.data.remote.api.DynamicBaseUrlInterceptor
import app.rebubble.data.remote.api.GuidAuthInterceptor
import app.rebubble.data.remote.api.ServerCredentialsProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Singleton

/**
 * Retrofit is wired against this placeholder base URL because the real server address is only
 * known once the user has configured one (and can change at runtime). [DynamicBaseUrlInterceptor]
 * rewrites every request's scheme/host/port to the configured server at call time.
 */
private const val PLACEHOLDER_BASE_URL = "http://placeholder.invalid/api/v1/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    /**
     * Placeholder credentials provider. `ServerConfigRepository` (a later task) will supersede
     * this binding with one backed by the user's real, persisted server configuration; until
     * then this keeps the Hilt graph compiling with "no server configured yet" semantics (both
     * [GuidAuthInterceptor] and [DynamicBaseUrlInterceptor] already handle null gracefully / by
     * surfacing an error, since there is genuinely nothing configured yet in that state).
     */
    @Provides
    @Singleton
    fun provideServerCredentialsProvider(): ServerCredentialsProvider =
        object : ServerCredentialsProvider {
            override fun url(): String? = null
            override fun password(): String? = null
        }

    @Provides
    @Singleton
    fun provideGuidAuthInterceptor(credentials: ServerCredentialsProvider): GuidAuthInterceptor =
        GuidAuthInterceptor(credentials)

    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(
        credentials: ServerCredentialsProvider
    ): DynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor(credentials)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        baseUrlInterceptor: DynamicBaseUrlInterceptor,
        guidAuthInterceptor: GuidAuthInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(guidAuthInterceptor)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideBlueBubblesApi(retrofit: Retrofit): BlueBubblesApi =
        retrofit.create(BlueBubblesApi::class.java)
}
