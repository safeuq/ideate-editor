# Feature parity (plan): undo, Cmd+Click, Search Everywhere, Find in Files, Find in the editor

Plan, 2026-11-18. Five things IntelliJ has and IDEate lacks or only half has. For each: how IntelliJ and
JetBrains Client 263.7307.200 do it, what IDEate has, the design, the steps and what is still unknown.

**Rule for every design here: look and behave like IntelliJ.** Same popup, same rows, same shortcuts, same
wording. Where a finger can't do what a mouse does, the touch form is listed as an addition, not a replacement.

Sources:

| Short name | Path |
|---|---|
| IC | `/Users/safeuq/Files/Libraries/intellij-community-263.7307.200` (open sources) |
| client jars | `~/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-…/IntelliJ IDEA.app/Contents/lib` (`intellij.rd.client.jar`, `intellij.rd.ide.model.generated.jar`), read with `javap -c -p` |

Tags: **[Seen code]** = read in the named source or class. **[Inferred]** = follows from the code but not read
or run. **[Open]** = not known yet; the step that settles it is named.

## Summary

| # | Feature | Where the work happens | Transport | New protocol work | Size |
|---|---|---|---|---|---|
| 1 | Undo / redo | backend undoes, IDEate applies the answer | rd `RdUndoRedoRequest` (already sent) | none | small |
| 2 | Cmd+Click | backend resolves and navigates | rd `HyperlinkHighlightingModel` + `RdCallActionRequest("GotoDeclaration")` | follow a backend selection change; the usages popup | medium |
| 3 | Find in the editor | IDEate, on its own copy of the text | none for Find; rd `RdApplyPatchRequest` for Replace | none | medium |
| 4 | Find in Files | backend searches, IDEate lists | Fleet RPC `FindInFilesApi` | serialize `FindModel`; file ids | medium |
| 5 | Search Everywhere | backend searches, IDEate lists | Fleet RPC `SeRemoteApi` | **the Rhizome database client (JetBrains' library)** | large |

Suggested order: 1, 3, 2, 4, 5. 1 and 3 need nothing new from the backend. 5 has the one real unknown, so it
starts with a spike.

---

## 1. Undo and redo

### How IntelliJ does it

- The backend keeps the undo stacks (`UndoManagerImpl`, IC `platform/undo`). Every edit request starts a command
  on the backend with the client's command id, name and group (`ForeignCommandProcessor`), so each typed word,
  paste or completion is one undo step there. [Seen code: `edit-protocol-backend.md` 1.4]
- JetBrains Client undoes on its own copy first (`SpeculativeUndoServiceImpl`, `FrontendUndoManager`), then
  sends `RdUndoRedoRequest(fileEditorId, fileIds, undoStackSizes, redoStackSizes, isUndo, undoProjectId)`.
  The sizes are the stack sizes after the undo. [Seen code: `edit-protocol-client.md` 1.5]
- The backend runs IntelliJ's own Undo and answers with the changes it made plus `RdStackSizeItem(fileId,
  undoSize, redoSize)` for the global stack and each file. It never refuses or asks for a retry.
  [Seen code: `edit-protocol-backend.md` 1.6b]
- Shortcuts in the default keymaps (action ids `$Undo`, `$Redo`):

  | | Undo | Redo |
  |---|---|---|
  | macOS | ⌘Z | ⇧⌘Z |
  | Windows / Linux (`$default`) | Ctrl+Z | Ctrl+Shift+Z |

- Menu: Edit ▸ Undo *name* / Redo *name* (e.g. "Undo Typing", "Undo Paste"). The editor's context menu
  (`EditorPopupMenu`) has no undo. A step that touches several files asks first ("Undo Rename?").

### What IDEate has

- `EditSession.undo(isUndo)` sends the request with the sizes expected after the undo, and applies the answer
  like any backend patch. `ClientSession.undo()` / `redo()` call it. [Seen code]
- `EditorView.onKey` binds only **Meta+Z** and Meta+Shift+Z. On Android and Linux, Ctrl+Z does nothing. On the
  emulator `adb`'s Ctrl+Z even reached Gboard as a plain `z`.
- No way to undo by touch. No enabled state. Not tested on a device.

### Design

1. **Keys.** The platform's shortcut modifier, as paste already does (`PASTE_MODIFIER` in `LuxDialogView`):
   ⌘ on a Mac, Ctrl elsewhere. Undo = modifier+Z, redo = modifier+Shift+Z. Ctrl+Y stays IntelliJ's "Delete
   Line", so it is not redo.
2. **Enabled state.** `EditSession` already tracks `undoSizes` and `redoSizes` from `RdStackSizeItem`. Publish
   `canUndo = file stack > 0` and `canRedo` in the editor state.
3. **Touch.** Three places, all greyed out when the stack is empty:
   - Android's own undo: Gboard and hardware keyboards send `android.R.id.undo` / `redo` through
     `InputConnection.performContextMenuAction`. `EditorTextTarget` gets `undo()` and `redo()`.
   - On screen: the keyboard bar, an extra bar below the tool window bar while the virtual keyboard is open
     (decided 2026-11-18, built later). Not the main toolbar's ⋮: that is the run widget's More Actions.
   - Later, with the phone's key row over the keyboard (not part of this plan), two buttons there.
4. **While the answer is pending** the editor shows nothing new (IDEate has no local undo history). That is the
   existing behaviour and stays. Local (speculative) undo is not planned.

### Steps

1. `onKey`: use the platform modifier. Test: hardware Ctrl+Z on the emulator undoes one typed word.
2. `canUndo` / `canRedo` in `EditorFile`, from the stack sizes; unit test with recorded `RdStackSizeItem`s.
3. `EditorTextTarget.undo/redo` and the Android input connection.
4. The keyboard bar's Undo and Redo buttons (later).
5. Device test: type, paste, complete, then undo and redo each; undo across a reconnect.

### Done (2026-11-18)

Steps 1 to 4 are built.

- **Keys:** `EditorView.onKey` uses ⌘ on a Mac and Ctrl elsewhere for Z and Shift+Z.
- **Enabled state:** the backend tells its stack sizes only in the answer to an undo or redo
  (`RdStackSizeItem`), so they are unknown until the first one, and a later text edit isn't counted in them.
  `EditSession.canUndo` / `canRedo` (`undoAvailable`, `redoAvailable`):

  | What is known | Undo | Redo |
  |---|---|---|
  | nothing yet (no undo done in this session) | enabled: the backend may hold history | greyed |
  | sizes known, undo stack 0, nothing typed since | greyed | enabled when the redo stack isn't 0 |
  | sizes known, text typed since | enabled | greyed: typing empties the backend's redo stack |

- **Touch:** Android's `android.R.id.undo` / `redo` input actions call the same. There is no on-screen Undo yet:
  bdd3abf put Undo and Redo under the main toolbar's ⋮, which is the run widget's More Actions (run
  configurations), so they were taken out again (user, 2026-11-18). Decided the same day: they go in a **keyboard bar**, an
  extra bar below the tool window bar that shows while the virtual keyboard is open. Not built yet.
- **Checked** on the emulator against the Mac backend: typed `hello`, Undo removed it, the enabled states
  followed (Undo greyed, Redo enabled), Redo brought it back and hardware Ctrl+Z removed it again (through the
  ⋮ menu that has since been removed).
- **Not checked:** Android's undo/redo input actions (Gboard shows no such button by default), and step 5's
  paste, completion and reconnect cases.

### Unknown

- **[Open]** The action's name ("Undo Typing"). The backend's answer names the command (`::Undo #Typing`), but
  the name of the *next* undo is not in `RdStackSizeItem`. Plain "Undo" / "Redo" until a source is found
  (`asyncActionsModel` presentation of `$Undo` in the editor's context is the first place to look).
- **[Open]** A multi-file undo's confirmation: expected as a backend dialog over Lux. Check with a Rename.

---

## 2. Cmd+Click: go to declaration, or show usages

### How IntelliJ does it

- Monolith: `CtrlMouseHandler` underlines the symbol under the pointer while ⌘ (Ctrl) is held;
  the click is the mouse shortcut of `GotoDeclaration`, handled by `GotoDeclarationOrUsageHandler2`
  (IC `platform/lang-impl/src/com/intellij/codeInsight/navigation/actions`): on a reference it goes to the
  declaration; on a declaration it shows its usages. Keyboard: ⌘B / Ctrl+B. ⌘⌥+click / Ctrl+Alt+click goes to
  the implementation, ⇧⌘+click to the type declaration.
- JetBrains Client, hover (`com.jetbrains.rdclient.hyperlinks.FrontendCtrlClickHost`) [Seen code]:
  - On mouse move with the modifiers held it turns them into a `BrowseMode` (`None`, `Declaration`,
    `TypeDeclaration`, `Implementation`) and, in the editing area and not over an inlay, calls

    ```
    solution.hyperlinkHighlightingModel.getElementRange(
      HyperlinkHighlightingArgs(editorId = <TextControlId>, offset = 412, mode = Declaration))
    → HyperlinkHighlightingResult(textRange = RdTextRange(408, 416), docInfo = "…")   // or
    → HyperlinkHighlightingDumpModeResult(message)                                      // while indexing
    ```
  - A non-empty range gets the hyperlink text attributes and the hand cursor. Mouse release, a key change or
    a scroll drops it.
- JetBrains Client, click: the usual action route. `FrontendCtrlClickHost` asks `ActionCallPolicy` for the
  strategy of the action id `"CtrlClick"`; the action itself is `GotoDeclaration`, run on the backend with the
  editor as context. [Seen code for the id; the dispatch to `GotoDeclaration` is **Inferred** from the keymap
  and from `InlayClickAction use-shortcut-of="GotoDeclaration"`]
- What the backend does next arrives over models IDEate already reads:
  - same file: a caret move in `backendPatches`;
  - another file: a new entry in `fileEditorsModel.backendDeferredComposites` and a selection change
    (`fileEditorsModel.selectionChanged` with `OriginKind.Backend`);
  - on a declaration: the usages popup, `RdShowUsagesSession(textControlId, popupWindowContext, scopeName,
    maxUsages, …)` under `FindUsagesModel.sessions`, shown by `FrontendShowUsagesManager` with IntelliJ's own
    Show Usages popup. [Seen code: model fields]

### What IDEate has

- `RdCallActionRequest` with the editor's timestamps runs any backend action (`ActionMenus`,
  `EditSession.actionTimestamps()`); the caret is moved to the click first for the context menu.
