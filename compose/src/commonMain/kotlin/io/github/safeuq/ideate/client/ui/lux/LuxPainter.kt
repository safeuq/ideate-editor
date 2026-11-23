package io.github.safeuq.ideate.client.ui.lux

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.DualColor
import com.jetbrains.rd.ide.model.LxAffineTransform
import com.jetbrains.rd.ide.model.LxAliasingStateChange
import com.jetbrains.rd.ide.model.LxAlphaComposite
import com.jetbrains.rd.ide.model.LxArcShape
import com.jetbrains.rd.ide.model.LxBackgroundColorStateChange
import com.jetbrains.rd.ide.model.LxBasicStroke
import com.jetbrains.rd.ide.model.LxCacheableImage
import com.jetbrains.rd.ide.model.LxCachedImage
import com.jetbrains.rd.ide.model.LxClearStateCommand
import com.jetbrains.rd.ide.model.LxClipStateChange
import com.jetbrains.rd.ide.model.LxColorPaint
import com.jetbrains.rd.ide.model.LxCompositeStateChange
import com.jetbrains.rd.ide.model.LxCubicCurveShape
import com.jetbrains.rd.ide.model.LxDiffImage
import com.jetbrains.rd.ide.model.LxDrawCommand
import com.jetbrains.rd.ide.model.LxDrawGlyphShapeCommand
import com.jetbrains.rd.ide.model.LxDrawGlyphTextCommand
import com.jetbrains.rd.ide.model.LxDrawImageCommand
import com.jetbrains.rd.ide.model.LxDrawOperation
import com.jetbrains.rd.ide.model.LxDrawShapeCommand
import com.jetbrains.rd.ide.model.LxDrawTextCommand
import com.jetbrains.rd.ide.model.LxEllipseShape
import com.jetbrains.rd.ide.model.LxFillShapeCommand
import com.jetbrains.rd.ide.model.LxFont
import com.jetbrains.rd.ide.model.LxFontStateChange
import com.jetbrains.rd.ide.model.LxGradientPaint
import com.jetbrains.rd.ide.model.LxImage
import com.jetbrains.rd.ide.model.LxLineShape
import com.jetbrains.rd.ide.model.LxLinearGradientPaint
import com.jetbrains.rd.ide.model.LxNonCacheableImage
import com.jetbrains.rd.ide.model.LxPaint
import com.jetbrains.rd.ide.model.LxPaintStateChange
import com.jetbrains.rd.ide.model.LxPathShape
import com.jetbrains.rd.ide.model.LxPlainFont
import com.jetbrains.rd.ide.model.LxPoint
import com.jetbrains.rd.ide.model.LxPolygonShape
import com.jetbrains.rd.ide.model.LxQuadCurveShape
import com.jetbrains.rd.ide.model.LxRectShape
import com.jetbrains.rd.ide.model.LxRoundRectShape
import com.jetbrains.rd.ide.model.LxShape
import com.jetbrains.rd.ide.model.LxStateChange
import com.jetbrains.rd.ide.model.LxStrokeStateChange
import com.jetbrains.rd.ide.model.LxSubFamilyFont
import com.jetbrains.rd.ide.model.LxTransformStateChange
import com.jetbrains.rd.ide.model.NamedColor
import com.jetbrains.rd.ide.model.RgbaColor
import io.github.safeuq.ideate.client.ui.theme.ClientFonts
import io.github.safeuq.ideate.client.ui.theme.ThemeKeys

/**
 * Replays a Lux dialog's drawing (docs/lux-protocol.md, sections 1 and 5) as the JetBrains Client's
 * `LuxRenderer` does: for each `LxDrawOperation` the state changes apply first, then the command
 * draws. The state lasts across batches, as a Swing `Graphics` does, until `LxClearStateCommand`.
 * One painter per dialog. Call it on the UI thread.
 *
 * Coordinates are the backend's pixels. As in `LuxRenderer.applyToGraphics`:
 * - the transform state replaces the whole transform (AWT `setTransform`), it is not concatenated;
 * - the clip is kept in device space, so a later transform doesn't move it;
 * - an image is drawn into its `dst` under its own extra transform;
 * - text is drawn at its baseline and shrunk sideways when it measures wider than the box the
 *   backend measured (the client's default `lux.fonts.transform.bounds=shrink`).
 *
 * Colors are IntelliJ theme keys with fallbacks; [ThemeKeys] resolves them as Islands Dark does.
 * Inter maps to `fonts.ui` and JetBrains Mono to `fonts.editor`.
 *
 * [density] isn't used for text: text is measured in backend pixels and scaled with the canvas.
 */
