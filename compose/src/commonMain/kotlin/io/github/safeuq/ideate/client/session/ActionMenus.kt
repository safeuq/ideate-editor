package io.github.safeuq.ideate.client.session

import com.jetbrains.rd.ide.model.ActionBaseModel
import com.jetbrains.rd.ide.model.ActionModelWithId
import com.jetbrains.rd.ide.model.ActionRegistrationModel
import com.jetbrains.rd.ide.model.ActionStubModel
import com.jetbrains.rd.ide.model.ActionTimestampSetModel
import com.jetbrains.rd.ide.model.ActionUiKindModel
import com.jetbrains.rd.ide.model.AsyncActionGroupExpandResponse
import com.jetbrains.rd.ide.model.AsyncActionUpdateRequest
import com.jetbrains.rd.ide.model.AsyncActionsModel
import com.jetbrains.rd.ide.model.GroupModel
import com.jetbrains.rd.ide.model.IconModel
import com.jetbrains.rd.ide.model.InputEventModel
import com.jetbrains.rd.ide.model.KeyStrokeModel
import com.jetbrains.rd.ide.model.KeyboardShortcutModel
import com.jetbrains.rd.ide.model.NameBooleanValue
import com.jetbrains.rd.ide.model.PopupKindModel
import com.jetbrains.rd.ide.model.PresentationModel
import com.jetbrains.rd.ide.model.RdCallActionRequest
import com.jetbrains.rd.ide.model.RdPatch
import com.jetbrains.rd.ide.model.RdRequest
import com.jetbrains.rd.ide.model.RdResponse
import com.jetbrains.rd.ide.model.RdSuccessResponse
import com.jetbrains.rd.ide.model.SeparatorModel
import com.jetbrains.rd.ide.model.ToggleActionModel
import com.jetbrains.rd.util.lifetime.Lifetime
import io.github.safeuq.ideate.client.ui.IS_MAC
import io.github.safeuq.ideate.client.ui.MenuEntry
import io.github.safeuq.ideate.client.ui.MenuItem
import io.github.safeuq.ideate.client.ui.MenuSeparator
import io.github.safeuq.ideate.client.ui.SubMenu

/**
 * Context menus the backend builds, as JetBrains Client gets them (docs/action-protocol.md): the
 * client names an action group, e.g. `ProjectViewPopupMenu`, and the context as timestamps (the
 * selected Project view node, or the editor and its caret). The backend runs each action's update
 * in that context and answers with the visible items, their text and whether they are enabled. A
 * chosen item goes back as `RdCallActionRequest` and runs on the backend.
 *
 * Touched only on the rd scheduler thread, except the items' click handlers, which [onRd] moves
 * there.
 */
