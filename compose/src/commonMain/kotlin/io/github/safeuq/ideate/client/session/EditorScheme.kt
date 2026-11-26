package io.github.safeuq.ideate.client.session

import com.jetbrains.ide.model.highlighterRegistration.ColorsSchemeModel
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributes
import com.jetbrains.ide.model.highlighterRegistration.IdeaTextAttributesKey
import com.jetbrains.ide.model.highlighterRegistration.TextAttributesKeyModel
import com.jetbrains.rd.ide.model.AbstractColor
import com.jetbrains.rd.ide.model.DualColor
import com.jetbrains.rd.ide.model.IdeaColorKey
import com.jetbrains.rd.ide.model.RgbColor
import com.jetbrains.rd.ide.model.RgbaColor
import kotlin.math.pow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** IntelliJ's `EffectType`, as sent in `IdeaTextAttributes.effectType`. */
@Serializable
enum class TextEffect {
  LINE_UNDERSCORE,
  WAVE_UNDERSCORE,
  BOXED,
  STRIKEOUT,
  BOLD_LINE_UNDERSCORE,
  BOLD_DOTTED_LINE,
}

/**
 * Resolved text attributes. Colors are ARGB; null means "keep what is below" (the default
 * foreground, no background).
 */
@Serializable
data class TextStyle(
  val foreground: Long? = null,
  val background: Long? = null,
  /** IntelliJ `Font` style bits: 1 bold, 2 italic. */
  val fontType: Int = 0,
  val effect: TextEffect? = null,
  val effectColor: Long? = null,
  /** The color of the mark on the scrollbar (IntelliJ's error stripe); null: no mark. */
  val errorStripeColor: Long? = null,
) {
  val isBold
    get() = fontType and 1 != 0

  val isItalic
    get() = fontType and 2 != 0
}

/**
 * One editor color scheme, resolved like IntelliJ's `EditorColorsSchemeImpl`: for a key, look in
 * the scheme, then its parent scheme; if neither has an entry, move to the key's fallback
 * (`JAVA_KEYWORD` → `DEFAULT_KEYWORD`) and repeat. The backend sends each scheme with only its own
 * entries ("Islands Dark" has 154; Darcula supplies the rest).
 */
@Serializable
class EditorScheme(
  val name: String,
  /** Per scheme of the chain (the scheme, then its parent): its own entries by key. */
  private val attributes: List<Map<String, TextStyle>>,
  private val colors: List<Map<String, Long>>,
  private val colorFallbacks: Map<String, String>,
) {
  @Transient val defaultText: TextStyle = style(listOf(listOf("TEXT")))
  @Transient
  val background: Long = defaultText.background ?: error("Scheme $name has no TEXT background")
  @Transient
  val foreground: Long = defaultText.foreground ?: error("Scheme $name has no TEXT foreground")

  /**
   * Whether the default background is dark, as IntelliJ's `ColorUtil.isDark`: the contrast of the
   * background against black, `(luminance + 0.05) / 0.05`, is below 4.5. For example `0x1E1F22` is
   * dark and `0xFFFFFF` is not.
   */
  @Transient val isDark: Boolean = (relativeLuminance(background) + 0.05) / 0.05 < 4.5

  /**
   * [keyChains]: for each key of a highlighter, the key followed by its fallbacks. The first key
   * that resolves wins.
   */
  fun style(keyChains: List<List<String>>): TextStyle {
    for (keyChain in keyChains) {
      for (key in keyChain) {
        for (level in attributes) {
          level[key]?.let {
            return it
          }
        }
      }
    }
    return TextStyle()
  }

  /** A scheme color such as `CARET_ROW_COLOR`; follows color-key fallbacks too. */
  fun color(key: String): Long? {
    var current: String? = key
    while (current != null) {
      for (level in colors) level[current]?.let {
        return it
      }
      current = colorFallbacks[current]
    }
    return null
  }

  companion object {
    /** WCAG relative luminance of an ARGB colour, as IntelliJ's `ColorUtil.getLuminance`. */
    internal fun relativeLuminance(argb: Long): Double {
      fun linear(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
      }
      return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    /**
     * Parents of the bundled schemes. ponytail: hard-coded; the protocol doesn't send a scheme's
     * parent.
     */
    private val PARENTS = mapOf("Islands Dark" to "Darcula", "Islands Light" to "Default")

    fun resolve(name: String, schemes: Map<String, ColorsSchemeModel>): EditorScheme? {
      val chain = generateSequence(name) { PARENTS[it] }.map(schemes::get).toList()
      if (chain.any { it == null }) return null
      val models = chain.filterNotNull()
      return EditorScheme(
        name,
        attributes =
          models.map { scheme ->
            scheme.attributes
              .filter { !it.isInherited && it.attributes != null }
              .associate { it.textAttributesKey.externalName to it.attributes!!.toStyle() }
          },
        colors =
          models.map { scheme ->
            scheme.colors
              .filter { !it.isInherited }
              .mapNotNull { entry ->
                entry.color?.toArgb()?.let { entry.colorKey.externalName to it }
              }
              .toMap()
          },
        colorFallbacks =
          models
            .flatMap { it.colors }
            .mapNotNull { entry ->
              (entry.colorKey as? IdeaColorKey)?.fallback?.let {
                entry.colorKey.externalName to it.externalName
              }
            }
            .toMap(),
      )
    }

    /**
     * The key followed by its fallbacks, for later resolution against whichever scheme is current.
     */
    fun keyChain(key: TextAttributesKeyModel): List<String> =
      generateSequence(key) { (it as? IdeaTextAttributesKey)?.fallback }
        .map { it.externalName }
        .toList()

    /** [dark]: which half of a `DualColor` to use; null leaves such colours out. */
    fun IdeaTextAttributes.toStyle(dark: Boolean? = null) =
      TextStyle(
        foreground = foregroundColor?.toArgb(dark),
        background = backgroundColor?.toArgb(dark),
        fontType = fontType,
        // An effect without a color is not drawn in IntelliJ.
        effect = effectColor?.let { TextEffect.valueOf(effectType.name) },
        effectColor = effectColor?.toArgb(dark),
        errorStripeColor = errorStripeColor?.toArgb(dark),
      )

    /**
     * A `DualColor` is a `JBColor` on the backend (light and dark values); JetBrains Client picks
     * the half for the current theme, here [dark] says which.
     */
    fun AbstractColor.toArgb(dark: Boolean? = null): Long? =
      when (this) {
        is DualColor ->
          when (dark) {
            true -> darkColor.toArgb()
            false -> lightColor.toArgb()
            null -> null
          }
        is RgbaColor ->
          (alpha.toLong() shl 24) or
            (red.toLong() shl 16) or
            (green.toLong() shl 8) or
            blue.toLong()
        is RgbColor ->
          (0xFFL shl 24) or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
        else -> null
      }
  }
}
