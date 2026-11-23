package io.github.safeuq.ideate.client.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * IntelliJ UI keys (`UIManager` names such as `Panel.background`) resolved as Islands Dark resolves
 * them, for colors the backend sends by name (Lux dialogs, docs/lux-protocol.md). Lookup order, as
 * IntelliJ builds its defaults:
 * 1. a key Islands Dark sets itself ([explicit]), e.g. `Button.default.startBackground` =
 *    control-brand-bg;
 * 2. Islands Dark's `ui."*"` wildcard, which rewrites every key ending in that suffix ([wildcard]),
 *    e.g. `Panel.background` → `*.background` = tool-window-bg;
 * 3. else null: the caller falls back to the color the backend sent with the name.
 *
 * Step 2 assumes the key exists in the look-and-feel defaults, as the standard Swing keys do; a key
 * that doesn't would take its fallback in IntelliJ instead. Values resolved from
 * ManyIslandsDark.theme.json, expUI_dark.theme.json and darcula.theme.json (263.7307.200).
 */
object ThemeKeys {
  fun color(key: String): Color? =
    explicit[key] ?: wildcard.entries.firstOrNull { key.endsWith("." + it.key) }?.value

  private val explicit =
    mapOf(
      "Button.startBackground" to Color(0x00191A1C), // transparent
      "Button.endBackground" to Color(0x00191A1C),
      "Button.shadowColor" to Color(0x00191A1C),
      "Button.startBorderColor" to IslandsDark.controlBorder,
      "Button.endBorderColor" to IslandsDark.controlBorder,
      "Button.focusedBorderColor" to IslandsDark.islandBackground, // control-bg
      "Button.default.startBackground" to IslandsDark.controlBrandBackground,
      "Button.default.endBackground" to IslandsDark.controlBrandBackground,
      "Button.default.startBorderColor" to IslandsDark.controlBrandBackground,
      "Button.default.endBorderColor" to IslandsDark.controlBrandBackground,
      "Button.default.shadowColor" to Color(0x00191A1C),
      "Button.default.foreground" to IslandsDark.textOverAccent,
      "Button.default.focusedBorderColor" to IslandsDark.islandBackground, // dialog-bg
      "Component.borderColor" to IslandsDark.controlBorder,
      "Component.focusedBorderColor" to IslandsDark.islandBackground, // dialog-bg
      "TextField.background" to IslandsDark.islandBackground, // control-bg
      "ComboBox.background" to IslandsDark.popupBackground,
      "ComboBox.nonEditableBackground" to IslandsDark.popupBackground, // control-bg-raised
      "ComboBox.ArrowButton.nonEditableBackground" to IslandsDark.popupBackground,
      "ComboBox.ArrowButton.background" to IslandsDark.islandBackground, // control-bg
      "Separator.separatorColor" to IslandsDark.popupBorder,
      // The hint under a field with a problem, e.g. New ▸ File's "already exists" (ManyIslandsDark:
      // feedback-error-bg = red-40, feedback-error-border = red-60, warnings yellow-40 /
      // yellow-60).
      // The welcome project's Open / New / Clone Repository buttons (darcula.theme.json, which
      // Islands Dark inherits: #FFFFFF12, hover #FFFFFF0D, pressed #FFFFFF1A).
      "DisclosureButton.defaultBackground" to Color(0x12FFFFFF),
      "DisclosureButton.hoverOverlay" to Color(0x0DFFFFFF),
      "DisclosureButton.pressedOverlay" to Color(0x1AFFFFFF),
      // ManyIslandsDark: Borders.color = dialog-border = layer-0-border = gray-30.
      "Borders.color" to Color(0xFF26282C),
      "ValidationTooltip.errorBackground" to Color(0xFF56272B),
      "ValidationTooltip.errorBackgroundColor" to Color(0xFF56272B),
      "ValidationTooltip.errorBorderColor" to Color(0xFF80383E),
      "ValidationTooltip.warningBackground" to Color(0xFF44321D),
      "ValidationTooltip.warningBackgroundColor" to Color(0xFF44321D),
      "ValidationTooltip.warningBorderColor" to Color(0xFF694820),
      // Darcula's AWT system color; Islands Dark doesn't set it. Lux sends it as the clear color.
      "control" to Color(0xFF3C3F41),
    )

  /** Islands Dark's `ui."*"`, resolved. */
  private val wildcard =
    linkedMapOf(
      "selectionInactiveForeground" to IslandsDark.text,
      "selectionInactiveBackground" to IslandsDark.selectionInactiveBackground,
      "lightSelectionBackground" to Color(0xFF233558), // selection-bg-active-muted
      "selectionBackground" to IslandsDark.selectionBackground,
      "selectionForeground" to IslandsDark.text,
      "disabledBorderColor" to IslandsDark.popupBorder, // control-border-disabled = gray-40
      "disabledForeground" to IslandsDark.textDisabled,
      "disabledBackground" to IslandsDark.islandBackground, // dialog-bg
      "inactiveForeground" to IslandsDark.textDisabled,
      "inactiveBackground" to IslandsDark.islandBackground, // dialog-bg
      "disabledText" to IslandsDark.textDisabled,
      "infoForeground" to IslandsDark.textSecondary,
      "acceleratorForeground" to IslandsDark.textSecondary,
      "shortcutForeground" to IslandsDark.textSecondary,
      "separatorForeground" to IslandsDark.textSecondary,
      "separatorColor" to IslandsDark.popupBorder,
      "focusColor" to IslandsDark.focusBorder,
      "borderColor" to IslandsDark.toolWindowBorder,
      "hoverBackground" to Color(0x17FFFFFF), // toolbar-bg-hovered
      "underlineColor" to IslandsDark.controlBrandBackground,
      "modifiedItemForeground" to IslandsDark.link,
      "foreground" to IslandsDark.text,
      "background" to IslandsDark.islandBackground, // tool-window-bg = layer-0-bg
    )
}