internal class ActionMenus(
  private val lifetime: Lifetime,
  private val model: AsyncActionsModel,
  private val sendRequest: (RdRequest) -> Long,
  private val onRd: (() -> Unit) -> Unit,
  private val iconPath: (IconModel) -> String?,
  private val log: (String) -> Unit,
  /** The finished menu, for the menu opened as number [serial]. */
  private val publish: (serial: Int, entries: List<MenuEntry>) -> Unit,
  /**
   * The document changes an action made (Comment with Line Comment, Reformat Code): they come only
   * in the success response's patch, not on `backendPatches` (seen 2026-11-17), so the edit
   * sessions apply them.
   */
  private val applyPatch: (RdPatch) -> Unit,
) {
  /** Requests of chosen items, to log the backend's answer and then call [Running.done]. */
  private val running = mutableMapOf<Long, Running>()

  private data class Running(val actionId: String, val done: () -> Unit)

  /**
   * Asks for [groupId]'s items in [place] and publishes them as menu number [serial]. The backend
   * fills a submenu (or an inline group of one) only when asked for that group, as JetBrains Client
   * asks when the submenu opens; Ideate's menu needs them up front, so each visible submenu is
   * asked for too, and the menu is published when the last answer is in.
   */
  fun show(serial: Int, groupId: String, place: String, timestamps: ActionTimestampSetModel) =
    expand(groupId, place, timestamps) { publish(serial, it) }

  /**
   * [groupId]'s items in [place], every visible submenu filled, given to [result] when the last
   * answer is in. [uiKind] goes into every request (the main menu's is `PopupKindModel(true,
   * false)`). [onItem]: when set, a chosen item calls it with its action id instead of running the
   * presentation the menu showed (the main menu runs it with the context at the click).
   */
  fun expand(
    groupId: String,
    place: String,
    timestamps: ActionTimestampSetModel,
    uiKind: ActionUiKindModel = PopupKindModel(false, false),
    maxRequests: Int = MAX_REQUESTS,
    onItem: ((actionId: String) -> Unit)? = null,
    result: (List<MenuEntry>) -> Unit,
  ) {
    val menu =
      MenuTree(place, timestamps, iconPath, log) { actionId, presentation ->
        if (onItem != null) onItem(actionId)
        else onRd { send(actionId, presentation, place, timestamps) }
      }
    val started = System.nanoTime()
    val requested = mutableSetOf<String>()
    var pending = 0
    fun expand(id: String) {
      if (!requested.add(id)) return
      pending++
      val request = AsyncActionUpdateRequest(id, timestamps, place, uiKind, null)
      model.expandGroup.start(lifetime, request).result.advise(lifetime) { answer ->
        pending--
        runCatching { answer.unwrap() }
          .onFailure { failure -> log("Menu $id not built: ${failure.message}") }
          .getOrNull()
          ?.let { response ->
            menu.add(response)
            val submenus =
              response.children
                .flatMap { it.children }
                .filterIsInstance<GroupModel>()
                .filter { group ->
                  val presentation = menu.presentationOf(group)
                  // An inline group the answer left unfilled is asked for too: the backend sent
                  // Build's `Java.BuildMenu` (Build Project, Rebuild, …) without its children.
                  presentation.isVisible && group.actionId !in menu.children
                }
            if (requested.size < maxRequests) submenus.forEach { expand(it.actionId) }
          }
        if (pending == 0) {
          val milliseconds = (System.nanoTime() - started) / 1_000_000
          log("Menu $groupId: ${requested.size} requests, $milliseconds ms")
          // Debug aid: CLIENT_MENU_DUMP=1 logs each item's action id, text and flags.
          if (System.getenv("CLIENT_MENU_DUMP") != null) menu.dump()
          result(tidy(menu.entries(groupId.removePrefix(COMPOSITE), inUnshownGroup = false)))
        }
      }
    }
    expand(groupId)
  }

  /** The backend's answer to a chosen item; false when it isn't one. */
  fun response(response: RdResponse): Boolean {
    val (actionId, done) = running.remove(response.requestId) ?: return false
    if (response is RdSuccessResponse) {
      log("$actionId ran")
      response.patch?.takeIf { it.changes.isNotEmpty() }?.let(applyPatch)
    } else log("$actionId did not run: $response")
    done()
    return true
  }

  /**
   * Runs [actionId] in [place] and the context [timestamps] without a menu, as a shortcut does: the
   * backend's `update` gives the presentation for that context, and the action runs only when it is
   * enabled there (e.g. `GotoDeclaration` with the caret on a reference). [disabled] is called
   * otherwise. [done] is called when the backend has answered the run.
   */
  fun runAction(
    actionId: String,
    place: String,
    timestamps: ActionTimestampSetModel,
    inputEvent: InputEventModel? = null,
    done: () -> Unit = {},
    disabled: () -> Unit = {},
  ) {
    val request =
      AsyncActionUpdateRequest(actionId, timestamps, place, PopupKindModel(false, false), null)
    model.update.start(lifetime, request).result.advise(lifetime) { result ->
      val presentation =
        runCatching { result.unwrap() }
          .onFailure { failure -> log("$actionId not updated: ${failure.message}") }
          .getOrNull() ?: return@advise
      if (presentation.isEnabled) send(actionId, presentation, place, timestamps, done, inputEvent)
      else {
        log("$actionId is disabled here")
        disabled()
      }
    }
  }

  private fun send(
    actionId: String,
    presentation: PresentationModel,
    place: String,
    timestamps: ActionTimestampSetModel,
    done: () -> Unit = {},
    inputEvent: InputEventModel? = null,
  ) {
    // The backend runs only an action whose presentation, as sent back, is enabled: the one the
    // menu showed, or the one `update` returned.
    val requestId =
      sendRequest(
        RdCallActionRequest(timestamps, actionId, place, presentation, inputEvent, false, null),
      )
    running[requestId] = Running(actionId, done)
    log("Running $actionId")
  }

  internal companion object {
    /**
     * A group id that asks as JetBrains Client does, e.g. `composite:RunConfigurationsActionGroup`:
     * the answer's children are listed under the plain id, `RunConfigurationsActionGroup`.
     */
    const val COMPOSITE = "composite:"

    /** Bounds the submenu requests for one menu. */
    const val MAX_REQUESTS = 40

    /** Groups whose items open what Ideate doesn't show yet. */
    val UNSHOWN_GROUPS =
      setOf(
        // The editor's menu: Diagrams open popups, Analyze fills tool windows, Run and Debug run
        // the program with its output in a backend tool window, and Folding changes folds Ideate
        // doesn't draw yet. Go To's navigation runs (ClientSession follows the backend's editor
        // selection); its items that open a chooser popup are in UNSHOWN_ACTIONS.
        "UML.EditorGroup",
        "AnalyzeMenu",
        "RunContextPopupGroup",
        "RunContextGroupMore",
        "FoldingGroup",
      )

    /**
     * Actions that open a popup window, a frame or a tool window, which Ideate doesn't show yet
     * (dialogs it does). Seen with CLIENT_MENU_DUMP on 263.7307.200: Find Usages and Recent Changes
     * fill a tool window or popup, Find/Replace in Files is a popup in the New UI, Show History and
     * the Compare actions open frames, Show Context Actions is the Alt+Enter popup.
     */
    val UNSHOWN_ACTIONS =
      setOf(
        "FindUsages",
        "FindInPath",
        "ReplaceInPath",
        "DownloadBackendFileToClient",
        "RecentChanges",
        "LocalHistory.ShowHistory",
        "LocalHistory.ShowSelectionHistory",
        "LocalHistory.ShowProjectHistory",
        "CompareTwoFiles",
        "CompareFileWithEditor",
        "CompareClipboardWithSelection",
        "ShowIntentionActions",
        // Go To items that open a chooser popup (Related Symbol, Test), a data or DDL editor, the
        // Database tool window or the grid's Row dialog (LangActions.xml, DatabaseTools, grid).
        "GotoRelated",
        "GotoTest",
        "Jdbc.OpenEditor.Data",
        "Jdbc.OpenEditor.DDL",
        "GotoRow",
        "sql.SelectInDatabaseView",
        // Main menu (2026-11-26, 263.7307.200): hint popups the backend draws itself, which reach
        // the client neither as a Lux window nor as an activePopups list (Quick Definition logged
        // "not shown inside: BeDynamicControlContainer"; the others ran with nothing to see), and
        // the Hierarchy and Bytecode tool windows, which Ideate doesn't draw.
        "QuickImplementations",
        "QuickTypeDefinition",
        "ShowSiblings",
        "ParameterInfo",
        "ExpressionTypeInfo",
        "EditorContextInfo",
        "BytecodeViewer",
        "TypeHierarchy",
        "MethodHierarchy",
        "CallHierarchy",
      )

    /**
     * Without the mnemonic mark: `Rena_me...` is `Rename...`; `__` is a real underscore, and `&&` a
     * real `&` (`Emoji && Symbols` in Edit).
     */
    fun label(presentation: PresentationModel): String =
      presentation.text
        .orEmpty()
        .replace("__", "\u0000")
        .replace("_", "")
        .replace("\u0000", "_")
        .replace("&&", "&")

    /** No separator first, last or twice in a row. */
    fun tidy(entries: List<MenuEntry>): List<MenuEntry> {
      val result = mutableListOf<MenuEntry>()
      for (entry in entries) {
        if (entry == MenuSeparator && (result.isEmpty() || result.last() == MenuSeparator)) continue
        result += entry
      }
      if (result.lastOrNull() == MenuSeparator) result.removeAt(result.lastIndex)
      return result
    }

    /** The first shortcut of IntelliJ's macOS keymap on a Mac, of the default keymap elsewhere. */
    fun shortcutOf(action: ActionBaseModel): String? {
      val shortcut = firstShortcut(action) ?: return null
      val first = KeyStrokes.text(shortcut.firstKeyStroke, IS_MAC) ?: return null
      val second = shortcut.secondKeyStroke ?: return first
      return KeyStrokes.text(second, IS_MAC)?.let { "$first, $it" }
    }

    /**
     * The key stroke of [shortcutOf]'s shortcut, for a menu bar accelerator: null when that
     * shortcut has a second stroke, or is Enter (`MacNativeActionMenuItem.isEnterKeyStroke`).
     */
    fun keyStrokeOf(action: ActionBaseModel): KeyStrokeModel? {
      val shortcut = firstShortcut(action) ?: return null
      if (shortcut.secondKeyStroke != null) return null
      return shortcut.firstKeyStroke.takeIf { it.keyCode != ENTER }
    }

    private fun firstShortcut(action: ActionBaseModel): KeyboardShortcutModel? {
      val keymaps = action.keymapShortcutsList
      val keymap =
        if (IS_MAC) keymaps.firstOrNull { it.keymapName in MAC_KEYMAPS }
        else keymaps.firstOrNull { it.keymapName == DEFAULT_KEYMAP }
      return keymap?.shortcuts?.filterIsInstance<KeyboardShortcutModel>()?.firstOrNull()
    }

    private const val ENTER = 0x0A

    const val DEFAULT_KEYMAP = "\$default"
    val MAC_KEYMAPS = setOf("macOS", "Mac OS X 10.5+")
  }
}

