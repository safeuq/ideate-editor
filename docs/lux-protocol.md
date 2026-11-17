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