- Files the backend opens on its own are already picked up from `backendDeferredComposites` and named through
  the VFS.
- No modifier-hover, no handling of a backend-made selection change, no usages popup. `Find Usages` and
  `Go To` are greyed out in the editor menu.

### Design

1. **Pointer (desktop, Android with a mouse).** As IntelliJ: with ⌘ (Mac) or Ctrl held, a move over the text
   asks `getElementRange` for the offset under the pointer (one request at a time, dropped when the pointer
   leaves the offset's word). The answer's range is drawn with the scheme's `CTRL_CLICKABLE` attributes
   (Islands Dark: link blue, underlined) and the pointer becomes a hand. Click: move the caret there, send
   `GotoDeclaration`. ⌘⌥ / Ctrl+Alt → `GotoImplementation`, ⇧⌘ / Ctrl+Shift → `GotoTypeDeclaration`.
2. **Keys.** ⌘B / Ctrl+B = `GotoDeclaration` at the caret; ⌘⌥B / Ctrl+Alt+B = `GotoImplementation`;
   ⌘[ and ⌘] / Ctrl+Alt+← and → = `Back` and `Forward`.
3. **Touch.** A finger has no modifier. The editor's long-press menu un-greys **Go To ▸ Declaration or
   Usages**, **Implementation(s)**, **Type Declaration** and **Find Usages**, as IntelliJ's own menu lists
   them. No new gesture, so a tap stays a caret move.
4. **Following the backend.** When the backend selects another editor (`selectionChanged`,
   `OriginKind.Backend`), IDEate shows that tab and scrolls to its caret. Today only IDEate's own selections
   are sent; backend ones are ignored.
5. **Back.** Android's Back and the toolbar get IntelliJ's `Back` action only through the keys above; Android
   Back keeps its current meaning (leave the session).
6. **Usages popup.** IntelliJ's Show Usages popup, built from `RdShowUsagesSession`: title "Usages of *name*",
   a row per usage (file icon, line, text with the match bold), a footer with the scope. A tap navigates
   through the session. This is the largest part and comes last; until then a click on a declaration does
   nothing visible, as before.

### Steps

1. Record a JetBrains Client session doing the four cases (same file, other file, library class, on a
   declaration) with the rd log. Settles the two [Open] items below.
2. `GotoDeclaration` from ⌘B and from the editor menu; follow the backend's selection change. Test on the Mac
   backend: `greeting.length()` → `String.length` opens `String.class`.
3. Hover underline and click with the modifier (desktop first).
4. `Back` / `Forward`.
5. Usages popup.

### Unknown

- **[Open]** Which action id the click sends (`GotoDeclaration` or a client-only id mapped by
  `ActionCallPolicy`). Step 1.
- **[Open]** How a library class opens: as a normal composite with decompiled text (expected), or something
  IDEate has no model for. Step 1.
- **[Open]** `RdShowUsagesSession`'s item list: the fields were read, the flow of `RdUsage` rows was not.

---

## 3. Find and replace in the editor

### How IntelliJ does it

- ⌘F / Ctrl+F opens a bar at the top of the editor (`EditorSearchSession`, `SearchReplaceComponent`,
  IC `platform/lang-impl/src/com/intellij/find`); ⌘R / Ctrl+R adds the replace row.
- The bar, left to right:

  | Row | Contents |
  |---|---|
  | Find | search field with history ▾ and, inside it, New line, **Cc** (match case), **W** (words), **.\*** (regex); then "3/12" or "0 results"; ↑ ↓ (previous / next); filter (Anywhere / In Comments / In String Literals / …); ⋮ (Select All Occurrences, Find in Selection, …); ✕ |
  | Replace | replace field with history ▾, New line, **Aa** (preserve case); buttons **Replace**, **Replace All**, **Exclude** |

- Behaviour: every match in view is highlighted as you type (`LivePreviewController`), the current one more
  strongly; Enter / ↓ = next, Shift+Enter / ↑ = previous, wrapping at the ends; Esc closes and leaves the
  caret on the current match; ⌘G / F3 repeats after closing. The field starts with the selection, or the word
  history's last entry.
- Matching is `FindManager.findString` on the document's text: plain text, whole words, or
  `java.util.regex` with `MULTILINE`; Replace with regex supports `$1` groups; Preserve Case adapts the
  replacement's case.
- JetBrains Client has no code of its own for this: `intellij.rd.client.jar` has no search-session class, so
  IntelliJ's bar runs on the client's copy of the document. A replacement is an ordinary local document
  change, sent as a patch. **[Inferred]** from the absence of client classes and from how every other local
  edit travels (`edit-protocol-client.md`).

### What IDEate has

- The document's full text in `EditSession.text`; highlighters drawn from backend markup; a shared
  `SearchTextField` component; `RdApplyPatchRequest` for local caret moves.
- No search bar. No way to send a local text change that isn't a typed key, a paste or a completion.

### Design

Everything runs in IDEate, as in JetBrains Client.

1. **Bar**: IntelliJ's, same order and icons, above the editor's text and under the tabs. On a phone the two
   rows keep IntelliJ's order; the filter and ⋮ go into one overflow menu when the row is narrower than its
   content.
2. **Search** (`ui/EditorSearch.kt`, pure Kotlin, unit tested): `findAll(text, FindOptions(query, matchCase,
   wholeWords, regex)) → List<IntRange>`. Plain search is `indexOf` with `ignoreCase`; whole words checks the characters on both sides of a match, as `FindManagerImpl` does; regex is `java.util.regex`
   with IntelliJ's flags. An invalid regex shows IntelliJ's red field and "Bad pattern". Re-run 100 ms after
   typing stops and after every document change.
3. **Highlights**: matches painted with the scheme's `TEXT_SEARCH_RESULT_ATTRIBUTES`, the current one with
   `SEARCH_RESULT_ATTRIBUTES` plus IntelliJ's border, and marks in the scrollbar when IDEate has one.
4. **Keys**: ⌘F / Ctrl+F, ⌘R / Ctrl+R, Enter, Shift+Enter, Esc, ⌘G / F3 and ⇧⌘G / Shift+F3. On touch the bar
   opens from the keyboard bar (section 1; later) and the soft keyboard's action key is "next".
5. **Replace**: a new `EditSession.replace(range, text)`: a local document change in a command named as
   IntelliJ names it ("Replace"), sent as `RdApplyPatchRequest`, rebased like any pending edit. **Replace
   All** is one command with the changes from the last match to the first, so it is one undo step.
   Regex groups and Preserve Case follow `FindManagerImpl`'s replacement rules.
6. **Not in the first version**: the In Comments / In String Literals filters (they need the backend's
   lexer), Select All Occurrences and Find in Selection. Their menu entries show greyed out.

### Steps

1. `EditorSearch` matching with tests ported from IntelliJ's `FindManagerTest` cases.
2. The bar and highlights, find only.
3. `EditSession.replace` (check the patch shape against a recorded JetBrains Client Replace first), then
   Replace and Replace All.
4. Phone layout and keyboard action.

### Unknown

- **[Open]** The exact patch JetBrains Client sends for Replace and Replace All (command name, group id, one
  patch or several). Record it before step 3.

---

## 4. Find in Files and Replace in Files

### How IntelliJ does it

- ⇧⌘F / Ctrl+Shift+F opens the Find in Files popup (`FindPopupPanel`, IC `platform/lang-impl/src/com/intellij/
  find/impl`); ⇧⌘R / Ctrl+Shift+R the same with a replace field.

  | Part | Contents |
  |---|---|
  | Title row | "Find in Files", match count ("12 matches in 3 files"), **File mask** checkbox and field, filter, pin |
  | Fields | search field with Cc / W / .\*; replace field (replace mode) with Aa |
  | Scope row | **In Project**, **Module**, **Directory**, **Scope**, each with its chooser |
  | Results | one row per match: the line with the match bold, then path and line number, right aligned |
  | Preview | the file around the selected match, editable in IntelliJ |
  | Footer | **Open in Find Window** (⌘⏎); in replace mode **Replace All** and **Replace** |

- Since 2026.x the popup is split (`find.in.files.split`, default `true`): the frontend draws it and asks the
  backend over Fleet RPC, service `com.intellij.platform.find.FindInFilesApi`
  (IC `platform/find/src/FindInFilesApi.kt`) [Seen code]:

  ```
  findByModel(findModel: FindModel, projectId: ProjectId,
              filesToScanInitially: List<VirtualFileId>, maxUsagesCount: Int): Flow<FindInFilesResult>
  performFindAllOrReplaceAll(findModel, openInNewTab: Boolean, projectId)
  checkDirectoryExists(findModel): Boolean
  ```
- A result is `FindInFilesResult(presentation: List<SerializableTextChunk>, line, navigationOffset,
  mergedOffsets, length, fileId: VirtualFileId, presentablePath, shortenPresentablePath, backgroundColor,
  tooltipText, iconId, fileLength)`. Example: `line = 6`, `navigationOffset = 229`, `length = 8`,
  `shortenPresentablePath = "src/demo/Main.java"`.
- `FindModel` (IC `platform/indexing-api/src/com/intellij/find/FindModel.kt`) is `@Serializable`: the query,
  the replacement, match case, whole words, regex, `searchContext`, file mask (`fileFilter`), and the scope
  (`isProjectScope`, `moduleName`, `directoryName` + `isWithSubdirectories`, `customScopeName` /
  `customScopeId`).
- The frontend asks again on each change of the query (`FindAndReplaceExecutorImpl`: a new search cancels the
  running one; "load more" extends it).
- **Open in Find Window** and **Replace All** run wholly on the backend (`performFindAllOrReplaceAll` →
  `FindInProjectManager.findInPath` / `ReplaceInProjectManager.replaceInPath`). The source says the results
  are *not* sent back; they show in the backend's Find tool window. [Seen code: the API's comment]