/**
 * One menu's answers as they come in: the presentations by action id and each group's children,
 * turned into [MenuEntry]s. [onItem] runs a chosen item (or a performed group) with the
 * presentation the menu showed.
 */
internal class MenuTree(
  val place: String,
  val timestamps: ActionTimestampSetModel,
  private val iconPath: (IconModel) -> String?,
  private val log: (String) -> Unit,
  private val onItem: (actionId: String, presentation: PresentationModel) -> Unit,
) {
  val presentations = mutableMapOf<String, PresentationModel>()
  val children = mutableMapOf<String, List<ActionRegistrationModel>>()

  fun add(response: AsyncActionGroupExpandResponse) {
    response.presentations.forEach { presentations[it.id] = it.presentation }
    response.children.forEach { children[it.id] = it.children }
  }

  /** The state the backend computed in this context; the registered one when it sent none. */
  fun presentationOf(action: ActionBaseModel) = presentationOf(action, presentations)

  fun dump() {
    for ((group, items) in children) {
      for (item in items.filterIsInstance<ActionBaseModel>()) {
        val presentation = presentationOf(item)
        log(
          "  $group > ${item.actionId} '${presentation.text}' flags=0x${presentation.flags.toString(16)}",
        )
      }
    }
  }

  fun entries(groupId: String, inUnshownGroup: Boolean): List<MenuEntry> =
    children[groupId].orEmpty().flatMap { child -> entry(child, inUnshownGroup) }

  private fun entry(child: ActionRegistrationModel, inUnshownGroup: Boolean): List<MenuEntry> =
    when (child) {
      is SeparatorModel -> listOf(MenuSeparator)
      // Blocked for clients, or an action Ideate has no part of.
      is ActionStubModel -> emptyList()
      is GroupModel -> {
        val presentation = presentationOf(child)
        val unshown = inUnshownGroup || child.actionId in ActionMenus.UNSHOWN_GROUPS
        when {
          !presentation.isVisible -> emptyList()
          // A group that can be performed is chosen like an item, e.g. a run configuration's row
          // in the run widget's popup; its submenu (Run with Coverage…) is the inline part.
          presentation.isPerformGroup ->
            listOf(
              MenuItem(
                label = ActionMenus.label(presentation),
                enabled = presentation.isEnabled && !unshown,
                iconPath = presentation.icon?.icon?.let(iconPath),
                id = child.actionId,
                onClick = { onItem(child.actionId, presentation) },
              ),
            )
          // A group that isn't a submenu shows its items in place.
          !presentation.isPopup -> entries(child.actionId, unshown)
          else -> {
            val items = ActionMenus.tidy(entries(child.actionId, unshown))
            listOf(
              SubMenu(
                ActionMenus.label(presentation),
                items,
                presentation.isEnabled && items.isNotEmpty(),
                presentation.icon?.icon?.let(iconPath),
                child.actionId,
              ),
            )
          }
        }
      }
      is ActionBaseModel -> {
        val presentation = presentationOf(child)
        if (!presentation.isVisible) emptyList()
        else {
          val text = ActionMenus.label(presentation)
          // Backend dialogs show (LuxDialogs); popups, frames and tool windows don't yet, so
          // the items that open those stay greyed out.
          val unshown = inUnshownGroup || child.actionId in ActionMenus.UNSHOWN_ACTIONS
          listOf(
            MenuItem(
              label = text,
              shortcut = ActionMenus.shortcutOf(child),
              checked = checkedOf(child, presentation),
              keyStroke = ActionMenus.keyStrokeOf(child),
              enabled = presentation.isEnabled && !unshown,
              iconPath = presentation.icon?.icon?.let(iconPath),
              id = child.actionId,
              onClick = { onItem(child.actionId, presentation) },
            ),
          )
        }
      }
      // ActionReferenceModel: the group's own lists name the action; nothing to show alone.
      is ActionModelWithId -> {
        log("Menu item ${child.actionId} (${child::class.simpleName}) not shown")
        emptyList()
      }
      else -> emptyList()
    }

  /** A toggle's state (`Toggleable`): the backend puts it in the presentation as `selected`. */
  private fun checkedOf(action: ActionBaseModel, presentation: PresentationModel): Boolean? =
    if (action !is ToggleActionModel) null
    else
      presentation.clientProperties
        .orEmpty()
        .filterIsInstance<NameBooleanValue>()
        .firstOrNull { it.name == SELECTED }
        ?.value ?: false

  private companion object {
    const val SELECTED = "selected"
  }
}

