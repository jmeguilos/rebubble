package app.rebubble.data.media

import android.content.Context
import app.rebubble.data.local.entity.AttachmentEntity
import coil3.ImageLoader
import coil3.map.Mapper
import coil3.request.Options
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Maps an [AttachmentEntity] to its on-disk [File] when [AttachmentEntity.localPath] is set
 * and the file exists. Does **not** trigger downloads — chat UI (T18) calls
 * [app.rebubble.data.repo.AttachmentRepository.ensureDownloaded] explicitly first.
 */
class AttachmentEntityMapper : Mapper<AttachmentEntity, File> {
    override fun map(data: AttachmentEntity, options: Options): File? {
        val path = data.localPath ?: return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }
}

/**
 * Hilt provider for the app [ImageLoader]. T18 wires this into Compose via
 * `LocalImageLoader` / `AsyncImage(imageLoader = …)` — nothing is registered on Application here.
 *
 * Crossfade is enabled via Coil 3's Android `ImageLoader.Builder.crossfade` extension.
 * Downsampling is left to Coil's size resolvers (no manual full-bitmap decode).
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilSetup {

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        createImageLoader(context)

    /** Test/production shared builder — keeps the smoke test free of Hilt. */
    fun createImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(AttachmentEntityMapper())
            }
            .build()
}