class LuxPainter(
  private val images: LuxImages,
  private val fonts: ClientFonts,
  fontFamilyResolver: FontFamily.Resolver,
  @Suppress("UNUSED_PARAMETER") density: Density,
) {
  private val textMeasurer =
    TextMeasurer(fontFamilyResolver, Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 64)
  private var state = State()

  /** The last image sent with `keepForDiff`: what an `LxDiffImage` patches (per dialog). */
  private var diffBase: ImageBitmap? = null

  /** Kinds already reported as not drawn, so each is logged once. */
  private val skipped = HashSet<String>()

  /**
   * Applies one `drawOperations` batch onto [canvas], whose pixels are backend pixels × [scale].
   *
   * The backend's transforms already include [luxScreenScale] (they replace the whole transform, as
   * AWT `setTransform` does on a HiDPI `Graphics2D`), so the canvas is scaled only by what is left:
   * 1 when the screen is the one reported, and the images keep their full resolution.
   */
  fun paint(canvas: Canvas, scale: Float, operations: List<LxDrawOperation>) {
    val left = scale / luxScreenScale
    for (operation in operations) {
      operation.stateChanges.forEach(::apply)
      canvas.save()
      canvas.scale(left, left)
      state.clip?.let { clip -> canvas.clipPath(clip) }
      canvas.concat(matrixOf(state.transform))
      draw(canvas, operation.command)
      canvas.restore()
    }
  }

  private class State {
    /** AWT order: m00 m10 m01 m11 m02 m12. */
    var transform = IDENTITY
    var clip: Path? = null
    var paint: LxPaint? = null
    var font: LxFont? = null
    var stroke: LxBasicStroke? = null
    var alpha = 1f
    var blendMode = BlendMode.SrcOver
  }

  private fun apply(change: LxStateChange) {
    when (change) {
      is LxTransformStateChange -> state.transform = change.transform.matrix
      is LxClipStateChange -> state.clip = change.shape?.let(::pathOf)
      is LxPaintStateChange -> state.paint = change.paint
      is LxFontStateChange -> state.font = change.font
      is LxStrokeStateChange ->
        when (val stroke = change.strokeShape) {
          is LxBasicStroke -> state.stroke = stroke
          else -> skip("stroke ${stroke::class.simpleName}")
        }
      is LxCompositeStateChange ->
        when (val composite = change.composite) {
          is LxAlphaComposite -> {
            state.alpha = composite.alpha
            state.blendMode = blendModeOf(composite.rule)
          }
          else -> skip("composite ${composite::class.simpleName}")
        }
      // The clear color only matters for clearRect, which Lux sends as a fill.
      is LxBackgroundColorStateChange -> Unit
      // Ideate's text is always antialiased.
      is LxAliasingStateChange -> Unit
      else -> skip("state ${change::class.simpleName}")
    }
  }

  private fun draw(canvas: Canvas, command: LxDrawCommand) {
    when (command) {
      is LxFillShapeCommand -> canvas.drawPath(pathOf(command.shape), paint(PaintingStyle.Fill))
      is LxDrawShapeCommand -> canvas.drawPath(pathOf(command.shape), paint(PaintingStyle.Stroke))
      is LxDrawGlyphShapeCommand ->
        canvas.drawPath(pathOf(command.shape), paint(PaintingStyle.Fill))
      is LxDrawTextCommand ->
        drawText(canvas, command.text, command.x, command.y, command.width, state.font)
      is LxDrawGlyphTextCommand ->
        drawText(canvas, command.text, command.x, command.y, null, command.font)
      is LxDrawImageCommand -> drawImage(canvas, command)
      is LxClearStateCommand -> state = State()
      // Copying pixels needs the backing bitmap, which the canvas doesn't expose; Lux uses it to
      // scroll, which Rename/New/Delete don't.
      else -> skip("command ${command::class.simpleName}")
    }
  }

  private fun drawText(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    boxWidth: Float?,
    font: LxFont?,
  ) {
    if (text.isEmpty()) return
    val layout =
      textMeasurer.measure(text, textStyle(font), softWrap = false, maxLines = 1, skipCache = false)
    val measuredWidth = layout.getLineRight(0) - layout.getLineLeft(0)
    canvas.save()
    canvas.translate(x, y)
    // LuxRenderer.adjustTextTransform, "shrink": only narrower, never wider; 0.3 px of slack.
    if (boxWidth != null && boxWidth > 0f && measuredWidth - boxWidth > 0.3f)
      canvas.scale(boxWidth / measuredWidth, 1f)
    canvas.translate(0f, -layout.firstBaseline)
    TextPainter.paint(canvas, layout)
    canvas.restore()
  }

  private fun textStyle(font: LxFont?): TextStyle {
    val (name, style, size) =
      when (font) {
        is LxPlainFont -> Triple(font.name, font.style, font.size)
        is LxSubFamilyFont -> Triple(font.family, font.style, font.size)
        else -> Triple("Inter", 0, 13f)
      }
    val subFamily = (font as? LxSubFamilyFont)?.subFamily.orEmpty()
    // AWT Font styles: 1 bold, 2 italic. A sub-family name refines the weight.
    val weight =
      when {
        subFamily.contains("SemiBold", ignoreCase = true) -> FontWeight.SemiBold
        subFamily.contains("Bold", ignoreCase = true) || style and 1 != 0 -> FontWeight.Bold
        subFamily.contains("Medium", ignoreCase = true) -> FontWeight.Medium
        subFamily.contains("Light", ignoreCase = true) -> FontWeight.Light
        else -> FontWeight.Normal
      }
    val base =
      TextStyle(
        fontFamily = if (name.contains("Mono", ignoreCase = true)) fonts.editor else fonts.ui,
        fontSize = size.sp,
        fontWeight = weight,
        fontStyle = if (style and 2 != 0) FontStyle.Italic else FontStyle.Normal,
      )
    return when (val brush = brush()) {
      is SolidColor -> base.copy(color = brush.value.copy(alpha = brush.value.alpha * state.alpha))
      else -> base.copy(brush = brush, alpha = state.alpha)
    }
  }

  private fun drawImage(canvas: Canvas, command: LxDrawImageCommand) {
    val bitmap = imageOf(command.image) ?: return
    val dst = command.dst
    canvas.save()
    canvas.concat(matrixOf(command.transform.matrix))
    canvas.drawImageRect(
      bitmap,
      IntOffset.Zero,
      IntSize(bitmap.width, bitmap.height),
      IntOffset(dst.x, dst.y),
      IntSize(dst.width, dst.height),
      Paint().apply {
        alpha = state.alpha
        blendMode = state.blendMode
        filterQuality = FilterQuality.High
      },
    )
    canvas.restore()
  }

  private fun imageOf(image: LxImage): ImageBitmap? =
    when (image) {
      is LxCacheableImage ->
        images.store(image.id, image.content)?.also { if (image.keepForDiff) diffBase = it }
      is LxCachedImage -> images.cached(image.id) ?: null.also { skip("unknown cached image") }
      is LxNonCacheableImage ->
        images.decode(image.content)?.also { if (image.keepForDiff) diffBase = it }
      is LxDiffImage -> patchedDiffBase(image)
      else -> null.also { skip("image ${image::class.simpleName}") }
    }

  /** `ImageDiff.applyTo`: the last kept image with the diff's pixels replacing a region. */
  private fun patchedDiffBase(diff: LxDiffImage): ImageBitmap? {
    val base = diffBase ?: return null.also { skip("diff image without a base") }
    val patch = diff.content?.let(images::decode) ?: return base
    val patched = ImageBitmap(base.width, base.height)
    Canvas(patched).apply {
      drawImage(base, Offset.Zero, Paint())
      drawImage(
        patch,
        Offset(diff.x.toFloat(), diff.y.toFloat()),
        Paint().apply {
          blendMode = BlendMode.Src
        },
      )
    }
    diffBase = patched
    return patched
  }

  private fun paint(style: PaintingStyle) =
    Paint().apply {
      isAntiAlias = true
      this.style = style
      blendMode = state.blendMode
      // The gradients are in absolute coordinates; applyTo only needs a size that isn't empty.
      brush().applyTo(Size(1f, 1f), this, state.alpha)
      if (style == PaintingStyle.Stroke) {
        val stroke = state.stroke
        strokeWidth = stroke?.width ?: 1f
        strokeCap =
          when (stroke?.cap) {
            1 -> StrokeCap.Round
            2 -> StrokeCap.Square
            else -> StrokeCap.Butt
          }
        strokeJoin =
          when (stroke?.join) {
            1 -> StrokeJoin.Round
            2 -> StrokeJoin.Bevel
            else -> StrokeJoin.Miter
          }
        strokeMiterLimit = stroke?.miterLimit ?: 10f
        pathEffect =
          stroke
            ?.dash
            ?.takeIf { it.isNotEmpty() }
            ?.let {
              PathEffect.dashPathEffect(it, stroke.dashPhase)
            }
      }
    }

  private fun brush(): Brush =
    when (val paint = state.paint) {
      is LxColorPaint -> SolidColor(colorOf(paint.color))
      is LxGradientPaint ->
        ShaderBrush(
          LinearGradientShader(
            offsetOf(paint.point1),
            offsetOf(paint.point2),
            listOf(colorOf(paint.color1), colorOf(paint.color2)),
            tileMode = if (paint.isCyclic) TileMode.Mirror else TileMode.Clamp,
          ),
        )
      is LxLinearGradientPaint ->
        ShaderBrush(
          LinearGradientShader(
            offsetOf(paint.startPoint),
            offsetOf(paint.endPoint),
            paint.colors.map(::colorOf),
            paint.fractions.toList(),
            // MultipleGradientPaint.CycleMethod: NO_CYCLE, REFLECT, REPEAT.
            when (paint.cycleMode) {
              1 -> TileMode.Mirror
              2 -> TileMode.Repeated
              else -> TileMode.Clamp
            },
          ),
        )
      // LuxGraphicsState's starting paint is JBColor.YELLOW.
      null -> SolidColor(Color.Yellow)
      else -> SolidColor(Color.Transparent).also { skip("paint ${paint::class.simpleName}") }
    }

  private fun colorOf(color: AbstractColor?): Color =
    resolveLuxColor(color) ?: Color.Gray.also { skip("color without a value: $color") }

  private fun skip(what: String) {
    if (skipped.add(what)) println("[lux] not drawn: $what")
  }
}