- A single **Replace** changes the selected usage (`ReplaceInProjectManager.replaceUsage`).

### What IDEate has

- `FleetRpc` with calls, subscriptions and streams (used by completion and Structure); the kernel project id;
  opening a file by path and moving the caret; the Fleet icon id decoder (`iconPath`).
- No popup. "Find in Files" is greyed out.

### Design

1. **Popup**: IntelliJ's layout in a dialog window. On a phone it is full width with the preview under the
   list, hidden while the keyboard is up.
2. **Search**: on each query change (debounced 100 ms, the delay `FindPopupPanel` reschedules with), cancel the running call and
   start `findByModel` with `maxUsagesCount = 1000`; rows appear as they stream in. Scope chooser: In
   Project and Directory first (Directory uses the folder chooser IDEate already has); Module and Scope
   after.
3. **Rows**: `presentation` chunks drawn with their attributes, then `shortenPresentablePath` and `line + 1`.
4. **Open a result**: Enter or a tap opens the file at `navigationOffset` and closes the popup (pinned: stays).
5. **Preview**: the result's file around the match. First version: the lines IDEate can get by opening the
   document read-only in the background; if that proves heavy on the phone, the preview shows only the
   matching line and its neighbours from `presentation`.
6. **Replace**: single Replace opens the file and uses `EditSession.replace` (section 3). **Replace All**
   calls `performFindAllOrReplaceAll`; the backend's confirmation comes over Lux.
