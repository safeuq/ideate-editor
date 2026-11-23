package io.github.safeuq.ideate.client.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import io.github.safeuq.ideate.client.session.TextEffect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where IntelliJ paints a text effect, ported from `EffectPainter2D` (platform-api,
 * `com.intellij.ui.paint`) as `EditorPainter.paintTextEffect` calls it, with IntelliJ's default
 * registry values (`ide.text.effect.new`, `.new.metrics` and `.new.scale` all true). Sizes are in
 * the editor's units (px at scale 1, dp here); `y` values are measured down from the baseline.
 *
 * Example, JetBrains Mono 12 on a 2x screen with 5 units under the baseline: an underline is 0.5
 * thick at 1.5; a bold one 1.5 thick at 1.5; the wave zig-zags between 2 and 4, one step every 2
 * units, drawn 0.7 thick; a strikeout is 0.5 thick at -3.5.
 */
internal data class EffectLine(val top: Float, val thickness: Float)

/**
 * JetBrains Mono's line metrics in em (its `post` and `OS/2` tables: underlinePosition -155,
 * underlineThickness 50, yStrikeoutPosition 320, yStrikeoutSize 50, unitsPerEm 1000), which Java's
 * `LineMetrics` reports scaled by the font size.
 *
 * ponytail: constants for the one editor font; read the tables if the font becomes a setting.
 */
private const val UNDERLINE_OFFSET_EM = 0.155f
private const val UNDERLINE_THICKNESS_EM = 0.05f
private const val STRIKEOUT_OFFSET_EM = 0.32f
private const val STRIKEOUT_THICKNESS_EM = 0.05f

/** `JBUIScale.DEF_SYSTEM_FONT_SIZE` and `UISettings.getDefFontSize()` on macOS. */
private const val SYSTEM_FONT_SIZE = 13f

/**
 * `PaintUtil.alignToInt`: [value] moved onto a whole device pixel, down or up ([ceiling]). Example
 * at density 2: 1.86 → 1.5 down, 2.0 up.
 */
private fun alignToPixel(value: Float, density: Float, ceiling: Boolean): Float =
  (if (ceiling) ceil(value * density) else floor(value * density)) / density

/** `EffectPainter2D.maybeScaleFontMetricsThickness`: never thinner than the font's scale. */
private fun scaledThickness(metricsThickness: Float, fontSize: Float, density: Float): Float {
  var fontScale = fontSize / SYSTEM_FONT_SIZE
  val normalized = fontSize / SYSTEM_FONT_SIZE
  if (normalized > 1) fontScale *= 1 / (normalized * normalized + 1) + 0.5f
  if (density <= 1f) fontScale = max(1f, Math.round(fontScale).toFloat())
  return max(metricsThickness, fontScale)
}

/**
 * `EffectPainter2D.paintUnderline` for [effect] (an underline kind): [descent] is the room under
 * the baseline (`EditorView.getDescent`, which includes half of the extra line spacing).
 */
internal fun underlineOf(
  effect: TextEffect,
  fontSize: Float,
  descent: Float,
  density: Float,
): EffectLine {
  val factor =
    when (effect) {
      TextEffect.LINE_UNDERSCORE -> 1
      TextEffect.WAVE_UNDERSCORE -> 3
      else -> 2 // BOLD_LINE_UNDERSCORE, BOLD_DOTTED_LINE
    }
  val pixel = 1 / density
  // The wave rounds up on a HiDPI screen unless the font is larger than the system's.
  val ceiling =
    density > 1f && effect == TextEffect.WAVE_UNDERSCORE && fontSize / SYSTEM_FONT_SIZE <= 1
  var thickness =
    alignToPixel(
      factor * scaledThickness(UNDERLINE_THICKNESS_EM * fontSize, fontSize, density),
      density,
      ceiling,
    )
  if (thickness <= 0) thickness = pixel
  var offset = min(descent - thickness, max(pixel, UNDERLINE_OFFSET_EM * fontSize))
  if (offset < pixel) {
    offset = if (descent > 3 * pixel) pixel else 0f
    thickness = alignToPixel(min(thickness, descent - offset), density, ceiling)
  } else offset = alignToPixel(offset, density, ceiling)
  return EffectLine(offset, thickness)
}