/**
 * A theme key resolved as Islands Dark does ([ThemeKeys]), else down the fallback chain the backend
 * sent: `NamedColor(name, fallback)` → … → `RgbaColor`; a `DualColor` gives its dark color.
 */
internal fun resolveLuxColor(color: AbstractColor?): Color? =
  when (color) {
    is NamedColor -> ThemeKeys.color(color.name) ?: resolveLuxColor(color.defaultColor)
    is RgbaColor -> Color(color.red, color.green, color.blue, color.alpha)
    is DualColor -> resolveLuxColor(color.darkColor)
    else -> null
  }

private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

/** AWT's flat matrix (m00 m10 m01 m11 [m02 m12]) as a Compose matrix. */
private fun matrixOf(values: DoubleArray): Matrix =
  Matrix().apply {
    this[0, 0] = values[0].toFloat()
    this[0, 1] = values[1].toFloat()
    this[1, 0] = values[2].toFloat()
    this[1, 1] = values[3].toFloat()
    if (values.size >= 6) {
      this[3, 0] = values[4].toFloat()
      this[3, 1] = values[5].toFloat()
    }
  }

private fun matrixOf(transform: LxAffineTransform?): Matrix =
  matrixOf(transform?.matrix ?: IDENTITY)

private fun offsetOf(point: LxPoint) = Offset(point.x.toFloat(), point.y.toFloat())

