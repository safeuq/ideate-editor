package intellijandroid.client.ui

import java.io.File
import java.util.zip.ZipFile

/**
 * IntelliJ's own icons from `intellij.platform.icons.jar`, picked like a dark theme does:
 * `<name>_dark.svg` first, then `<name>.svg`; then the Islands Dark palette swap `#CED0D6` →
 * `#C3C5CB`. Each platform turns the SVG text into a painter (desktop: Skia; Android: AndroidSVG).
 */
class IconJar(file: File) {
  private val jar = ZipFile(file.also { check(it.isFile) { "Missing icons jar $it" } })

  /**
   * The SVG text for [path], e.g. `expui/nodes/folder.svg`, or null when the jar has no such icon.
   */
  fun svg(path: String): String? {
    val name = path.removePrefix("/")
    val entry =
      jar.getEntry(name.removeSuffix(".svg") + "_dark.svg") ?: jar.getEntry(name) ?: return null
    return jar
      .getInputStream(entry)
      .use { it.readBytes().decodeToString() }
      .replace("#CED0D6", "#C3C5CB", ignoreCase = true)
  }

  companion object {
    /**
     * Icons that aren't in JetBrains Client's jars under the path the UI asks for.
     * `markdown/link.svg`: the Markdown plugin's chain link
     * (`icons/expui/editorActions/link_dark.svg`, Apache 2.0, JetBrains), the icon Gateway shows
     * for Link. `laf/radio*.svg`, `laf/checkBox*.svg`: the New UI's radio buttons and check boxes
     * (`themes/expUI/icons/dark/` in intellij.platform.resources.jar, Apache 2.0, JetBrains), which
     * the Android app's icons jar doesn't carry.
     */
    val EXTRA_ICONS =
      mapOf(
        "markdown/link.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<path d="M7.09656 3.19658C8.67248 1.62069 11.2275 1.62067 12.8035 3.19653C14.3795 4.77245 14.3795 7.3276 12.8036 8.90359L11.8529 9.85434C11.6576 10.0496 11.341 10.0496 11.1458 9.85435C10.9505 9.6591 10.9505 9.34251 11.1458 9.14725L12.0965 8.1965C13.2819 7.01104 13.2819 5.08906 12.0964 3.90366C10.911 2.7183 8.98906 2.71832 7.80366 3.9037L6.85287 4.85446C6.65761 5.04972 6.34103 5.04972 6.14577 4.85445C5.95051 4.65919 5.95051 4.34261 6.14577 4.14735L7.09656 3.19658Z" fill="#C3C5CB"/>
<path d="M4.85288 6.14731C5.04814 6.34258 5.04814 6.65916 4.85287 6.85442L3.90376 7.80351C2.71827 8.98898 2.71828 10.911 3.90378 12.0965C5.08926 13.2819 7.01126 13.2819 8.19669 12.0964L9.14661 11.1465C9.34187 10.9512 9.65846 10.9512 9.85372 11.1465C10.049 11.3417 10.049 11.6583 9.85373 11.8536L8.90381 12.8035C7.32786 14.3795 4.77269 14.3796 3.19669 12.8036C1.62065 11.2276 1.62063 8.67239 3.19666 7.0964L4.14577 6.14731C4.34104 5.95205 4.65762 5.95205 4.85288 6.14731Z" fill="#C3C5CB"/>
<path d="M10.3536 6.35355C10.5488 6.15829 10.5488 5.84171 10.3536 5.64645C10.1583 5.45118 9.84171 5.45118 9.64645 5.64645L5.64645 9.64645C5.45118 9.84171 5.45118 10.1583 5.64645 10.3536C5.84171 10.5488 6.15829 10.5488 6.35355 10.3536L10.3536 6.35355Z" fill="#C3C5CB"/>
</svg>""",
        // IntelliJ has no phone icon: a phone outline drawn like the platform's 16 px icons.
        "ideate/phone.svg" to
          """<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
<rect x="4.5" y="1.5" width="7" height="13" rx="1.5" stroke="#C3C5CB"/>
<path d="M7 12.5H9" stroke="#C3C5CB" stroke-linecap="round"/>
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
