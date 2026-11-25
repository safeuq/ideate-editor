package io.github.safeuq.ideate.client.ui

import io.github.safeuq.ideate.client.ui.theme.ISLANDS_DARK
import io.github.safeuq.ideate.client.ui.theme.ISLANDS_LIGHT
import io.github.safeuq.ideate.client.ui.theme.LafManager
import io.github.safeuq.ideate.client.ui.theme.UiTheme
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * IntelliJ's own icons from `intellij.platform.icons.jar`, picked and recoloured for a theme as
 * IntelliJ does (docs/theme-icons.md): the file by `ImageDescriptorKt.createImageDescriptorList`'s
 * rule, then the theme's `icons.ColorPalette`. Each platform turns the SVG text into a painter
 * (desktop: Skia; Android: AndroidSVG).
 */
class IconJar(file: File) {
  private val jar = ZipFile(file.also { check(it.isFile) { "Missing icons jar $it" } })

  /**
   * The SVG text for [path] in [theme], e.g. `expui/nodes/folder.svg`, or null when the jar has no
   * such icon.
   */
  fun svg(path: String, theme: UiTheme = LafManager.currentTheme): String? {
    // A tinted icon, as `ColorizeProxyIcon` paints it: every painted colour becomes the tint.
    COLORIZED.matchEntire(path)?.let { match ->
      val (color, base) = match.destructured
      return svg(base, theme)?.replace(PAINT, "$1=\"#$color\"")
    }
    val palette = iconPalette(theme)
    val files = imageFiles(path, theme.isDark)
    val body =
      files.firstNotNullOfOrNull { name ->
        jar.getEntry(name)?.let { entry ->
          jar.getInputStream(entry).use { it.readBytes().decodeToString() }
        }
      } ?: files.firstNotNullOfOrNull(SENT_ICONS::get) ?: return null
    return palette.patch(path, body)
  }