7. **Open in Find Window**: needs the Find tool window, which IDEate doesn't show yet (it is a backend
   window). Greyed out until tool windows drawn by the backend exist.

### Steps

1. Call `findByModel` from a throwaway command and log the JSON: settles the `FindModel` and `VirtualFileId`
   wire forms.
2. Popup with the field, options, In Project scope and results; open on Enter.
3. File mask, Directory scope, match count, "load more".
4. Preview.
5. Replace mode: Replace, then Replace All.

### Unknown

- **[Open]** `FindModel`'s JSON (kotlinx serialization of a class with many defaults) and `VirtualFileId`
  (expected: the same file id rd's VFS uses). Step 1.
- **[Open]** Whether `SerializableTextChunk` carries colours as ids or values.
- **[Open]** Where the backend's Replace All confirmation and its result ("12 occurrences replaced") appear
  for a client without the Find tool window.

---

## 5. Search Everywhere

### How IntelliJ does it

- Double ⇧ opens the popup (`SearchEverywhere` action); ⌘O / Ctrl+N, ⇧⌘O / Ctrl+Shift+N, ⌥⌘O / Ctrl+Alt+Shift+N
  and ⇧⌘A / Ctrl+Shift+A open it on a tab.
- 263 uses the new, split implementation (`search.everywhere.new.enabled=true`), IC `platform/searchEverywhere`
  (`frontend`, `backend`, `shared`).

  | Part | Contents |
  |---|---|
  | Tabs | **All**, **Classes**, **Files**, **Symbols**, **Actions**, **Text** (ids `SearchEverywhereContributor.All`, `ClassSearchEverywhereContributor`, `FileSearchEverywhereContributor`, `SymbolSearchEverywhereContributor`, `ActionSearchEverywhereContributor`, `TextSearchContributor`), then tabs plugins add |
  | Right of the tabs | scope or type filter of the tab, "Include non-project items" checkbox, preview toggle, Open in Find Tool Window |
  | Field | search icon, the query, a hint ("Type / to see commands") |
  | List | rows by kind: icon, name with the match highlighted, container ("in demo"), location on the right; group separators in All ("Classes", "Files", …); "… more" |
  | Footer | the selected item's extended info (path) and shortcut hints |

- All of it is one Fleet RPC service, `com.intellij.platform.searchEverywhere.impl.SeRemoteApi`
  (IC `platform/searchEverywhere/shared/src/impl/SeRemoteApi.kt`) [Seen code]. The calls a first version needs:

  ```
  getAvailableProviderIds(projectId, session, dataContextId): SeSortedProviderIds?
  getItems(projectId, session, providerIds: List<SeProviderId>, isAllTab, params: SeParams,
           dataContextId, requestedCountChannel: ReceiveChannel<Int>): Flow<SeTransferEvent>
  itemSelected(projectId, session, itemData, modifiers: Int, searchText, isAllTab): Boolean
  getSearchScopesInfoForProviders(…), getTypeVisibilityStatesForProviders(…), getDisplayNameForProviders(…)
  ```
- `SeParams(inputQuery = "main", filter = SeFilterState.Data(mapOf(…)))`. The stream gives
  `SeTransferItem(itemData)`, `SeTransferSkippedItem(providerId)` and `SeTransferEnd(providerId)`.
  The frontend asks for more by sending counts on `requestedCountChannel` ("give me the next 50").
- `SeItemDataImpl(uuid, providerId, composedWeight, presentation, uuidsToReplace, additionalInfo, itemRef)`;
  `presentation` is one of `SeTargetItemPresentation` (classes, files, symbols: icon, text with matched
  ranges, container text, location text and icon), `SeActionItemPresentation`, `SeTextSearchItemPresentation`
  and a few more.
- Choosing a row is `itemSelected(…)`: the backend navigates (opens the file, runs the action) and answers
  whether the popup should close.
- The frontend's own work: one stream per tab, merging and ordering by weight (`SeResultsCountBalancer`,
  `uuidsToReplace` replaces earlier rows), and the tab's filter editor.