// PresentationModel.flags: IntelliJ's `Presentation.myFlags` as they are (ActionsKt in
// intellij.rd.platform.jar), so the bits are Presentation's IS_ENABLED, IS_VISIBLE, IS_POPUP_GROUP
// and IS_PERFORM_GROUP. A run configuration's row in the run widget's popup is 0x8b3: a popup group
// that is also performed when chosen.
private const val ENABLED = 0x1
private const val VISIBLE = 0x2
private const val POPUP_GROUP = 0x10
private const val PERFORM_GROUP = 0x20

internal val PresentationModel.isEnabled
  get() = flags and ENABLED != 0

internal val PresentationModel.isVisible
  get() = flags and VISIBLE != 0

internal val PresentationModel.isPopup
  get() = flags and POPUP_GROUP != 0

private val PresentationModel.isPerformGroup
  get() = flags and PERFORM_GROUP != 0

/**
 * [action]'s state in the context the backend answered for: [updated] by action id, merged over the
 * action's registered (template) presentation. The update leaves out (null) what is the same as the
 * template, e.g. Cut's text. The registered presentation when the backend sent none.
 */
internal fun presentationOf(
  action: ActionBaseModel,
  updated: Map<String, PresentationModel>,
): PresentationModel {
  val template = action.presentation
  val presentation = updated[action.actionId] ?: return template
  return PresentationModel(
    presentation.flags,
    presentation.text ?: template.text,
    presentation.icon ?: template.icon,
    presentation.description ?: template.description,
    presentation.clientProperties,
    presentation.shouldCache,
  )
}

