package intellijandroid.client.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.dp
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.LxAffineTransform
import com.jetbrains.rd.ide.model.LxBasicStroke
import com.jetbrains.rd.ide.model.LxColorPaint
import com.jetbrains.rd.ide.model.LxDrawCommand
import com.jetbrains.rd.ide.model.LxDrawOperation
import com.jetbrains.rd.ide.model.LxDrawShapeCommand
import com.jetbrains.rd.ide.model.LxDrawTextCommand
import com.jetbrains.rd.ide.model.LxFillShapeCommand
import com.jetbrains.rd.ide.model.LxFontStateChange
import com.jetbrains.rd.ide.model.LxGradientPaint
import com.jetbrains.rd.ide.model.LxPaintStateChange
import com.jetbrains.rd.ide.model.LxPlainFont
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxRectShape
import com.jetbrains.rd.ide.model.LxRoundRectShape
import com.jetbrains.rd.ide.model.LxStateChange
import com.jetbrains.rd.ide.model.LxStrokeStateChange
import com.jetbrains.rd.ide.model.LxTransformStateChange
import com.jetbrains.rd.ide.model.NamedColor
import com.jetbrains.rd.ide.model.RgbaColor
import intellijandroid.client.ui.lux.LuxImages
import intellijandroid.client.ui.lux.LuxPainter
import intellijandroid.client.ui.theme.LocalClientFonts

/**
 * A Rename dialog drawn by [LuxPainter] from hand-made Lux operations, with the texts, theme keys,
 * text boxes and button geometry of a real one (the UI session's recording, docs/lux-protocol.md
 * section 6). The layout around them is approximate.
 */
@Composable
internal fun SampleLuxDialog() {
  val fonts = LocalClientFonts.current
  val resolver = LocalFontFamilyResolver.current
  val density = LocalDensity.current
  val bitmap =
    remember(fonts, resolver, density) {
      ImageBitmap((358 * density.density).toInt(), (254 * density.density).toInt()).also {
        LuxPainter(LuxImages(), fonts, resolver, density)
          .paint(Canvas(it), density.density, renameOperations())
      }
    }
  Image(bitmap, "Rename (Lux)", Modifier.size(358.dp, 254.dp))
}

private fun renameOperations(): List<LxDrawOperation> = buildList {
  fun op(command: LxDrawCommand, vararg changes: LxStateChange) =
    add(LxDrawOperation(command, changes.toList()))
  fun at(x: Double, y: Double) =
    LxTransformStateChange(LxAffineTransform(doubleArrayOf(1.0, 0.0, 0.0, 1.0, x, y)))
  fun key(name: String, fallback: AbstractColor? = null) =
    LxPaintStateChange(LxColorPaint(NamedColor(name, fallback)))
  fun text(text: String, x: Float, y: Float, width: Float) =
    LxDrawTextCommand(text, x, y, width, 15.733028f)
  val thin = LxStrokeStateChange(LxBasicStroke(1f, 0, 0, 10f, FloatArray(0), 0f))

  op(
    LxFillShapeCommand(LxRectShape(0.0, 0.0, 358.0, 254.0)),
    key("Panel.background"),
    LxFontStateChange(LxPlainFont("Inter", 0, 13f)),
  )
  op(
    text("Rename file 'smoke.iml' and its usages to:", 0f, 13f, 255.01535f),
    at(12.0, 12.0),
    key("Label.foreground"),
  )

  // The name field, focused, with the name selected without its extension.
  op(
    LxFillShapeCommand(LxRoundRectShape(0.0, 0.0, 334.0, 30.0, 8.0, 8.0)),
    at(12.0, 36.0),
    key("TextField.background"),
  )
  op(
    LxDrawShapeCommand(LxRoundRectShape(1.0, 1.0, 332.0, 28.0, 8.0, 8.0)),
    LxStrokeStateChange(LxBasicStroke(2f, 0, 0, 10f, FloatArray(0), 0f)),
    key("Component.focusColor"),
  )
  op(
    LxFillShapeCommand(LxRectShape(8.0, 7.0, 38.0, 17.0)),
    thin,
    key("TextField.selectionBackground"),
  )
  op(text("smoke.iml", 8f, 20f, 61.547165f), key("TextField.foreground"))

  for ((index, label) in
    listOf("Search in comments and strings" to 197.383f, "Search for references" to 133.87848f)
      .withIndex()) {
    op(
      LxDrawShapeCommand(LxRoundRectShape(4.5, 4.5, 15.0, 15.0, 4.0, 4.0)),
      at(12.0, 78.0 + 28 * index),
      thin,
      key("Component.borderColor"),
    )
    op(text(label.first, 28f, 17f, label.second), key("CheckBox.foreground"))
  }

  op(text("Scope:", 0f, 13f, 42.379456f), at(12.0, 146.0), key("Label.foreground"))
  op(
    LxFillShapeCommand(LxRoundRectShape(0.0, 0.0, 270.0, 28.0, 8.0, 8.0)),
    at(76.0, 139.0),
    key("ComboBox.nonEditableBackground"),
  )
  op(
    LxDrawShapeCommand(LxRoundRectShape(0.5, 0.5, 269.0, 27.0, 8.0, 8.0)),
    thin,
    key("Component.borderColor"),
  )
  op(text("Project Files", 10f, 19f, 76f), key("ComboBox.foreground"))

  // South panel: Preview on the left, then Cancel and the default Refactor (x 260, as recorded).
  for ((x, label) in
    listOf(
      12.0 to text("Preview", 17f, 22f, 49.119553f),
      170.0 to text("Cancel", 18f, 22f, 42.305588f),
    )) {
    op(
      LxFillShapeCommand(LxRoundRectShape(2.0, 2.0, 80.0, 28.0, 8.0, 8.0)),
      at(x, 212.0),
      key("Button.startBackground"),
    )
    op(
      LxDrawShapeCommand(LxRoundRectShape(2.5, 2.5, 79.0, 27.0, 8.0, 8.0)),
      thin,
      key("Button.startBorderColor"),
    )
    op(label, key("Button.foreground"))
  }
  op(
    LxFillShapeCommand(LxRoundRectShape(2.0, 2.0, 80.0, 28.0, 8.0, 8.0)),
    at(260.0, 212.0),
    LxPaintStateChange(
      LxGradientPaint(
        NamedColor(
          "Button.default.startBackground",
          NamedColor("Button.darcula.defaultStartColor", RgbaColor(56, 79, 107, 255)),
        ),
        NamedColor(
          "Button.default.endBackground",
          NamedColor("Button.darcula.defaultEndColor", RgbaColor(35, 49, 67, 255)),
        ),
        LxPoint(0.0, 0.0),
        LxPoint(0.0, 32.0),
        false,
      ),
    ),
  )
  op(text("Refactor", 17f, 22f, 52.480362f), key("Button.default.foreground"))
}
