# Lux: backend Swing dialogs over rd in JetBrains Client 263.7307.200 / IntelliJ IDEA 2026.3 backend

Researched 2026-11-17 for pending #7 (backend dialogs; intellij-backend-android `docs/pending.md`). Static analysis
only (unzip + Fernflower from the IDEA install's `plugins/java-decompiler` + community source). Nothing was started or connected.
Tags: **Seen in code** = read in a decompiled class (class and member given). **Inferred** = my conclusion.
Decompiled copies (temporary, in a session scratchpad, not kept): `dec-lux/out/` with `proto` (LuxModel and Lx* types), `frontend.split`, `backend.split`, `split` (shared Lux util), `model` (BeControl family), `bdlg` and `rdclient` (BeDialog route).

## 0. Short answers

| Question | Answer |
|---|---|
| Is Lux generic or semantic? | **Generic: a remote canvas.** Each Swing window is one `LxPanel`/`LxDialog` that carries a stream of draw operations (fill shape, draw text, draw image, clip, transform, paint changes). There is no label/button/text-field type in `LxAbstractComponent`. Text does arrive as plain strings inside `LxDrawTextCommand`, but with no widget identity. |
| Where do the model classes live? | `intellij.platform.split.protocol.jar` in `lib/` (package `com.jetbrains.rd.ide.model`), not in `intellij.rd.ide.model.generated.jar` (that jar only holds the BeControl family). |
| Which route is cheapest? | Neither is cheap, but only Lux is actually used for ordinary dialogs on a remote-dev backend. The BeDialog/BeControl route is semantic (easy to render) but **Inferred: not used for Rename/New/Delete on a split backend** (section 4). |
| Smallest plan | Bind `luxModel` and `codeWithMeUnattendedHostControlModel.session.graphicsDevices`, show `LxDialog`s as a Compose window with a `Canvas` that replays draw operations, send `LxMouseEvent`/`LxKeyEvent` back. About 12 draw commands, 5 shape types, colour and gradient paint, PNG images, state tracking (section 5). |

## 1. LuxModel shape

**Seen in code**, `LuxModel` (extends `RdExtBase`, `serializationHash = 5589913469715632981L`):

| Member | Type | Direction (Inferred from use) |
|---|---|---|
| `components` | `RdMap<Int, LxAbstractComponent>` | backend adds/removes entries (key = Lux id from `LuxHostService.register`, a counter) |
| `cleanImagesFromCache` | `RdSignal<List<Int>>` | backend tells client which cached image ids to drop |
| `eventToNonLuxifiedComponent` | `RdSignal<LxEvent>` | client sends events for windows that are not Lux windows (native frontend windows) |
| `showMainWindow` | `RdOptionalProperty<Boolean>` | client toggles the Lux "main window" (controller debug feature) |

Where it hangs: **Seen in code** `LuxModel_GeneratedKt.getLuxModel(IProtocol)` is `protocol.getOrCreateExtension(LuxModel::class)`. A root ext on the protocol, same level as `asyncActionsModel`. The client binds it with `RootExtListener<LuxModel>` for endpoint `"IDE Frontend"` (`LuxClientService$Listener`, registered in `intellij.platform.frontend.split.xml`). The backend gets the same ext through `RemoteSessionKt.getProtocol(session)` in `LuxHostConnection.getModel()`.

### Component hierarchy (all in `com.jetbrains.rd.ide.model`)

```
RdBindableBase
  LxAbstractComponent                      graphics: LxGraphics, info: LxAbstractComponentInfo (creationStack, className)
  |                                        localComponents: RdMap<LxLocalComponentId, LxLocalComponent>
  |                                        size: Prop<LxSize>, visible: Prop<Boolean>, position: Prop<LxPoint>
  |                                        cursor: Prop<LxCursor>, graphicsConfiguration: Prop<RemoteGraphicsConfigurationModel>
  |                                        events: RdSignal<LxEvent>, unprocessedKeyEvents: RdSignal<LxKeyEvent>
  |                                        requestRepaint: RdCall<Unit, Unit>
  +-- LxPanel                              layoutSource: LuxLayoutSource (Frontend|Backend), minimumSize/maximumSize/preferredSize: Prop<LxSize>
  |     |                                  inputLocation: Prop<LxRect> (IME caret box), focusableOnFrontend: Boolean
  |     +-- LxWindow                       owner: LxWindowRef, measureShowingTime, typePopup, waylandUnconstrained,
  |           |                            roundedCornerParameters: List<LxRoundedCornerParam>, focusableWindowState: Prop<Boolean>
  |           +-- LxFrame                  title: Prop<String>, resizable: Prop<Boolean>
  |                 +-- LxDialog           modality: Boolean, undecorated: Boolean
  |                                        (LxFrameInfo adds isMainWindow, projectId)
LxLocalComponent (RdBindableBase)          bounds: Prop<LxRect>, visible: Prop<Boolean>
  +-- LxLocalBeControl                     control: BeControl      <- a semantic widget embedded inside a Lux window
  +-- LxScrollPaneComponent                graphics, viewSize        <- never constructed on the 263 backend (only the model class exists)
```

There are **no** subclasses for label, button, text field, checkbox, combo box, tree, list or scroll pane. **Seen in code**: the backend only constructs `LxWindow`, `LxFrame`, `LxDialog`, `LxPanel` (`LuxFrameAdapter`, `LuxDialogAdapter`, `LuxWindowAdapter`, `LuxPanelAdapter`) and `LxLocalBeControl` (`LuxBackendLocalBeControlBinder.bind`).

`LxWindowRef` (what `owner` points at): `LxLuxWindowRef(id)`, `LxPopupWindowRef(id)`, `LxIdeFrameRef` (the main IDE frame, "use the last focused IDE window"), `LxNonLuxWindowRef` (some native window on the client).

### What carries the content: `LxGraphics`

`LxGraphics.drawOperations: RdSignal<List<LxDrawOperation>>` (backend to client). `LxDrawOperation(command: LxDrawCommand, stateChanges: List<LxStateChange>)`: the state changes apply before the command.

| `LxDrawCommand` | Fields |
|---|---|
| `LxDrawTextCommand` | `text: String, x, y, width, height: Float` (backend's measured bounds; the client stretches text to fit) |
| `LxDrawGlyphTextCommand` | `text, font: LxFont, isRtl, x, y` (`LxDrawGlyphCommand` family, also `LxDrawGlyphShapeCommand(shape)`) |
| `LxFillShapeCommand` / `LxDrawShapeCommand` | `shape: LxShape` |
| `LxDrawImageCommand` | `image: LxImage, dst: LxRect, transform: LxAffineTransform` |
| `LxCopyAreaCommand` | `source: LxRect, dx, dy` (scroll blit) |
| `LxClearStateCommand` | none |

| `LxStateChange` | Payload |
|---|---|
| `LxPaintStateChange` | `LxPaint`: `LxColorPaint(color)`, `LxGradientPaint`, `LxLinearGradientPaint(start,end)`, `LxRadialGradientPaint`, `LxTexturePaint(image, anchor)` |
| `LxFontStateChange` | `LxFont`: `LxPlainFont(name, style, size)`, `LxSubFamilyFont(+family, subFamily)` |
| `LxClipStateChange` | `LxShape` |
| `LxTransformStateChange` | `LxAffineTransform(matrix: double[])` |
| `LxStrokeStateChange` | `LxBasicStroke(width, cap, join, miterLimit, dash, dashPhase)` or `LxCustomStroke` |
| `LxCompositeStateChange` | `LxAlphaComposite(rule, alpha)` or `LxXorComposite` |
| `LxBackgroundColorStateChange` | `color` |
| `LxAliasingStateChange` | `fractionalMetrics, textAliasing` |

Shapes: `LxRectShape`, `LxRoundRectShape(+arcWidth, arcHeight)`, `LxLineShape`, `LxEllipseShape`, `LxArcShape`, `LxPolygonShape`, `LxPathShape(windingMode, elements)`, `LxQuadCurveShape`, `LxCubicCurveShape`.
Images: `LxCacheableImage(id, content, keepForDiff)`, `LxCachedImage(id)` (reference by id), `LxNonCacheableImage(content, keepForDiff)`, `LxDiffImage(x, y, content)` (partial update, registry `lux.incremental.image.coding` defaults to true). Content is `LxPngImageContent(bytes)`.

**Seen in code**: `LuxTextPipe.drawChars` (backend) emits `LxDrawTextCommand` for plain `drawString`; `LuxGraphics2D` is a `Graphics2D` implementation whose pipes (`LuxDrawPipe`, `LuxTextPipe`, `LuxDrawImagePipe`, `LuxGraphicsPipe`) queue these commands. So the backend runs real Swing paint on its own fonts and sends the result as vector commands. **Seen in code**: the client's `LuxRenderer.drawLxText` rescales the text to the `width`/`height` the backend measured (`adjustTextTransform`), so the client's font does not have to match.

Layout is done by the backend (`LuxLayoutSource.Backend` is hard-coded in `LuxDialogAdapter.Companion.create`). The client does not lay out a dialog's insides.

## 2. How the JetBrains Client renders and returns input

**Seen in code**, frontend module `intellij.platform.frontend.split.jar`, package `com.jetbrains.thinclient.lux`:

| Step | Class and member |
|---|---|
| Bind root ext | `LuxClientService$Listener.extensionCreated` creates `LuxClientService(lifetime, session, luxModel)` (skipped when headless) |
| Watch components | `LuxClientService.<init>` posts `luxModel.components.view(lifetime) { id, component -> bindComponent }` |
| Dispatch by type | `bindComponent` wraps every entry in a `LuxFrontendPanel` (a `JPanel`), then `LxDialog` -> `bindOrPostponeDialog`, `LxFrame` -> `bindFrame`, `LxWindow` -> `bindWindow`, else `bindPanel` |
| Dialog window | `LuxFrontendDialog extends LuxFrontendDialogBase extends DialogWrapper`. Modality: `model.modality ? IdeModalityType.IDE : MODELESS`. `createCenterPanel()` returns the `LuxFrontendPanel`; `createCancelAction()` and `createContentPaneBorder()` return null, so the frontend dialog has **no buttons of its own**, everything is inside the pixels |
| Parent | `unwrapLxRef(lxDialog.owner)`: `LxLuxWindowRef(id)` finds another Lux window, `LxIdeFrameRef` the last focused IDE window |
| Postponed dialogs | `shouldPostponeDialog`: non-modal dialog owned by `LxIdeFrameRef` while no project is open is held until `projectOpened` |
| Visible | `LuxFrontendWindowAdapter.subscribeVisibilityChange` advises `model.visible` and calls `setVisible`; `LuxFrontendFrameAdapter.setVisible(true)` calls `ModalUiUtilsKt.showProtocolBasedDialog` (nested modal loop that still pumps the protocol) |
| Title, resizable | `LuxFrontendFrameAdapter.init` advises `frameModel.title` and `.resizable` |
| Size | Backend publishes `preferredSize`/`minimumSize`; the frontend does `panel.setPreferredSize(fromLx(it))` so the `DialogWrapper` packs to it (`LuxFrontendPanelAdapter` init). The frontend writes its real size back into `model.size` (`LuxFrontendPanelAdapter`, "Set size") |
| Position | Frontend writes `model.position` = panel location on screen (`updatePositionForModel`); backend writes a position the frontend applies via `window.setLocation` (`LuxFrontendWindowAdapter.init`). The dialog is otherwise placed by `DialogWrapper` (centered on parent) |
| Graphics config | Frontend sets `model.graphicsConfiguration` (`RemoteGraphicsConfigurationModel`: `deviceId, bounds, defaultTransform, normalizingTransform`) |
| Painting | `LuxRenderer(lifetime, panel, model.graphics)` advises `drawOperations`, applies state changes and draws into a (volatile) bitmap; `paintComponent` blits it. Registry `lux.dialog.wait.time.ms` (50) sleeps after show "to ensure correct dialog sizing" |
| Repaint on demand | `model.requestRepaint` (`RdCall<Unit,Unit>`), `LuxFrontendPanelAdapter.forceHostToRepaint` |

### Input going back

All input goes through `LuxEventSynchronizer` (a Swing listener attached to the panel and to the dialog window). Each event is converted by `EventsKt.toLx` (module `intellij.platform.split.jar`, package `com.jetbrains.rd.platform.codeWithMe.lux.util`) and fired with `model.events.fire(event)` (`send`). Mouse-wheel events are merged by a `MergingUpdateQueue` (`lux.scrolling.merge.timespan`, default 0).

| Event | Shape (**Seen in code**) |
|---|---|
| `LxMouseEvent` | `type: Clicked/Pressed/Released/Moved/Entered/Exited/Dragged/Wheel`, `x, y` (panel-relative, or relative to the last press for Dragged), `xAbs, yAbs` (screen), `clickCount, causedByTouchEvent, button` (AWT: 1 left, 2 middle, 3 right), `popupTrigger`, `modifiers` (AWT modifier mask) |
| `LxMouseWheelEvent` | mouse fields + `scrollType, scrollAmount, wheelRotation, preciseWheelRotation` |
| `LxKeyEvent` | `type: Typed/Pressed/Released`, `keyCode` (AWT VK_ code), `keyChar`, `keyLocation`, `modifiers` |
| `LxInputMethodEvent` | `type: Text/Caret`, `text, committedCount, caretPos, selectPreviousCharacter` (IME, macOS dead keys) |
| `LxWindowEvent` | `type: Activated/Deactivated/GainedFocus/LostFocus/Closing`, `oppositeWindow: LxWindowRef?`. The client maps AWT id 202 (WINDOW_CLOSED) to `Closing` (`EventsKt.toLx`), and `windowClosing` itself does nothing |

Key detail: `LuxFrontendPanel.processKeyEvent` sends keys to the backend; the backend sends keys it did not use back in `unprocessedKeyEvents` so the client can run its own shortcuts. Typing text is `Pressed` + `Typed(keyChar)` + `Released`. Clipboard: on a paste shortcut the client first calls `FrontendCopyPasteManagerHolder.syncClipboard()` (**Seen in code**, `syncClipboardIfNeeded`), a separate clipboard channel that IDEate would also need for Ctrl+V.

### Backend receive side

**Seen in code**, `LuxComponentAdapter` advises `model.position`, `.size`, `.events`, `.visible` in `<init>`. `receiveEvent` ignores mouse events for modally blocked windows (`modalBlocked`, set by `LuxWindowPeer.setModalBlocked`), converts with `UnattendedHostEventHelper.convertLxEvent` into AWT events and dispatches on the EDT with the client's `ClientId`. `receiveSize(w, h)` resizes the Swing content pane and repaints.

## 3. Lifecycle of one dialog (Rename on a file)

### Backend wiring (Seen in code)

- `intellij.platform.backend.split.xml` registers `RemoteToolkit` as `ClientToolkit` (no client filter), `LuxHostConnection` with `client="remote" preload="true"`, and `RemoteGraphicsEnvironment` with `client="controller"`.
- `RemoteToolkit.createDialog(Dialog)` builds a `LuxDialogPeer`, so any `java.awt.Dialog` shown while a remote client's `ClientId` is current gets a Lux adapter. `LuxDialogAdapter.create` makes `LxDialog(modality = dialog.isModal(), undecorated, owner = wrapWindowToLxRef(dialog.owner), ..., layoutSource = Backend, focusableOnFrontend = true, info = LxDialogInfo(creationStack, className))`.
- `LuxHostService.register` assigns the integer id; `bindIfPossible` binds only when `LuxHostConnection.getControllerInstance()` exists **and** `getAttached()` is true; otherwise it returns and the window just waits unbound.
- `LuxHostConnection.bindAdapter` adds the model to `luxModel.components[id]` (`addUnique(map, lifetime, id, model)`) and returns a `LuxGraphicsAdapter` bound to `componentModel.graphics`.
- `shouldBindAdapter` skips `IdeFrameImpl` and windows registered with `doNotLuxifyWindow`.

### The gate you must open first (Seen in code)

`LuxHostConnection.attached` becomes true only in `RemoteGraphicsEnvironment.Companion.handleNewSession`, after `remoteControl.graphicsDevices` is set by the client. `remoteControl` is `RemoteControlSession`, which lives at `CodeWithMeUnattendedHostControlModel.session: RdProperty<RemoteControlSession>` (a root ext, `getCodeWithMeUnattendedHostControlModel(IProtocol)`). Members of `RemoteControlSession` the client fills: `graphicsDevices: Prop<List<RemoteGraphicsDeviceModel(idString, configurations: List<RemoteGraphicsConfigurationModel>, defaultConfigurationIndex)>>`, `textRenderingProperties: Prop<RemoteTextRenderingProperties(uiAntiAliasingMode, uiFractionalMetrics, editorAntiAliasingMode, editorFractionalMetrics, fontMetrics)>`, `clientOsInfo: Prop<ClientOSInfo(os, waylandToolkit, windowRoundedCorners)>`, plus `additionalFontMetrics`, `subscribeOnWindowDeactivation`, `windowDeactivated`, `closeAllPopups`.
**Inferred**: a split JetBrains Client is the backend's `CONTROLLER` client type, because every Lux class is gated by `session.isController()` and the Lux client listener is in the split frontend. `AttachConnection` also logs "Lux can not work only with single controller connection" for anything else.
**Unknown**: whether IDEate already publishes `RemoteControlSession`. If not, Lux dialogs will never be bound, and the dialog stays unbound on the backend.

### Order of events for a modal Rename (Inferred from the classes above; not recorded)

1. Client sends `RdCallActionRequest` for `RenameElement`; the backend EDT runs the action and builds a `DialogWrapper`.
2. `DialogWrapper.show()` makes a Swing `JDialog`; `RemoteToolkit.createDialog` creates `LuxDialogPeer`; `LuxHostService.register` allocates id N.
3. `LuxDialogPeer.setVisible` -> `LuxWindowPeer.setVisible` first sends position and size, then the adapter's `bindTo` adds `components[N] = LxDialog(modality=true, owner=LxIdeFrameRef)`.
4. Backend sets `title`, `resizable`, `preferredSize`, `minimumSize`, `visible=true`, and starts sending `graphics.drawOperations` batches (first one is the full dialog; later ones repaint caret, focus, hover).
5. Client creates a modal window of that size, sets `size`, `position`, `graphicsConfiguration`, and replays operations.
6. The user types: client sends `LxKeyEvent`s (and clicks `LxMouseEvent`s) on `components[N].events`; backend turns them into AWT events; the text field changes and the caret and text are redrawn as new operations.
7. OK click: `LxMouseEvent` Pressed/Released at the OK button's pixel, backend `DialogWrapper.doOKAction` runs, disposes the JDialog, `LuxWindowPeer.dispose`, the adapter lifetime ends and `components[N]` is removed (or `visible=false`). The client window closes when the map entry goes away (`bindWindow` registers `lifetime.onTermination { window.dispose() }`; the frontend dialog does `close(0)` on lifetime end in `LuxFrontendDialogBase`).
8. The rename (a PSI write command) then runs on the backend and results arrive through the normal project view / documents channels.

Release of the backend EDT: **Inferred**. The modal Swing dialog is dismissed on the backend by the backend's own `DialogWrapper` code when OK/Cancel/Esc arrives as an AWT event. The client never sends an explicit "result".
How the client knows it is modal: **Seen in code** `LxDialog.modality`.

### If the client never shows it

**Seen in code**: nothing times it out. `LuxHostService.bindIfPossible` just returns when no attached controller exists; there is no timer or fallback in the Lux classes. **Inferred**: the backend modal loop (`DialogWrapper.show` pumping `LaterInvocator`) keeps waiting, which blocks that action. Only Esc/OK events or the client disconnecting end it. This matches the earlier note that greying the action out is safer than running it blind.

## 4. The older route: BeDialog and BeControl

### Model (intellij.rd.ide.model.generated.jar, package `com.jetbrains.ide.model.uiautomation`)

`UIAutomationInteractionModel` (root ext, `protocol.uiAutomationInteractionModel`): `activeToolWindows: RdList<BeToolWindow>`, `activeDialogs: RdList<BeDialog>`, `modalCWMDialogs: RdList<BeDialog>`, `hintCollection`, `activePopups: RdMap<Int, BeAbstractPopup>`, `beBalloons`, `contextMenu: Prop<BeActionMenu>`, `currentPopup`.

`BeDialog extends BeControlWithValidation`: `projectId, content: Prop<BeControl>, modality (DialogModality.MODAL), title, size, minSize, maxSize, isResizable, margin, eventLog, layoutPersistenceMode, controlBox, style (SIMPLE|THEMED), validationBehavior, result: Prop<DialogResult> (OK, CANCEL, CLOSE, NONE), acceptButton, cancelButton, helpButton, buttons: Prop<List<BeAbstractButton>>, extraFooterContent, notification, activated, deactivated, loaded, resize`.

**BeControl is a semantic widget tree.** `BeControl extends ProtoViewModel`: `enabled, controlId, uniqueId, dataId, focusable, tooltip, focus (signal), visible`. Concrete types (all **Seen in code**): `BeLabel(text)`, `BeDslLabel(text, linkClicked)`, `BeTextBox`/`BePasswordTextBox`/`BeComboBox` (extend `BeControlWithEditableText: text, placeholder, leftActions, rightActions` and validation), `BeTextArea(text)`, `BeCheckbox(description, property, isThreeState)`, `BeRadioButton`, `BeButton(action)`/`BeAbstractButton(content, style)`, `BeLink`, `BeGroupBox`, `BeGrid`/`BeGridLayoutPanel`/`BeMigLayout`, `BeSpacer`, `BeSeparator`, `BeTabbedControl`, `BeTreeGrid`, `BeHtmlViewer`, `BeValidationLabel`, `BeEditor*`, toolbars and menus. The reply path is the property itself (`text`, `property`, `selectedIndex` are `RdProperty`, writable from the client) plus `BeControlWithEvents.eventLog` (`BeEvent(type, hierarchyControlId, source, data)`, `BeKeyEvent`). The client side is `BeDialogHostProtocolComponent` and `BeDialogWrapper` (`intellij.rd.client.jar`, `com.jetbrains.rdclient.ui.bindableUi`).

### Who produces BeDialog in 263 (Seen in code, `intellij.platform.backend.split.jar`)

`BackendDialogWrapperPeerFactory` replaces `DialogWrapperPeerFactory`. In every `createPeer` overload:

```
if (ClientId.isCurrentlyUnderLocalId() || AppMode.isRemoteDevHost())  -> super.createPeer(...)      // normal Swing peer
else                                                                    -> RdDialogWrapperPeer(session, rdProjectId, wrapper, modality)   // BeDialog
```

`HostDialogsRegistry.show` (used by `RdDialogWrapperPeer`) converts the dialog with `DialogConverter.tryGetDialogModel`; for modal dialogs with a non-controller session it either clicks OK silently, cancels, refuses, or pushes `RdModalDialogStep(DialogComponentId(controlId))` and adds the dialog to `modalCWMDialogs`; otherwise it adds to `activeDialogs` and spins `IdeEventQueue.flushQueue()` until `actionCallback` completes (removal from `activeDialogs` completes it). Per-dialog-class handlers come from the extension `com.intellij.rdserver.modalDialogAction` (`RdModalDialogAction`, e.g. `MemberChooserModalAction`).

**Inferred**: `AppMode.isRemoteDevHost` is true for the remote-dev-server backend (source: `AppMode.isRemoteDevHost = knownCommand.isRemoteDevHost`, `platform/core-api/src/com/intellij/idea/AppMode.java`), so on a split backend ordinary `DialogWrapper`s take the first branch, the normal Swing peer, and end up in Lux through `RemoteToolkit`. The `RdDialogWrapperPeer`/BeDialog route is the Code With Me guest path. So **Rename, New file, Delete confirmation are almost certainly Lux pixels on your backend**, not BeDialog. Not verified at run time; this one fact decides the plan, so it should be the first thing to record.

### Where BeControl still shows up inside Lux

`lux.localComponents` defaults to `beControls`. **Seen in code**: `LuxBackendLocalBeControlBinder.accept` swaps in an `LxLocalBeControl(toBeModel(component))` only for (a) components flagged `RemoteTransferUIManager.setWellBeControlizable` (the only caller in 263 is `SplitComponentId`), (b) `ConsoleViewImpl`, (c) `EditorComponentImpl` **only when neither the window nor an ancestor is modal** and not inside a scroll pane or diff, (d) JCEF browser panels. So a modal Rename dialog contains no `LxLocalBeControl`: even its name field (an `EditorTextField`) is painted as pixels. Non-modal Lux windows with editors or consoles will have local BeControls (this is where Find Usages options is a registry exception, `lux.localComponents.becontrolizationException = findUsages,cmake-console`).

## 5. Minimal plan for IDEate

### Which route first

Lux. The BeDialog route is easier to render but, per section 4, is not what a remote-dev backend produces for common dialogs. Supporting it first would leave Rename/New/Delete still greyed out.

### Messages to bind

| # | Model / call | What IDEate does |
|---|---|---|
| 1 | `protocol.codeWithMeUnattendedHostControlModel.session` (`RemoteControlSession`) | Set `graphicsDevices` (one `RemoteGraphicsDeviceModel` with one `RemoteGraphicsConfigurationModel`: bounds of the phone window, `defaultTransform`/`normalizingTransform` as identity or the density scale), `clientOsInfo`, `textRenderingProperties`. This attaches Lux on the backend. Check first whether IDEate already sets it. |
| 2 | `protocol.luxModel.components` (`RdMap<Int, LxAbstractComponent>`) `view` | On add: if `LxDialog` (or `LxWindow` with `typePopup` for dropdown popups later), open a Compose dialog. On remove: close it. Ignore `LxFrame` with `isMainWindow`, and `LxPanel` for now. |
| 3 | `component.title`, `.resizable`, `.visible`, `.preferredSize`, `.minimumSize`, `.position`, `.cursor` | title bar text; show/hide; dialog size from `preferredSize` |
| 4 | `component.size`, `.position`, `.graphicsConfiguration` (client writes) | write the canvas size in backend pixels as soon as the dialog opens; write a position; write graphics config |
| 5 | `component.graphics.drawOperations` | decode and replay (below) |
| 6 | `luxModel.cleanImagesFromCache` | drop cached images |
| 7 | `component.events` (client writes) | send mouse and keys (below) |
| 8 | `component.unprocessedKeyEvents`, `component.requestRepaint` | optional; call `requestRepaint` once after open if the first frame is missing |
| 9 | `clipboard` sync for paste | separate channel, skip for the first version |

### Smallest renderer

Keep a state object per component: paint (colour), font, clip, transform, stroke, composite. For each `LxDrawOperation` apply `stateChanges`, then run the command.

- Commands for Rename/New file/Delete confirmation: `LxFillShapeCommand`, `LxDrawShapeCommand`, `LxDrawTextCommand`, `LxDrawGlyphTextCommand` (or `LxDrawGlyphShapeCommand` as a path), `LxDrawImageCommand` (icons: `LxCacheableImage`/`LxCachedImage`/`LxNonCacheableImage`/`LxDiffImage` with PNG), `LxCopyAreaCommand`, `LxClearStateCommand`.
- State changes: paint (colour plus linear gradient), font (`LxPlainFont`), clip (rect), transform, stroke (`LxBasicStroke` width and dash), composite (alpha), and text aliasing (can be ignored).
- Shapes: rect, round rect, line, ellipse, path (covers polygon/arc/curves if you convert them to a path). Rounded buttons and focus rings are round rects.
- Text: draw `text` at `(x, y)` (baseline) with font size, then scale horizontally to the given `width`.
- Mouse: map Compose pointer to `LxMouseEvent` (Moved, Pressed, Released, Clicked with `clickCount=1`, button 1, `modifiers=0`). For touch, send Moved before Pressed so the backend sees hover on the button.
- Keys: hardware keys map to AWT VK_ codes (Enter=10, Esc=27, Tab=9, Backspace=8, arrows 37-40, Ctrl-A etc. via modifiers). Soft-keyboard text: send `LxKeyEvent` Pressed+Typed+Released per character (`keyCode` unknown for most characters is allowed as `VK_UNDEFINED=0`, **Inferred**; verify).
- Close: send `LxWindowEvent(Closing)` when the user closes the Compose dialog, and also fire Esc.

Types you can skip: gradients beyond linear, `LxTexturePaint`, `LxXorComposite`, `LxCustomStroke`, `LxCustomCursor`, `LxInputMethodEvent` (until non-ASCII input), `LxLocalBeControl`, popups, `LxFrame`.

### Cost and risks

- Roughly: ~40 rd types to register (the generated model already exists in the jar you load), one Compose `Canvas` renderer (a few hundred lines), event mapping. The renderer is the bulk. Cheaper than a full widget layer because no layout code is needed.
- Fallback that is much cheaper but weaker: **Inferred** a "text only" mode that ignores shapes and only draws `LxDrawTextCommand` strings over a plain background. It would show labels and the typed name but not buttons; clicks still work if you also keep the pixel coordinates, so you would have to draw at least fills and strokes. Not recommended beyond a spike.

### What cannot be known without a recorded live session

1. Whether Rename, New > Java Class and Delete really arrive as `LxDialog` (and not `BeDialog` or the split "SplitComponent" path). Record `luxModel.components` and `uiAutomationInteractionModel.activeDialogs/modalCWMDialogs` during one run.
2. Whether the backend needs `RemoteControlSession.graphicsDevices` from IDEate before it binds, and which values (scale, bounds) give a correctly sized dialog.
3. Real operation sequence and size: first batch, caret blink rate, hover redraws, whether `size` must be written before the first draw batch.
4. Whether closing the client window sends `LxWindowEvent(Closing)` and whether the backend then cancels the dialog, or whether only Esc/key events do.
5. Which `keyCode` values the backend needs for typed characters (for example whether `Typed` alone fills a text field).
6. What happens to the backend EDT if the client disconnects mid-dialog (no code path found; likely the dialog is disposed with the session lifetime, `LuxHostConnection.getLifetime`).
7. Clipboard sync (`FrontendCopyPasteManagerHolder`) for paste into the name field.
8. Whether the UI thread on the backend needs focus events (`LxWindowEvent GainedFocus`) before key events are accepted; `LuxFrontendDialog` sends them automatically from Swing window focus, a Compose client must send them itself (**Inferred**).

## Key files

- Models: `/Users/safeuq/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.split.protocol.jar` (`LuxModel`, `Lx*`, `RemoteControlSession`, `RemoteGraphics*Model`); `lib/intellij.rd.ide.model.generated.jar` (`BeDialog`, `BeControl*`, `UIAutomationInteractionModel`).
- Frontend: `plugins/remdev-plugin/lib/modules/intellij.platform.frontend.split.jar` -> `com.jetbrains.thinclient.lux.*` (`LuxClientService`, `LuxEventSynchronizer`, `swing/LuxFrontendDialog*`, `adapter/LuxFrontend*Adapter`, `rendering/LuxRenderer`).
- Shared event conversion: `plugins/remdev-plugin/lib/modules/intellij.platform.split.jar` -> `com.jetbrains.rd.platform.codeWithMe.lux.util.EventsKt`.
- Backend: `plugins/remdev-plugin/lib/modules/intellij.platform.backend.split.jar` -> `com.jetbrains.rdserver.lux.*` (`toolkit/RemoteToolkit`, `services/LuxHostService`, `services/LuxHostConnection`, `adapter/Lux*Adapter`, `awt/graphics/RemoteGraphicsEnvironment`, `awt/pipe/*`, `local/LuxBackendLocalBeControlBinder`) and `com.jetbrains.rdserver.ui.dialogs.*` (BeDialog route).
- Decompiled output: `/private/tmp/claude-501/-Users-safeuq-Files-XFlow-Claude/9a9f1a88-f9f2-49ac-8e20-09842dfad5e9/scratchpad/dec-lux/out/`.

## 6. Recording spike (2026-11-17)

A throwaway desktop build attached Lux and logged everything for Rename on `smoke.iml` (isolated Mac backend,
263.7307.200). The build wasn't kept; this is what it showed.

**Attach.** Setting `codeWithMeUnattendedHostControlModel.session.graphicsDevices` to one device was enough:
`RemoteGraphicsDeviceModel("ideate", [RemoteGraphicsConfigurationModel("ideate", LxRect(0, 0, 1200, 800),
identity, identity)], 0)` with identity = `LxAffineTransform([1, 0, 0, 1, 0, 0])`. The backend creates the
session; the client fills it once it appears.

**Timeline of one Rename** (ms after the dialog appeared):

| ms | What |
|---|---|
| −500 | `RdCallActionRequest(RenameElement)` sent (the item is greyed in the menu; the spike called it) |
| 0 | `luxModel.components[2] = LxDialog`, `modality = true`, `layoutSource = Backend`, info `className = "MyDialog"` |
| 0–4 | `title = "Rename"`, `position = (333, 257)`, `preferredSize = 358 × 254`. The spike wrote `size` = preferred size, a position and `graphicsConfiguration` back |
| 82–164 | `visible = true`; the first full frame: 57 operations (42 fill shape, 9 draw text, 5 draw image, 1 draw shape) and the same 57 again a few times |
| ~170 | small updates (2–8 operations) |
| 2 000 | spike sent `LxWindowEvent(Activated)`, `LxWindowEvent(GainedFocus)` (opposite window null) |
| 2 080 → | from then on a caret repaint every 500 ms (5 operations, texts `smoke`, `.iml`). **Before the focus events there was none: the client must send them** |
| 3 600 | `LxKeyEvent(Pressed, 88, 'x', 1, 0)`, `(Typed, 0, 'x', 0, 0)`, `(Released, 88, 'x', 1, 0)`; next frame draws `x`, `.iml`. The name was selected without its extension, as in IntelliJ, and typing replaced it |
| 5 600 | Escape as `LxKeyEvent(Pressed, 27, '\u001b', 1, 0)` + `Released` |
| 5 654 | `visible = false`, then the map entry was removed: the backend closed its dialog itself (no rename happened) |

**What the operations carry** (first frame, `LxDrawOperation(command, stateChanges)`):

- Colors are IntelliJ theme keys with fallbacks, not RGB: `LxColorPaint(NamedColor("Panel.background"))`,
  `LxGradientPaint(NamedColor("Button.default.startBackground", fallback "Button.darcula.defaultStartColor"
  → RgbaColor(56, 79, 107)), …)`. The client resolves them from its theme, so IDEate resolves them from the
  Islands Dark keys in `ui/theme` (with the `RgbaColor` fallback when a key is unknown).
- Fonts by name: `LxPlainFont("Inter", style 0, size 13)`.
- Text with its box: `LxDrawTextCommand("Rename file 'smoke.iml' and its usages to:", x = 0, y = 13, width = 255.0,
  height = 15.7)`, also "Refactor", "Preview", "Cancel", "Scope:", "Search in comments and strings",
  "Search for references".
- Shapes: `LxRectShape`, `LxRoundRectShape(arcWidth = 8)` for buttons; each operation usually brings its own
  `LxTransformStateChange` (component offset) and `LxClipStateChange`.
- Images: `LxCacheableImage` (data, sent once) and `LxCachedImage` (a reference to one sent before).

**Answered** (section 5's list): 1 Rename arrives as `LxDialog`; 2 `graphicsDevices` is needed and one plain
device works; 3 the order above, and writing `size` right away is accepted; 5 Pressed/Typed/Released with AWT
codes types into the field; 8 focus events are needed (no caret before them). **Still open:** 4 closing the
window (`LxWindowEvent(Closing)`), 6 a disconnect mid-dialog, 7 clipboard paste, and New/Delete dialogs (same
route expected).

## 7. The painter (2026-11-17)

`ui/lux/LuxPainter.kt` replays `drawOperations` onto a bitmap; `ui/lux/LuxImages.kt` keeps the images. The dialog
window, the attach and the events are the UI session's (`session/LuxDialogs.kt`, `ui/lux/LuxDialogView.kt`).

**Contract.** `LuxPainter(images, fonts, fontFamilyResolver, density)`, one per dialog, on the UI thread:
`paint(canvas, scale, operations)` draws one batch onto the dialog's bitmap, which is backend pixels × `scale`.
`LuxImages()`, one per session: `remove(ids)` for `cleanImagesFromCache`, thread-safe. PNGs are decoded by the
platform's `pngDecoder` (set at startup: Skia on the desktop, `BitmapFactory` on Android).

**Semantics, from the JetBrains Client's `LuxRenderer` and `LuxGraphicsState` (decompiled):**

- **State.** Each operation's state changes apply, then its command draws. The state lasts across batches
  until `LxClearStateCommand`, which resets it. The starting paint is yellow (`JBColor.YELLOW`), so a missing
  paint shows.
- **Transform.** `LxTransformStateChange` replaces the whole transform (`setTransform`); the matrix is AWT's flat
  order `m00 m10 m01 m11 m02 m12`.
- **Clip.** `LxClipStateChange` is in device space (the client maps it back through the inverse transform), so
  a later transform doesn't move it. A null shape removes the clip.
- **Text.** `LxDrawTextCommand(text, x, y, width, height)`: drawn at the baseline `y`. The client's default
  `lux.fonts.transform.bounds=shrink` scales text down only when it measures wider than the backend's box;
  IDEate does the same sideways, with 0.3 px of slack.
  - Fonts: a name with "Mono" in it uses `fonts.editor`, anything else `fonts.ui`.
  - AWT style bits: 1 bold, 2 italic. A sub-family (`SemiBold`, `Medium`, …) refines the weight.
- **Images.** `LxDrawImageCommand(image, dst, transform)`: the command's transform is added, then the image is
  drawn scaled into `dst`.
  - `LxCacheableImage` stores by id, `LxCachedImage` reads.
  - `keepForDiff` keeps the image as the dialog's diff base. `LxDiffImage(x, y, content)` copies the base with
    the content replacing pixels at (x, y), and becomes the new base (`ImageDiff.applyTo`).
- **Shapes and paints.**
  - Shapes: rect, round rect (AWT arcs are diameters), ellipse, line, arc (`Arc2D.OPEN`, angles negated for
    Compose), polygon, path (segment types 0–4, `WIND_EVEN_ODD` = 0), quad and cubic curves.
  - `LxGradientPaint` becomes a two-color linear gradient (cyclic = mirror), `LxLinearGradientPaint` a
    multi-stop one.
  - Stroke: width, cap, join, miter, dash. `LxAlphaComposite` gives alpha and the blend mode by AWT rule.
- **Colors.**
  - `NamedColor(name, fallback)` resolves through `ui/theme/ThemeKeys.kt`: the keys Islands Dark sets, then its
    `ui."*"` wildcard by suffix (`Panel.background` → `*.background` = tool-window-bg `#191A1C`), then down the
    fallback chain.
  - `RgbaColor` is used as is; `DualColor` gives its dark color.
  - All the keys of the recorded Rename frame resolve this way (`Button.default.startBackground` → `#3871E1`,
    `Component.borderColor` → `#40434A`, `Label.foreground` → `#D1D3D9`, …).

**Not drawn**, one `[lux] not drawn: …` line each:
- `LxCopyAreaCommand`: it needs the backing bitmap, which a `Canvas` doesn't expose. Lux uses it to scroll; the
  recorded Rename frames had none.
- `LxRadialGradientPaint`, `LxTexturePaint`, `LxXorComposite`, `LxCustomStroke`.
- A linear gradient's own transform.
- `LxBackgroundColorStateChange` (Lux sends clears as fills) and `LxAliasingStateChange` (text is always
  antialiased) are read and ignored.

**Checked.**
- `desktop/src/test/…/LuxPainterTest.kt` (6 pixel tests):
  - a default button's gradient from its theme key, under a transform at scale 2;
  - a device-space clip that a later transform doesn't move;
  - text shrunk into the backend's box;
  - an unknown key's fallback;
  - clear state;
  - an image cached, reused by id and removed.
- `CLIENT_GALLERY=<out.png>` also writes `<out>-lux.png`: a Rename dialog from hand-made operations with the
  recorded texts, text boxes, keys and button geometry (`desktop/…/LuxGallerySample.kt`).


## 8. IDEate shows backend dialogs (2026-11-17)

`session/LuxDialogs.kt` and `ui/lux/LuxDialogView.kt`, on the painter from section 7.

- **Attach:** once the backend creates `codeWithMeUnattendedHostControlModel.session`, IDEate sets
  `graphicsDevices` to one 1920 × 1200 screen at scale 1, so a backend pixel is a dp.
- **Which windows:** every `LxDialog`, and every `LxWindow` that isn't a frame (popups; none seen yet, see
  New below).
  - On the first `preferredSize`, IDEate writes `size` back, then a position and
    `graphicsConfiguration`.
  - On `visible = true` it sends `LxWindowEvent(Activated)` and `(GainedFocus)`. Without them there's
    no caret and no typing.
- **The window:** a Compose `Dialog` with IntelliJ's title row (left out for popups and for undecorated
  dialogs such as IntelliJ's alerts, which paint their own title) over the backend's content.
  - The content is a bitmap at screen density that the painter replays batches into, in order, on
    the UI thread.
  - A dialog wider than the screen is scaled down to fit, and the input is scaled back.
- **Input:**
  - Pointer: `LxMouseEvent` Moved / Pressed / Released / Clicked, button 1, with
    `BUTTON1_DOWN_MASK` while pressed and the click count. A finger sends Moved before Pressed, so
    the button under it is hovered first.
  - Hardware keys: AWT key codes plus Typed for characters; a shortcut held with Ctrl or ⌘ types
    nothing.
  - The soft keyboard goes through `EditorTextTarget` (`LocalEditorTextInput`): each character as a
    key stroke, Select all as Ctrl/⌘+A.
  - Back, or a click outside a non-modal one, sends `LxWindowEvent(Closing)` and Escape. The backend
    closes its own dialog, and the window goes when `components` drops it.
- **Un-greyed:** the dialog actions in `ActionMenus` (Rename, Delete, Reformat Code, Optimize Imports,
  Module Settings, …). Still greyed: what opens a popup, frame or tool window (Find Usages, Find/Replace
  in Files, Show History, Compare, Go To, Analyze, Run). New is un-greyed in section 10.

**New ▸ File, Java Class, …** don't use Lux. Their name popup arrives as
`uiAutomationInteractionModel.activePopups[1] = BeAbstractPopup`, the semantic BeControl popup route, and
`NewFile` returns at once (the popup isn't modal). IDEate shows them since section 10.

**Tested** against the isolated Mac backend:

| Where | Dialog | Result |
|---|---|---|
| desktop | Rename on `smoke.iml` | drawn like IntelliJ's; typing `tested` gave `tested.iml`; a click on Cancel closed it, nothing renamed |
| desktop | Delete on `.idea` (alert) | drawn; Escape cancelled, `.idea` kept |
| emulator | Rename on `smoke.iml` | drawn at density; Gboard typing `xy` gave `xy.iml`; a tap on Cancel closed it |
| emulator | Safe Delete on `smoke.iml` | drawn; Back closed it, the file kept |

Not done yet: ~~a dialog's own popups~~ (done, see section 9), the backend clipboard for paste into a dialog field (pasted text is typed key by key), and a soft keyboard that
opens only when the dialog has a text field (today it opens for every dialog on Android).

## 9. A dialog's own popups (2026-11-17)

Tested with Rename on the class `Other` (Project tree, long press, Rename…) on the emulator against the
isolated backend. Its Scope combo opens a list window.

| What arrives | Value |
| --- | --- |
| The dialog | `LxDialog`, owner `LxIdeFrameRef`, `position` (282, 242), 459 × 283 |
| The Scope list | `LxWindow` (`HeavyWeightWindow`), `typePopup = true`, owner `LxNonLuxWindowRef`, `position` (290, 467), 423 × 268, `focusableWindowState = true` |

The backend places windows on the screen IDEate reported. The list's offset from the dialog, here (8, 225),
puts it just under the combo. JetBrains Client does the same: `LuxFrontendWindowAdapter` moves each window to
its `position`, and `unwrapLxRef` gives a `LxNonLuxWindowRef` popup the focused window as parent.

Three things kept a tap on a list item from doing anything, found one at a time with the backend's trace log
(`-Didea.log.trace.categories=#com.jetbrains.rdserver.lux.util.UnattendedHostEventHelper`):

1. **`xAbs` / `yAbs`** were the window-local point. They are now the window's `position` plus the point, e.g.
   (50, 165) in the list is (340, 632) on the screen.
2. **No `Entered`.** A finger has no hover, so the backend got `Pressed` in a window the pointer had never
   entered, and Swing ignored it. A mouse (`adb shell input mouse`) sends `Entered` first, and its drag worked.
   A touch press now sends `Entered` first, and `Exited` after the release.
3. **One move is not a move.** IntelliJ's list popups take the first `MOUSE_MOVED` only as a start
   (`ListPopupImpl.isMouseMoved` records `myLastMouseLocation`), so the item under the finger was never
   selected, and the release picked nothing. A touch press now sends two moves, 1 px apart along the row, e.g.
   (52, 140) then (53, 140).

The backend trace for a tap on "Open Files" after the change:
`MOUSE_ENTERED (53,140)`, `MOUSE_MOVED (52,140)`, `MOUSE_MOVED (53,140)`, `MOUSE_PRESSED`, `MOUSE_RELEASED`,
`MOUSE_CLICKED`, `MOUSE_EXITED`, then Scope reads "Open Files".

**How IDEate shows a popup:** over the top dialog, inside its window, as a Compose `Popup` at the backend's
offset from the dialog, scaled like the dialog. A popup with no dialog open still gets a window of its own.
A popup gets no `Activated` / `GainedFocus` (a real OS leaves the focus with the dialog, so the combo keeps
handling keys; Down moved the list's selection that way) and opens no soft keyboard. Back or a tap outside
sends it `Closing` and Escape.

Checked on the emulator: the list opens under the combo; a tap on "Open Files" sets Scope to it; Cancel
still closes the dialog and nothing is renamed.

## 10. Backend BeControl popups: New ▸ File and Directory (2026-11-17)

New ▸ File, Directory and the other name popups now show (`session/BackendPopups.kt`, `ui/popup/BackendPopupView.kt`), and New is no longer greyed. One popup arrives like this (recorded with New ▸ File on `empty/`):

```
activePopups[1] = BeAbstractPopup(
  title   = BeLabel("New File"),
  content = DefaultPopupContent(BeStyleControl(margin bottom 8 →
              BeBehaviorControl(behavior = [BeShortcutListener(["pressed ENTER"])] →
                BeTextBox(text = "", placeholder = "Name")))),
  settings = (isFocused = true, closeOnEnter = false, cancelKeyEnabled = true, cancelOnClickOutside = true, modality = MODAL),
  context  = BePopupPositionActiveWindow(position = TOP))
```

| Step | Client does (as JetBrains Client's `PopupViewControl`) |
|---|---|
| `loaded` fires | read the tree then: the Enter listener is added between the popup appearing and `loaded` (reading on add found `behavior = []`) |
| typing | `BeTextBox.text.set(...)` on every change |
| Enter | fire each `pressed ENTER` listener's `reaction` with `BeEmptyData()`; the backend creates the file and sets `result = CLOSE` |
| Escape, Back, a tap outside | `result = CANCEL`, then `CLOSE` |

**The "already exists" hint is a second popup with Lux inside.** Enter on a name that exists opens `activePopups[2]` with `isFocused = false` and content `BeneluxView(luxId = 10)`: a BeControl that only names an `LxPanel` the backend paints. JetBrains Client hosts it in `BeneluxViewControl` (`intellij.platform.frontend.split.jar`, `com.jetbrains.thinclient.unattendedHost`). The panel draws only after the client does the same handshake as for a Lux window:

| Panel property | Set to |
|---|---|
| `graphicsConfiguration` | IDEate's one screen (`LuxDialogs.CONFIGURATION`) |
| `position` | `(0, 0)` |
| `visible` | `true` |
| `size` | each `preferredSize` the backend sends, e.g. `291 × 25`, even though `layoutSource` is `Backend` (the client sets it unconditionally) |

Its colors are `ValidationTooltip.errorBackground` / `errorBorderColor`, which `ThemeKeys` now resolves as Islands Dark does (`#56272B` / `#80383E`; without them the painter used the light fallback, a pale pink). IDEate shows the hint under the name popup, and cancelling the name popup cancels the hint too (the backend doesn't close it on its own).

**Tested** on the emulator against the isolated Mac backend: New ▸ File `notes.txt` and New ▸ Directory `docs` in `empty/` created both; a second `notes.txt` showed "A file with the name 'notes.txt' already exists"; one Back (after the keyboard closed) cancelled both popups. The field takes focus, so the soft keyboard opens.
