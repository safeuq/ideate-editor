package io.github.safeuq.ideate.client.ui.lux

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.jetbrains.rd.ide.model.LxImageContent
import com.jetbrains.rd.ide.model.LxPngImageContent

/** PNG bytes to a bitmap; null when they can't be decoded. The platform supplies it. */
fun interface PngDecoder {
  fun decode(bytes: ByteArray): ImageBitmap?
}

/**
 * The platform's PNG decoder (desktop: Skia, Android: BitmapFactory), set once at startup before
 * any Lux dialog opens, as [io.github.safeuq.ideate.client.ui.theme.Islands.density] is.
 */
@Volatile var pngDecoder: PngDecoder = PngDecoder { null }

/**
 * The screen scale reported to the backend (2 on a Retina Mac, `displayMetrics.density` on
 * Android), set once at startup like [pngDecoder]. The backend then sends images at this scale and
 * its transforms include it, as for JetBrains Client, which reports its AWT screen's default
 * transform. [LuxPainter] takes it back out.
 */
@Volatile var luxScreenScale: Float = 1f

/**
 * The screen size reported to the backend, in dp (its pixels), set once at startup like
 * [luxScreenScale]. IntelliJ fits every dialog to the screen it shows on
 * (`DialogWrapper.fitToScreen` → `ScreenUtil.fitToScreen`), so on a phone this keeps a dialog such
 * as "Trust and Open Project" no wider than the phone. Android: the app's screen in dp; the desktop
 * keeps 1920 × 1200. ponytail: sent when the session connects; a later rotation isn't re-sent.
 */
@Volatile var luxScreenSize: IntSize = IntSize(1920, 1200)

/**
 * Images Lux sends once and then refers to by id, shared by all dialogs of a session, as the
 * JetBrains Client's `LuxImageCache.Frontend` keeps them: `LxCacheableImage(id, content)` stores,
 * `LxCachedImage(id)` reads, `LuxModel.cleanImagesFromCache(ids)` removes. Thread-safe: the
 * backend's removals can arrive on the rd thread while a dialog paints on the UI thread.
 */
class LuxImages(private val decoder: PngDecoder = pngDecoder) {
  private val byId = HashMap<Int, ImageBitmap>()

  @Synchronized
  fun remove(ids: List<Int>) {
    ids.forEach(byId::remove)
  }

  /** Decodes [content] and keeps it under [id]; returns the bitmap, or null if undecodable. */
  @Synchronized
  internal fun store(id: Int, content: LxImageContent): ImageBitmap? =
    decode(content)?.also { byId[id] = it }

  @Synchronized internal fun cached(id: Int): ImageBitmap? = byId[id]

  internal fun decode(content: LxImageContent): ImageBitmap? =
    when (content) {
      is LxPngImageContent -> decoder.decode(content.bytes)
      else -> null
    }
}