private fun blendModeOf(rule: Int): BlendMode =
  // java.awt.AlphaComposite rules.
  when (rule) {
    1 -> BlendMode.Clear
    2 -> BlendMode.Src
    4 -> BlendMode.DstOver
    5 -> BlendMode.SrcIn
    6 -> BlendMode.DstIn
    7 -> BlendMode.SrcOut
    8 -> BlendMode.DstOut
    9 -> BlendMode.Dst
    10 -> BlendMode.SrcAtop
    11 -> BlendMode.DstAtop
    12 -> BlendMode.Xor
    else -> BlendMode.SrcOver
  }

/** An `LxShape` as a path, the way `UtilKt.fromLx` builds the AWT shape. */
internal fun pathOf(shape: LxShape): Path =
  Path().apply {
    when (shape) {
      is LxRectShape -> addRect(rectOf(shape.x, shape.y, shape.width, shape.height))
      is LxRoundRectShape ->
        addRoundRect(
          RoundRect(
            rectOf(shape.x, shape.y, shape.width, shape.height),
            // AWT arcs are diameters.
            CornerRadius(shape.arcWidth.toFloat() / 2, shape.arcHeight.toFloat() / 2),
          ),
        )
      is LxEllipseShape -> addOval(rectOf(shape.x, shape.y, shape.width, shape.height))
      is LxLineShape -> {
        moveTo(shape.x1.toFloat(), shape.y1.toFloat())
        lineTo(shape.x2.toFloat(), shape.y2.toFloat())
      }
      // Arc2D.OPEN; AWT angles run counter-clockwise, Compose's clockwise.
      is LxArcShape ->
        arcTo(
          rectOf(shape.x, shape.y, shape.width, shape.height),
          -shape.startAngle.toFloat(),
          -shape.arcAngle.toFloat(),
          forceMoveTo = true,
        )
      is LxPolygonShape -> {
        for (index in 0 until shape.nPoints) {
          val x = shape.xPoints[index].toFloat()
          val y = shape.yPoints[index].toFloat()
          if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        if (shape.nPoints > 0) close()
      }
      is LxPathShape -> {
        // PathIterator.WIND_EVEN_ODD = 0, WIND_NON_ZERO = 1.
        fillType = if (shape.windingMode == 0) PathFillType.EvenOdd else PathFillType.NonZero
        for (element in shape.elements) {
          val s = element.segment
          when (element.segmentType) {
            0 -> moveTo(s[0].toFloat(), s[1].toFloat())
            1 -> lineTo(s[0].toFloat(), s[1].toFloat())
            2 -> quadraticTo(s[0].toFloat(), s[1].toFloat(), s[2].toFloat(), s[3].toFloat())
            3 ->
              cubicTo(
                s[0].toFloat(),
                s[1].toFloat(),
                s[2].toFloat(),
                s[3].toFloat(),
                s[4].toFloat(),
                s[5].toFloat(),
              )
            4 -> close()
          }
        }
      }
      is LxQuadCurveShape -> {
        moveTo(shape.x1.toFloat(), shape.y1.toFloat())
        quadraticTo(
          shape.ctrlX.toFloat(),
          shape.ctrlY.toFloat(),
          shape.x2.toFloat(),
          shape.y2.toFloat(),
        )
      }
      is LxCubicCurveShape -> {
        moveTo(shape.x1.toFloat(), shape.y1.toFloat())
        cubicTo(
          shape.ctrlX1.toFloat(),
          shape.ctrlY1.toFloat(),
          shape.ctrlX2.toFloat(),
          shape.ctrlY2.toFloat(),
          shape.x2.toFloat(),
          shape.y2.toFloat(),
        )
      }
      else -> println("[lux] not drawn: shape ${shape::class.simpleName}")
    }
  }

private fun rectOf(x: Double, y: Double, width: Double, height: Double) =
  Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