/**
 * A key stroke as IntelliJ's menus show it (`KeymapUtil.getKeystrokeText`): `⇧⌘C` on a Mac,
 * `Ctrl+Shift+C` elsewhere. Codes are Java AWT's (`KeyEvent.VK_*`, `InputEvent.*_DOWN_MASK`).
 */
internal object KeyStrokes {
  fun text(stroke: KeyStrokeModel, mac: Boolean): String? {
    val key = keyName(stroke.keyCode, mac) ?: return null
    val modifiers = stroke.modifiers
    fun has(newMask: Int, oldMask: Int) = modifiers and (newMask or oldMask) != 0
    val shift = has(0x40, 0x1)
    val control = has(0x80, 0x2)
    val meta = has(0x100, 0x4)
    val alt = has(0x200, 0x8)
    return if (mac) {
      buildString {
        if (control) append('⌃')
        if (alt) append('⌥')
        if (shift) append('⇧')
        if (meta) append('⌘')
        append(key)
      }
    } else {
      listOfNotNull(
          "Ctrl".takeIf { control },
          "Alt".takeIf { alt },
          "Shift".takeIf { shift },
          "Meta".takeIf { meta },
          key,
        )
        .joinToString("+")
    }
  }

  private fun keyName(code: Int, mac: Boolean): String? =
    when (code) {
      in 0x30..0x39,
      in 0x41..0x5A -> code.toChar().toString()
      in 0x70..0x7B -> "F${code - 0x6F}"
      0x08 -> if (mac) "⌫" else "Backspace"
      0x7F -> if (mac) "⌦" else "Delete"
      0x0A -> if (mac) "↩" else "Enter"
      0x1B -> if (mac) "⎋" else "Escape"
      0x09 -> if (mac) "⇥" else "Tab"
      0x20 -> "Space"
      0x25 -> if (mac) "←" else "Left"
      0x26 -> if (mac) "↑" else "Up"
      0x27 -> if (mac) "→" else "Right"
      0x28 -> if (mac) "↓" else "Down"
      0x24 -> if (mac) "↖" else "Home"
      0x23 -> if (mac) "↘" else "End"
      0x21 -> if (mac) "⇞" else "Page Up"
      0x22 -> if (mac) "⇟" else "Page Down"
      0x9B -> "Insert"
      0x2C -> ","
      0x2E -> "."
      0x2F -> "/"
      0x3B -> ";"
      0x3D -> "="
      0x2D -> "-"
      0x5B -> "["
      0x5C -> "\\"
      0x5D -> "]"
      0xC0 -> "`"
      0xDE -> "'"
      else -> null
    }
}