### The hard part: the session

`session` is not a string. `SeSessionImpl(ref: DurableRef<SeSessionEntity>)` points at an entity in Rhizome,
the database that the frontend and the backend keep in sync. JetBrains Client creates it itself:

```kotlin
// IC platform/searchEverywhere/shared/src/SeSessionEntity.kt
suspend fun createSession(): SeSession = change { shared { SeSessionEntity.new {} } }.ref().let { SeSessionImpl(it) }
```

The backend looks the entity up for every item (`SeItemEntity.createWith`: `session.asRef().derefOrNull()
?: return null`), so a made-up id gives **no results**. [Seen code]

IDEate has no Rhizome database: completion and Structure only needed ids that came over rd
(`completion-protocol.md` section 5).

**Decided 2026-11-18: bring up the real database client, with JetBrains' own library**, as IDEate does for rd.
A hand-made "create session" transaction was the first idea and is dropped: it would write to a database it never
reads, couldn't see a rejected transaction, and more features will need the database.

How JetBrains Client starts it [Seen code, decompiled `remdev-plugin.jar` and
`intellij.platform.kernel.base.frontend.split.jar`]:

```kotlin
// FrontendRemoteApiProviderService: one Fleet RPC client over the rd model FleetRpcModel
val client = fleetClient(ClientId(UID.random()), RdTransportFactory.client { protocol },
                         abortOnError = …, requestInterceptor = ClientCausalityRequestInterceptor)
fun <A> resolve(d: RemoteApiDescriptor<A>) = client.asServiceProxy().proxy(d, route, InstanceId(d.apiFqn))

// FrontendKernelService: the database, kept in step with the backend's
withKernel(middleware) {                                   // withTransactor { withRete { … } }
  withRebaseLoop(async { resolve(remoteApiDescriptor<RemoteKernel>()) }, CommonInstructionSet,
                 reconnectWhenBroken = true) {
    handleEntityTypes(transactor(), this)                  // registers every plugin's entity types
    …
  }
}
```