/** `EffectPainter2D.STRIKE_THROUGH`: a line through the text, above the baseline. */
internal fun strikeoutOf(fontSize: Float, density: Float): EffectLine {
  val offset = alignToPixel(STRIKEOUT_OFFSET_EM * fontSize, density, ceiling = false)
  var thickness =
    alignToPixel(
      scaledThickness(STRIKEOUT_THICKNESS_EM * fontSize, fontSize, density),
      density,
      ceiling = false,
    )
  if (thickness <= 0) thickness = 1 / density
  return EffectLine(-offset, thickness)
}

/**
 * Draws [effect] in [color] for the text from [left] to [right] on a line whose baseline is at
 * [baseline] and which is [lineHeight] tall (from y = 0), as IntelliJ does.
 */
internal fun DrawScope.drawTextEffect(
  effect: TextEffect,
  color: Color,
  left: Float,
  right: Float,
  baseline: Float,
  lineHeight: Float,
  fontSize: Float,
) {
  if (right <= left) return
  // Geometry in dp, drawn in px.
  val descent = (lineHeight - baseline) / density
  fun px(value: Float) = value * density
  when (effect) {
    TextEffect.LINE_UNDERSCORE,
    TextEffect.BOLD_LINE_UNDERSCORE -> {
      val line = underlineOf(effect, fontSize, descent, density)
      drawRect(
        color,
        Offset(left, baseline + px(line.top)),
        Size(right - left, px(line.thickness)),
      )
    }
    TextEffect.STRIKEOUT -> {
      val line = strikeoutOf(fontSize, density)
      drawRect(
        color,
        Offset(left, baseline + px(line.top)),
        Size(right - left, px(line.thickness)),
      )
    }
    // Cached.BOLD_DOTTED_UNDERSCORE: dots as wide as the line is thick, every second one left
    // out, in step with the editor's x so neighbouring ranges line up.
    TextEffect.BOLD_DOTTED_LINE -> {
      val line = underlineOf(effect, fontSize, descent, density)
      val dot = px(line.thickness)
      val top = baseline + px(line.top)
      val round = line.thickness > 2 || density > 1f
      clipRect(left, top, right, top + dot) {
        var x = floor(left / (2 * dot)) * 2 * dot
        while (x < right) {
          if (round) drawRoundRect(color, Offset(x, top), Size(dot, dot), CornerRadius(dot / 2))
          else drawRect(color, Offset(x, top), Size(dot, dot))
          x += 2 * dot
        }
      }
    }
    // Cached.WAVE_UNDERSCORE: a zig-zag as tall as the "line" minus one unit, one step per that
    // height, 0.7 thick while the line is under 4 units tall; in step with the editor's x.
    TextEffect.WAVE_UNDERSCORE -> {
      val line = underlineOf(effect, fontSize, descent, density)
      val height = line.thickness
      val period = max(height - 1, 1f)
      var lower = height - 1
      var upper = lower - period
      var stroke = 1f
      if (height > 3) {
        var fix = height / 3
        stroke = fix
        if (fix > 1) {
          fix = (fix - 1) / 2
          lower -= fix
          upper += fix
        }
      }
      val top = baseline + px(line.top)
      val step = px(period)
      val path = Path()
      var x = floor(left / (2 * step)) * 2 * step
      path.moveTo(x, top + px(lower))
      if (height + 2 < 6) {
        stroke = 0.7f
        while (x < right) {
          x += step
          path.lineTo(x, top + px(upper))
          x += step
          path.lineTo(x, top + px(lower))
        }
      } else {
        // Taller waves are rounded: four quarter curves per period.
        val quarter = step / 2
        var control = x - quarter / 2
        val center = top + px((upper + lower) / 2)
        while (x < right) {
          control += quarter
          x += quarter
          path.quadraticTo(control, top + px(lower), x, center)
          control += quarter
          x += quarter
          path.quadraticTo(control, top + px(upper), x, top + px(upper))
          control += quarter
          x += quarter
          path.quadraticTo(control, top + px(upper), x, center)
          control += quarter
          x += quarter
          path.quadraticTo(control, top + px(lower), x, top + px(lower))
        }
      }
      clipRect(left, top, right, top + px(height)) {
        drawPath(path, color, style = Stroke(width = px(stroke)))
      }
    }
    // EditorPainter.drawSimpleBorder: a 1-unit frame around the whole line's height.
    TextEffect.BOXED -> {
      val thickness = px(1f)
      drawRect(
        color,
        Offset(left + thickness / 2, thickness / 2),
        Size(right - left - thickness, lineHeight - thickness),
        style = Stroke(thickness),
      )
    }
  }
}
