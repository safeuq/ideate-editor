package intellijandroid.client.desktop

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import com.jetbrains.rd.ide.model.LxAffineTransform
import com.jetbrains.rd.ide.model.LxCacheableImage
import com.jetbrains.rd.ide.model.LxCachedImage
import com.jetbrains.rd.ide.model.LxClearStateCommand
import com.jetbrains.rd.ide.model.LxClipStateChange
import com.jetbrains.rd.ide.model.LxColorPaint
import com.jetbrains.rd.ide.model.LxDrawImageCommand
import com.jetbrains.rd.ide.model.LxDrawOperation
import com.jetbrains.rd.ide.model.LxDrawTextCommand
import com.jetbrains.rd.ide.model.LxFillShapeCommand
import com.jetbrains.rd.ide.model.LxFontStateChange
import com.jetbrains.rd.ide.model.LxGradientPaint
import com.jetbrains.rd.ide.model.LxPaintStateChange
import com.jetbrains.rd.ide.model.LxPlainFont
import com.jetbrains.rd.ide.model.LxPngImageContent
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxRect
import com.jetbrains.rd.ide.model.LxRectShape
import com.jetbrains.rd.ide.model.LxRoundRectShape
import com.jetbrains.rd.ide.model.LxStateChange
import com.jetbrains.rd.ide.model.LxTransformStateChange
import com.jetbrains.rd.ide.model.NamedColor
import com.jetbrains.rd.ide.model.RgbaColor
import intellijandroid.client.ui.lux.LuxImages
import intellijandroid.client.ui.lux.LuxPainter
import intellijandroid.client.ui.lux.PngDecoder
import intellijandroid.client.ui.lux.luxScreenScale
import intellijandroid.client.ui.theme.ClientFonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

/** Hand-made Lux batches drawn onto a bitmap; values like those of a real Rename dialog. */
class LuxPainterTest {
  private val skiaPng = PngDecoder { bytes -> Image.makeFromEncoded(bytes).toComposeImageBitmap() }
  private val images = LuxImages(skiaPng)
  private val painter =
    LuxPainter(
      images,
      ClientFonts(FontFamily.Monospace, FontFamily.SansSerif),
      createFontFamilyResolver(),
      Density(2f),
    )
  private val bitmap = ImageBitmap(200, 120)

  private fun paint(scale: Float, vararg operations: LxDrawOperation): PixelMap {
    painter.paint(Canvas(bitmap), scale, operations.toList())
    return bitmap.toPixelMap()
  }

  private fun op(command: com.jetbrains.rd.ide.model.LxDrawCommand, vararg changes: LxStateChange) =
    LxDrawOperation(command, changes.toList())

  private fun translate(x: Double, y: Double) =
    LxTransformStateChange(LxAffineTransform(doubleArrayOf(1.0, 0.0, 0.0, 1.0, x, y)))

  private fun color(red: Int, green: Int, blue: Int) =
    LxPaintStateChange(LxColorPaint(RgbaColor(red, green, blue, 255)))

  private fun rect(x: Double, y: Double, width: Double, height: Double) =
    LxRectShape(x, y, width, height)