- `fleet.rpc.jar`, `fleet.rhizomedb.jar`, `fleet.rhizomedb.transactor.jar` and
  `fleet.rhizomedb.transactor.rebase.jar` are in JetBrains Client's `lib` (about 6 MB), with their sources in IC
  under `fleet/`. `RemoteKernel` (service id `fleet.reserved.RemoteKernel`) is
  `subscribeWithChunkedSnapshot(author)` → the snapshot, a vector clock and a flow of transactions, plus
  `transact(frontendTxs)` for the client's own.
- `ClientCausalityRequestInterceptor` adds the client's vector clock to each call and waits until the local
  database has caught up with the answer's.
- The session entity type is `SeSessionEntity` (`DurableEntityType`, ident
  `com.intellij.platform.searchEverywhere.SeSessionEntity`). IDEate declares a type with the same ident instead
  of loading IntelliJ's platform jars.

### What IDEate has

- `FleetRpc` (calls, subscriptions, outgoing streams with refill), the kernel project id, the icon id
  decoder, camel-hump match painting from completion, the popup and list components.
- No Rhizome, no data context ids.

### Design

1. **Popup**: IntelliJ's, centred near the top of the window at IntelliJ's `BigPopup` size on desktop, full width
   on a phone with the tabs scrolling sideways. Opened by double ⇧ (two Shift presses with no other key between them,
   with `ModifierKeyDoubleClickHandler`'s timing: released within 500 ms, pressed again within 300 ms), the tab shortcuts above, and on touch the magnifier in the
   main toolbar, where IntelliJ's new UI has it.
2. **Tabs**: All, Classes, Files, Symbols, Actions first; Text after Find in Files works (same results type).
   The provider ids for All come from `getAvailableProviderIds`.
3. **Search**: per query (debounced 100 ms) cancel the tab's stream and start `getItems`, asking for 50 rows
   and 50 more when the list nears its end. Rows are ordered by weight; an item whose `uuidsToReplace` names
   shown rows replaces them. In All, rows are grouped by provider in the backend's order with IntelliJ's
   separators.
4. **Rows**: by presentation type. Targets: icon, text with the matched ranges bold, container text in grey,
   location right aligned. Actions: name, shortcut, the toggle state.
5. **Choosing**: Enter or a tap calls `itemSelected` and closes the popup when it answers true. The backend
   opens the file; IDEate follows its selection change (section 2, step 4 is a prerequisite).
6. **Filters**: "Include non-project items" and the scope chooser of each tab, from
   `getSearchScopesInfoForProviders`. Type filters and preview later.
7. **Session lifetime**: one session per popup opening, retracted when it closes.

### Steps

1. **Spike on the desktop (go / no-go), in three parts:**
   1. JetBrains' Fleet RPC client over IDEate's `FleetRpcModel`: resolve `RemoteKernel` and read the snapshot.
   2. The database: `withTransactor`, `withRete`, `withRebaseLoop`; log how many entities arrive and stay in
      step.
   3. Create a `SeSessionEntity`, call `getItems` for the Files tab. Done when `Main` returns `Main.java` on
      the Mac backend.
   Then the same on Android (the jars need the ART patching the rd jars get).

   **Spike results, 2026-11-18, desktop against the Mac backend** (`desktop/…/KernelSpike.kt`, run with
   `CLIENT_KERNEL_SPIKE=1`; `ClientSession.fleetModelListener` hands it the rd `FleetRpcModel`):

   | Part | Result |
   |---|---|
   | 1. JetBrains' `rpcClient` over `FleetRpcModel`, next to IDEate's own `FleetRpc` | works. Each client has its own origin id and takes only envelopes addressed to it. Route `fleet`, instance id = the API's name (`fleet.kernel.rebase.RemoteKernel`). `subscribeWithChunkedSnapshot(null)` answered with a vector clock and the snapshot |
   | 2. Local database following the backend's | works, set up as IntelliJ's `startClientKernel`: `withTransactor(FollowerTransactorMiddleware(DefaultInstructionSet.encoder()), defaultPart = FrontendPart)`, `withRete`, `withRebaseLoop(…, DefaultInstructionSet, …)`. 30 entities after the snapshot (bookkeeping types only on a fresh backend) |
   | 3a. Create a `SeSessionEntity` | works locally, no rejection from the backend. IDEate declares the type itself (`SearchSessionEntity`): a type is known by its name, and the module is always `<unknown>`; a reference travels as `{"uid": "…"}` |
   | 3b. `SeRemoteApi.getItems` with that session | not done: needs the call with an outgoing stream argument, and the client's vector clock in the call's meta so the backend waits for the session (`ClientCausalityRequestInterceptor`) |

   What it took:
   - Fleet's logging finds its factory through Java's service loader and fails without one:
     `FleetLoggerFactory`, registered in `META-INF/services/fleet.util.logging.KLoggerFactory`.
   - kotlinx-coroutines 1.11 (the jars call `SendChannel.close`'s default-argument bridge, missing in 1.9). The
     desktop moved to 1.11.0 through `kotlinx-coroutines-swing`. None of the jars use JetBrains' coroutines fork.
   - The jars, from JetBrains Client's `lib`: `fleet.rpc`, `fleet.rhizomedb`, `.transactor`, `.transactor.rebase`,
     `fleet.util.core`, `.serialization`, `.logging.api`, `.codepoints`, `fleet.multiplatform.shims`,
     `fleet.reporting.api`, `.shared`, `fleet.bifurcan`, `fleet.fastutil`, `fleet.openmap`, `fleet.radixTrie`,
     `intellij.libraries.kotlinx.collections.immutable`.
   - `ChangeDocument` (IntelliJ's `CommonInstructionSet` adds it to `DefaultInstructionSet`) is left out: it is in
     IntelliJ's platform jars. Not needed so far; a backend transaction using it would stop the rebase loop.
2. Popup with the Files tab end to end, including opening the result.
3. Classes, Symbols, All (merging, groups, "more").
4. Actions (running one is `itemSelected`; results that open dialogs come over Lux).
5. Filters, then the Text tab.

### Unknown

- **[Open]** Whether JetBrains' Fleet jars run outside IntelliJ: they are built against JetBrains' coroutines
  fork and may reach for platform classes (logging, tracing). Step 1.
- **[Open]** Whether two RPC clients (IDEate's hand-written `FleetRpc` and JetBrains') can share one
  `FleetRpcModel`, or completion and Structure must move onto JetBrains' client first. Step 1.
- **[Open]** Android: the jars' size and whether they run on ART.
- **[Open]** `dataContextId`: JetBrains Client registers its data context and sends an id. Whether `null` or
  the editor's context from rd is enough for `getAvailableProviderIds`. Step 1.
- **[Open]** If the spike fails: the fallback is the backend's own Search Everywhere popup over Lux, which
  would look like IntelliJ but not feel native (no soft-keyboard hints, scaled on a phone).

---

## Shared pieces

| Piece | Used by | Notes |
|---|---|---|
| Platform shortcut modifier (⌘ on a Mac, Ctrl elsewhere) in `EditorView.onKey` | 1, 2, 3, 4, 5 | today only paste and select-all in Lux dialogs use it |
| Follow a backend-made editor selection | 2, 4, 5 | `fileEditorsModel.selectionChanged`, `OriginKind.Backend` |
| `EditSession.replace(range, text)` as `RdApplyPatchRequest` | 3, 4 | one command, one undo step |
| Text with matched ranges bold | 2 (usages), 4, 5 | completion already paints camel-hump matches |
| A recording of JetBrains Client doing each feature | 2, 3, 4, 5 | one session, recorded as in `split-protocol.md`, "A recorded session" |
| Touch entries: a magnifier in the main toolbar; the keyboard bar (below the tool window bar while the virtual keyboard is open) for Undo, Redo, Find…, Replace… | 1, 3, 4, 5 | the keyboard bar is decided, built later; never the run widget's ⋮ |