  companion object {
    /**
     * Plugin icons the backend sent with their SVG (`ImageIconModel.imageSources`), e.g. the GitHub
     * plugin's `org/jetbrains/plugins/github/expui/pullRequests.svg`: the client's jar doesn't have
     * them. Keyed by file, both `_dark` and plain as sent. Filled by the session before the UI asks
     * for them.
     */
    private val SENT_ICONS = ConcurrentHashMap<String, String>()

    /** Keeps [path]'s SVGs from [sources] (path to SVG bytes), both variants when sent. */
    fun remember(path: String, sources: Map<String, ByteArray>) {
      val name = path.removePrefix("/")
      for (file in listOf(name.removeSuffix(".svg") + "_dark.svg", name)) {
        sources[file]?.let { SENT_ICONS.putIfAbsent(file, it.decodeToString()) }
      }
    }

    /** [path]'s icon from [EXTRA_ICONS] in [theme], or null when it isn't one of them. */
    fun extra(path: String, theme: UiTheme = LafManager.currentTheme): String? {
      val palette = iconPalette(theme)
      return imageFiles(path, theme.isDark).firstNotNullOfOrNull(EXTRA_ICONS::get)?.let {
        palette.patch(path, it)
      }
    }

    /**
     * The files to try, as `createImageDescriptorList`: dark → `name_dark.svg`, then `name.svg`;
     * light → `name.svg` only.
     */
    private fun imageFiles(path: String, isDark: Boolean): List<String> {
      val name = path.removePrefix("/")
      return if (isDark) listOf(name.removeSuffix(".svg") + "_dark.svg", name) else listOf(name)
    }

    private fun iconPalette(theme: UiTheme) =
      checkNotNull(ICON_PALETTES[theme.id]) { "No icon palette for theme ${theme.id}" }

    /**
     * A theme's `icons.ColorPalette` (docs/theme-icons.md §3), split as
     * `UiThemePaletteScopeManager` does: [colors] (`#RRGGBB` keys) for ordinary icons, [checkbox]
     * (`Checkbox.*` keys) for the check boxes and radio buttons, matched by element id.
     */
    private class IconPalette(val colors: Map<String, String>, val checkbox: Map<String, String>) {
      fun patch(path: String, svg: String): String =
        if (path.removePrefix("/").startsWith("laf/")) svg.patchById(checkbox)
        else
          colors.entries.fold(svg) { text, (from, to) -> text.replace(from, to, ignoreCase = true) }

      /**
       * `NewThemeCheckboxPatcher` with `PaletteKeys`: an `id` of two parts split at `_` gives the
       * fill key and the stroke key, any other id is both. Only attributes the element has change.
       */
      private fun String.patchById(palette: Map<String, String>): String =
        TAG_WITH_ID.replace(this) { tag ->
          val id = tag.groupValues[1]
          val keys = id.split('_')
          val (fillKey, strokeKey) = if (keys.size == 2) keys[0] to keys[1] else id to id
          tag.value
            .withAttribute("fill", palette[fillKey])
            .withAttribute("stroke", palette[strokeKey])
        }

      private fun String.withAttribute(name: String, color: String?): String =
        if (color == null) this else replace(Regex("""(\s$name=")[^"]*""""), "\$1$color\"")
    }

    private val COLORIZED = Regex("""colorize/([0-9A-Fa-f]{6})/(.+)""")
    private val PAINT = Regex("""\b(fill|stroke)="(?!none")[^"]+"""")

    private val TAG_WITH_ID = Regex("""<[^>]*\sid="([^"]+)"[^>]*>""")

    private val ICON_PALETTES =
      mapOf(
        ISLANDS_DARK.id to
          IconPalette(colors = mapOf("#CED0D6" to "#C3C5CB"), checkbox = emptyMap()),
        ISLANDS_LIGHT.id to
          IconPalette(
            colors = emptyMap(),
            checkbox =
              mapOf(
                "Checkbox.Background.Default" to "#FFFFFF",
                "Checkbox.Border.Default" to "#A8ADBD",
                "Checkbox.Foreground.Selected" to "#FFFFFF",
                "Checkbox.Background.Selected" to "#3574F0",
                "Checkbox.Border.Selected" to "#3574F0",
                "Checkbox.Focus.Wide" to "#3574F0",
                "Checkbox.Foreground.Disabled" to "#C9CCD6",
                "Checkbox.Background.Disabled" to "#F7F8FA",
                "Checkbox.Border.Disabled" to "#DFE1E5",
              ),
          ),
      )

    /**
     * Icons that aren't in JetBrains Client's jars under the path the UI asks for, read through
     * [extra]. `markdown/link.svg`, `link_dark.svg`: the Markdown plugin's chain link
     * (`icons/expui/editorActions/`, Apache 2.0, JetBrains), the icon Gateway shows for Link.
     * `laf/radio*.svg`, `laf/checkBox*.svg`: the New UI's radio buttons and check boxes
     * (`themes/expUI/icons/dark/` in intellij.platform.resources.jar, Apache 2.0, JetBrains), which
     * the Android app's icons jar doesn't carry; both themes draw these same files, recoloured by
     * id.
     */
    private val EXTRA_ICONS =
      mapOf(
        "markdown/link_dark.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="M7.09656 3.19658C8.67248 1.62069 11.2275 1.62067 12.8035 3.19653C14.3795 4.77245 14.3795 7.3276 12.8036 8.90359L11.8529 9.85434C11.6576 10.0496 11.341 10.0496 11.1458 9.85435C10.9505 9.6591 10.9505 9.34251 11.1458 9.14725L12.0965 8.1965C13.2819 7.01104 13.2819 5.08906 12.0964 3.90366C10.911 2.7183 8.98906 2.71832 7.80366 3.9037L6.85287 4.85446C6.65761 5.04972 6.34103 5.04972 6.14577 4.85445C5.95051 4.65919 5.95051 4.34261 6.14577 4.14735L7.09656 3.19658Z" fill="#CED0D6"/>
<path d="M4.85288 6.14731C5.04814 6.34258 5.04814 6.65916 4.85287 6.85442L3.90376 7.80351C2.71827 8.98898 2.71828 10.911 3.90378 12.0965C5.08926 13.2819 7.01126 13.2819 8.19669 12.0964L9.14661 11.1465C9.34187 10.9512 9.65846 10.9512 9.85372 11.1465C10.049 11.3417 10.049 11.6583 9.85373 11.8536L8.90381 12.8035C7.32786 14.3795 4.77269 14.3796 3.19669 12.8036C1.62065 11.2276 1.62063 8.67239 3.19666 7.0964L4.14577 6.14731C4.34104 5.95205 4.65762 5.95205 4.85288 6.14731Z" fill="#CED0D6"/>
<path d="M10.3536 6.35355C10.5488 6.15829 10.5488 5.84171 10.3536 5.64645C10.1583 5.45118 9.84171 5.45118 9.64645 5.64645L5.64645 9.64645C5.45118 9.84171 5.45118 10.1583 5.64645 10.3536C5.84171 10.5488 6.15829 10.5488 6.35355 10.3536L10.3536 6.35355Z" fill="#CED0D6"/>
</svg>""",
        "markdown/link.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="M7.09656 3.19658C8.67248 1.62069 11.2275 1.62067 12.8035 3.19653C14.3795 4.77245 14.3795 7.3276 12.8036 8.90359L11.8529 9.85434C11.6576 10.0496 11.341 10.0496 11.1458 9.85435C10.9505 9.6591 10.9505 9.34251 11.1458 9.14725L12.0965 8.1965C13.2819 7.01104 13.2819 5.08906 12.0964 3.90366C10.911 2.7183 8.98906 2.71832 7.80366 3.9037L6.85287 4.85446C6.65761 5.04972 6.34103 5.04972 6.14577 4.85445C5.95051 4.65919 5.95051 4.34261 6.14577 4.14735L7.09656 3.19658Z" fill="#6C707E"/>
<path d="M4.85288 6.14731C5.04814 6.34258 5.04814 6.65916 4.85287 6.85442L3.90376 7.80351C2.71827 8.98898 2.71828 10.911 3.90378 12.0965C5.08926 13.2819 7.01126 13.2819 8.19669 12.0964L9.14661 11.1465C9.34187 10.9512 9.65846 10.9512 9.85372 11.1465C10.049 11.3417 10.049 11.6583 9.85373 11.8536L8.90381 12.8035C7.32786 14.3795 4.77269 14.3796 3.19669 12.8036C1.62065 11.2276 1.62063 8.67239 3.19666 7.0964L4.14577 6.14731C4.34104 5.95205 4.65762 5.95205 4.85288 6.14731Z" fill="#6C707E"/>
<path d="M10.3536 6.35355C10.5488 6.15829 10.5488 5.84171 10.3536 5.64645C10.1583 5.45118 9.84171 5.45118 9.64645 5.64645L5.64645 9.64645C5.45118 9.84171 5.45118 10.1583 5.64645 10.3536C5.84171 10.5488 6.15829 10.5488 6.35355 10.3536L10.3536 6.35355Z" fill="#6C707E"/>
</svg>""",
        // IntelliJ has no phone icon: a phone outline drawn like the platform's 16 px icons, in
        // their colours (light #6C707E, dark #CED0D6).
        "ideate/phone_dark.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect x="4.5" y="1.5" width="7" height="13" rx="1.5" stroke="#CED0D6"/>
<path d="M7 12.5H9" stroke="#CED0D6" stroke-linecap="round"/>
</svg>""",
        "ideate/phone.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect x="4.5" y="1.5" width="7" height="13" rx="1.5" stroke="#6C707E"/>
<path d="M7 12.5H9" stroke="#6C707E" stroke-linecap="round"/>
</svg>""",
        "laf/radio.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Default_Checkbox.Border.Default" cx="12" cy="12" r="8" fill="#2B2D30" stroke="#5A5D63"/>
</svg>""",
        "laf/radioSelected.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Selected_Checkbox.Border.Selected" cx="12" cy="12" r="8" fill="#3574F0" stroke="#3574F0"/>
<path id="Checkbox.Foreground.Selected" fill-rule="evenodd" clip-rule="evenodd" d="M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z" fill="white"/>
</svg>""",
        "laf/radioFocused.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Default_Checkbox.Border.Selected" cx="12" cy="12" r="8.5" fill="#2B2D30" stroke="#3574F0" stroke-width="2"/>
</svg>""",
        "laf/radioSelectedFocused.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Selected_Checkbox.Border.Selected" cx="12" cy="12" r="8" fill="#3574F0" stroke="#3574F0"/>
<circle id="Checkbox.Focus.Wide" cx="12" cy="12" r="11" stroke="#3574F0" stroke-width="2"/>
<path id="Checkbox.Foreground.Selected" fill-rule="evenodd" clip-rule="evenodd" d="M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z" fill="white"/>
</svg>""",
        "laf/radioDisabled.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Disabled_Checkbox.Border.Disabled" cx="12" cy="12" r="8" fill="#393B40" stroke="#5A5D63"/>
</svg>""",
        "laf/radioSelectedDisabled.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<circle id="Checkbox.Background.Disabled_Checkbox.Border.Disabled" cx="12" cy="12" r="8" fill="#393B40" stroke="#5A5D63"/>
<path id="Checkbox.Foreground.Disabled" fill-rule="evenodd" clip-rule="evenodd" d="M12 15C13.6569 15 15 13.6569 15 12C15 10.3431 13.6569 9 12 9C10.3431 9 9 10.3431 9 12C9 13.6569 10.3431 15 12 15Z" fill="#6F737A"/>
</svg>""",
        "laf/checkBox.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Default_Checkbox.Border.Default" x="4.5" y="4.5" width="15" height="15" rx="2.5" fill="#2B2D30" stroke="#5A5D63"/>
</svg>""",
        "laf/checkBoxSelected.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Selected_Checkbox.Border.Selected" x="4.5" y="4.5" width="15" height="15" rx="2.5" fill="#3574F0" stroke="#3574F0"/>
<path id="Checkbox.Foreground.Selected" d="M8 12.5L11 15.5L16.5 9" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>""",
        "laf/checkBoxFocused.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Default_Checkbox.Border.Selected" x="4" y="4" width="16" height="16" rx="3" fill="#2B2D30" stroke="#3574F0" stroke-width="2"/>
</svg>""",
        "laf/checkBoxSelectedFocused.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Selected_Checkbox.Border.Selected" x="4.5" y="4.5" width="15" height="15" rx="2.5" fill="#3574F0" stroke="#3574F0"/>
<rect id="Checkbox.Focus.Wide" x="2" y="2" width="20" height="20" rx="4.5" stroke="#3574F0" stroke-width="2"/>
<path id="Checkbox.Foreground.Selected" d="M8 12.5L11 15.5L16.5 9" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>""",
        "laf/checkBoxDisabled.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Disabled_Checkbox.Border.Disabled" x="4.5" y="4.5" width="15" height="15" rx="2.5" fill="#393B40" stroke="#5A5D63"/>
</svg>""",
        "laf/checkBoxSelectedDisabled.svg" to
          """<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect id="Checkbox.Background.Disabled_Checkbox.Border.Disabled" x="4.5" y="4.5" width="15" height="15" rx="2.5" fill="#393B40" stroke="#5A5D63"/>
<path id="Checkbox.Foreground.Disabled" d="M8 12.5L11 15.5L16.5 9" stroke="#6F737A" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
</svg>""",
      )
  }
}