  private fun PixelMap.rgb(x: Int, y: Int): Triple<Int, Int, Int> =
    this[x, y].let {
      Triple((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt())
    }

  @Test
  fun `a default button's gradient comes from the theme key, under its transform and the scale`() {
    // Rename's Refactor button: Button.default.startBackground → control-brand-bg #3871E1.
    val gradient =
      LxGradientPaint(
        NamedColor(
          "Button.default.startBackground",
          NamedColor("Button.darcula.defaultStartColor", RgbaColor(56, 79, 107, 255)),
        ),
        NamedColor("Button.default.endBackground", RgbaColor(35, 49, 67, 255)),
        LxPoint(0.0, 0.0),
        LxPoint(0.0, 32.0),
        false,
      )
    val pixels =
      paint(
        2f,
        op(
          LxFillShapeCommand(LxRoundRectShape(2.0, 2.0, 40.0, 20.0, 8.0, 8.0)),
          translate(10.0, 10.0),
          LxPaintStateChange(gradient),
        ),
      )
    // Backend (12..52, 12..32), on the bitmap × 2: (24..104, 24..64).
    assertEquals(Triple(0x38, 0x71, 0xE1), pixels.rgb(60, 44))
    assertEquals(0f, pixels[10, 44].alpha, "left of the button: nothing drawn")
  }

  @Test
  fun `at the reported screen scale the backend's transform carries it, not the canvas too`() {
    // A Retina Mac: the backend was told scale 2, so its transform includes it (10 → 20 px).
    luxScreenScale = 2f
    try {
      val scaled =
        LxTransformStateChange(LxAffineTransform(doubleArrayOf(2.0, 0.0, 0.0, 2.0, 0.0, 0.0)))
      val pixels =
        paint(2f, op(LxFillShapeCommand(rect(0.0, 0.0, 10.0, 10.0)), scaled, color(255, 0, 0)))
      assertEquals(Triple(255, 0, 0), pixels.rgb(19, 19))
      assertEquals(0f, pixels[21, 21].alpha, "not scaled a second time by the density")
    } finally {
      luxScreenScale = 1f
    }
  }

  @Test
  fun `the clip stays in device space when a later operation moves the transform`() {
    val pixels =
      paint(
        1f,
        op(
          LxFillShapeCommand(rect(0.0, 0.0, 100.0, 100.0)),
          LxClipStateChange(rect(0.0, 0.0, 30.0, 30.0)),
          translate(20.0, 20.0),
          color(255, 0, 0),
        ),
        // Same clip, new transform: still only inside (0..30, 0..30).
        op(LxFillShapeCommand(rect(0.0, 0.0, 100.0, 100.0)), translate(5.0, 5.0), color(0, 255, 0)),
      )
    assertEquals(Triple(0, 255, 0), pixels.rgb(25, 25))
    assertEquals(0f, pixels[35, 35].alpha, "outside the clip")
    assertEquals(0f, pixels[2, 2].alpha, "before the second transform's origin")
  }

  @Test
  fun `text is drawn at its baseline and shrunk into the width the backend measured`() {
    val pixels =
      paint(
        1f,
        op(
          LxDrawTextCommand("Refactor", 17f, 22f, 30f, 15.7f),
          LxFontStateChange(LxPlainFont("Inter", 0, 13f)),
          LxPaintStateChange(LxColorPaint(NamedColor("Button.default.foreground", null))),
        ),
      )
    val lit =
      (0 until 200)
        .flatMap { x -> (0 until 40).map { y -> x to y } }
        .filter { (x, y) -> pixels[x, y].alpha > 0.5f }
    assertTrue(lit.isNotEmpty(), "the text drew something")
    assertTrue(lit.all { (x, _) -> x in 16..48 }, "within 17..47: ${lit.maxOf { it.first }}")
    assertTrue(lit.all { (_, y) -> y in 8..26 }, "around baseline 22: ${lit.map { it.second }}")
    // Button.default.foreground = text-over-accent, white.
    assertEquals(
      Triple(255, 255, 255),
      lit
        .map { pixels.rgb(it.first, it.second) }
        .maxBy {
          it.first + it.second + it.third
        },
    )
  }

  @Test
  fun `an unknown theme key falls back to the color the backend sent with it`() {
    val pixels =
      paint(
        1f,
        op(
          LxFillShapeCommand(rect(0.0, 0.0, 10.0, 10.0)),
          LxPaintStateChange(LxColorPaint(NamedColor("No.such.key", RgbaColor(10, 20, 30, 255)))),
        ),
      )
    assertEquals(Triple(10, 20, 30), pixels.rgb(5, 5))
  }

  @Test
  fun `clear state drops the transform and clip`() {
    val pixels =
      paint(
        1f,
        op(
          LxFillShapeCommand(rect(0.0, 0.0, 10.0, 10.0)),
          LxClipStateChange(rect(50.0, 50.0, 10.0, 10.0)),
          translate(50.0, 50.0),
          color(0, 0, 255),
        ),
        op(LxClearStateCommand()),
        op(LxFillShapeCommand(rect(0.0, 0.0, 10.0, 10.0)), color(255, 255, 0)),
      )
    assertEquals(Triple(255, 255, 0), pixels.rgb(5, 5))
    assertEquals(Triple(0, 0, 255), pixels.rgb(55, 55))
  }

  @Test
  fun `an image is cached by id, reused, and gone once the backend removes it`() {
    val png =
      Surface.makeRasterN32Premul(4, 4).run {
        canvas.clear(0xFFFF0000.toInt())
        makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
      }
    val identity = LxAffineTransform(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    val first =
      paint(
        1f,
        op(
          LxDrawImageCommand(
            LxCacheableImage(7, LxPngImageContent(png), false),
            LxRect(0, 0, 16, 16),
            identity,
          ),
        ),
        op(LxDrawImageCommand(LxCachedImage(7), LxRect(40, 0, 16, 16), identity)),
      )
    assertEquals(Triple(255, 0, 0), first.rgb(8, 8), "drawn from the PNG, scaled into dst")
    assertEquals(Triple(255, 0, 0), first.rgb(48, 8), "drawn again from the cache")

    images.remove(listOf(7))
    val afterRemove =
      paint(1f, op(LxDrawImageCommand(LxCachedImage(7), LxRect(80, 0, 16, 16), identity)))
    assertEquals(0f, afterRemove[88, 8].alpha, "removed images draw nothing")
  }
}
