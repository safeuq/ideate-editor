# Compose client

A native client for IntelliJ's split mode (remote development), written with Compose. It runs on the desktop JVM and, as an Android app, on a phone ([`android-app.md`](android-app.md)); both use the `compose` module, which holds everything but the platform pieces. It connects to a backend and shows IntelliJ's Project tree and an editor with the backend's highlighting that you can type in, in IntelliJ's Islands Dark look ([`intellij-look.md`](intellij-look.md)). How the protocol works: [`split-protocol.md`](split-protocol.md), sections "Connection setup", "Our own client (step 2)" and "Editing (step 4)"; the edit messages in detail: [`edit-protocol.md`](edit-protocol.md).

| Folder | What | Platform |
|---|---|---|
| `compose/src/commonMain/kotlin/…/connection/` | join link, client certificate (BouncyCastle), TLS 1.3, DEFLATE frames, handshake, the rd wire | JVM and Android |
| `compose/src/commonMain/kotlin/…/session/` | rd protocol and the models the editor needs; exposes a `StateFlow<SessionState>` | JVM and Android, with the rd jars |
| `compose/src/commonMain/kotlin/…/ui/` | Project tree, editor, window layout; common Compose APIs only. Fonts, icons and the editor's text input come in through `LocalClientFonts`, `LocalIcons`, `LocalEditorTextInput` | JVM and Android |
| `compose/src/commonMain/kotlin/…/connect/` | the connect screen (Gateway's welcome window), its logic (`ConnectController`), history, the port scan, the folder chooser, and the two windows' content: `ConnectApp` (connections window) and `EditorApp` (editor window). Termux and where history is stored come in through the `Termux` and `StoredText` interfaces | JVM and Android |
| `desktop/` | the JVM launcher: `Main.kt` (no Termux; history in `~/.ideate/`) and `desktop/`: fonts and icons read from the JetBrains Client install, off-screen snapshots | desktop only |
| `android/` | the Android app: activity, `AndroidTermux` (RUN_COMMAND), history in shared preferences, fonts and icons from assets, soft keyboard | Android only |

One Gradle build with three modules (`settings.gradle.kts`):

| Module | Plugin | Depends on |
|---|---|---|
| `:compose` | Kotlin Multiplatform with two targets, `jvm` and `android` (`com.android.kotlin.multiplatform.library`). All code is in `commonMain`, tests in `jvmTest` | JetBrains Compose foundation 1.12.2, BouncyCastle, kotlinx-serialization-json; the protocol jars as `compileOnly` |
| `:desktop` | Kotlin JVM + Compose Desktop (`application`, `run`) | `:compose`, the protocol jars as they are, `compose.desktop.currentOs` |
| `:android` | Android application | `:compose`, the protocol jars patched for ART ([`android-app.md`](android-app.md)) |

How the shared code builds (checked 2026-11-16 with Kotlin 2.3.21, AGP 9.5.1 and 9.3.0):
- `ConnectController` moves session callbacks to the UI thread with `Dispatchers.Main`. Android gets it from `kotlinx-coroutines-android` (through androidx Compose); Compose Desktop doesn't bring `kotlinx-coroutines-swing`, so `:desktop` adds it (1.9.0, the version of coroutines-core). Without it the first port scan failed with `Module with the Main dispatcher is missing`.
- AGP is held at **9.3.0**, the newest version IntelliJ 2026.3's Android support imports. With 9.5.1 the IDE refused the project: "The project is using an incompatible version (AGP 9.5.1) of the Android Gradle plugin. Latest supported version is AGP 9.3.0". AGP 9.3.0 builds `compileSdk = 37` without a warning, and the APK runs as before (emulator, Mac backend, 2026-11-16 18:02 IST). Raise it when the IDE supports a newer one.
- Kotlin's docs say a source set can't be shared between a JVM and an Android target. With only those two targets, though, Gradle prints `compileCommonMainKotlinMetadata SKIPPED` and compiles `commonMain` once per target, each with its full platform API. `java.io.File`, rd and BouncyCastle work in `commonMain`. The IDE may still mark JDK calls there as unresolved.
- The protocol jars are `compileOnly` in `:compose` because each app needs its own copy at run time: the desktop the original Java 25 jars, Android the jars lowered to Java 17 with the `Unsafe` shim.
- On Android, `org.jetbrains.compose.foundation:foundation:1.12.2` resolves to `androidx.compose.foundation:foundation:1.12.1`, so the app still runs on androidx Compose.

## Run

Needs:
- Java 25 or newer, because JetBrains Client's protocol jars are Java 25 class files. IntelliJ's bundled JBR works.
- JetBrains Client **263.7307.200**, the same build as the backend. Gateway downloads it.

Machine-specific paths go in `local.properties` at the repo root (not committed). Gradle and the IDE's sync both read it:

```properties
sdk.dir=/Users/you/Library/Android/sdk
jetbrains.client.lib=/Users/you/Library/Caches/JetBrains/JetBrainsClientDist/JetBrainsClient-263.7307.200-aarch64.sit-…/IntelliJ IDEA.app/Contents/lib
```

`-Pjetbrains.client.lib=…` and the `JETBRAINS_CLIENT_LIB` variable also work; the order is `-P`, then `local.properties`, then the variable. The variable alone is not enough for the IDE: an IDE started from the Dock doesn't see shell variables, and without `sdk.dir` it offers to install an Android SDK although one is there.

```sh
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew :desktop:run                      # opens on the connect screen, like the app
./gradlew :desktop:run --args="'tcp://127.0.0.1:5990#jt=…&fp=…' [/absolute/path/on/the/backend/src/demo/Main.java]"
```

Hot reload ([Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload) 1.3.0) changes the running desktop app without a restart or a new connection:

```sh
./gradlew :desktop:hotRunAsync -PjoinLink='tcp://127.0.0.1:5990#jt=…&fp=…'   # hotRun does the same and waits
# edit a file in :compose or :desktop, then
./gradlew :desktop:hotReloadMain                                              # or the IDE's reload button
```

`hotRun` has no `--args`, so the link goes in the `joinLink` property. It runs on the JetBrains Runtime of `JAVA_HOME` (the build stops if that is not a JBR 25 or newer), not on the JetBrains Client runtime that `:desktop:run` uses. Composables and calls made after the reload use the new code. Code that runs once when the connection opens, such as most of `ClientSession`'s protocol binding, keeps the old behaviour until you reconnect. The app's output goes to `desktop/build/run/main/hotRun.stdout.txt`. `:desktop:hotMcpServer` starts an MCP server for agents that can screenshot the window, read its semantics tree, click by node and trigger a reload.

Without arguments the window opens on the same connect screen as the Android app, with two pages:

| Page | Desktop | Android |
|---|---|---|
| This computer / This phone | open ports 5990 to 5999 on 127.0.0.1; a tap on one asks for its join link | the same, plus Termux: running and recent projects, New Project, stop |
| Link | join link and file fields, history grouped by address | the same |

History (client identity per backend, so a used link works again after a restart) is in `~/.ideate/connection-history.json`, in a folder only the user can read; the last typed link and file are in `join-link.txt` and `open-file.txt` next to it. A link argument connects at once, as the app's `joinLink` extra does; the optional second argument opens that file at start, otherwise double-click a file in the tree. On a Mac with Gateway connected to the phone, port 5990 is Gateway's forward to the phone: connecting there takes the phone's backend over. Fonts (JetBrains Mono, Inter) and icons (`intellij.platform.icons.jar`) are read from the same JetBrains Client install. The UI density (compact or normal) follows IntelliJ IDEA's own setting on this computer, read from its `ui.lnf.xml`; `IDEATE_UI_DENSITY=normal` or `compact` overrides it ([`intellij-look.md`](intellij-look.md) section 11).

Debug aids, all off by default:

| Variable | Effect |
|---|---|
| `CLIENT_SNAPSHOT=/tmp/client.png` | renders the window off-screen into that PNG every 3 s (works with a locked or hidden screen) |
| `CLIENT_HIGHLIGHT_DUMP=/tmp/highlights.txt` | writes every highlight of the open file: range, layer, rd model class (`SyntaxHighlighterModel`, `FoldingHighlighterModel`, …), attribute keys, covered text |
| `CLIENT_EDIT_DEBUG=1` | prints every edit request, the backend's answer and whether it matched the local edit (`[edit] …` lines) |

The join link is the one the backend prints, with `0.0.0.0` replaced by its address. The join token in it works for one client only: the history entry (same identity) can use it again, any other client needs a restarted backend. Starting a backend on the Mac without disturbing a running IntelliJ: `split-protocol.md`, "Running a backend on the Mac for client work".

`./gradlew :compose:jvmTest` checks the frame encoding against the bytes in the protocol doc, and the version hashes against numbers recorded from the real client.

Code is formatted with Spotless: ktfmt in Google style (2-space indent, 100 columns) for `src/**/*.kt` and the Gradle scripts, trailing whitespace for `*.md`. `./gradlew spotlessApply` formats, `./gradlew spotlessCheck` fails on unformatted code. Plugin versions are in `gradle/libs.versions.toml`.

## Agent MCP server (2026-11-21)

The Compose Hot Reload MCP (`hotMcpServer`) clicks semantic nodes only. IDEate's backend-painted parts (the Run toolbar, Git windows, backend dialogs and their popups) are one drawn image with no nodes, so an agent needs input at points and hover. `AgentMcpServer.kt` (in `desktop`) is a small MCP server that does that on the running app, using `ComposeWindowInput`: it never moves the user's cursor and never takes keyboard focus.

Setting it up together with the Compose Hot Reload MCP, step by step: [`mcp/setup.md`](mcp/setup.md).

It is off by default. It starts only when the system property `ideate.agent.mcp.port` is set, and it listens on `127.0.0.1` only.

```sh
./gradlew :desktop:hotRunAsync -PjoinLink='tcp://127.0.0.1:5990#jt=…' -PagentMcpPort=5979   # :desktop:run takes -PagentMcpPort too
claude mcp add --transport http ideate-window http://127.0.0.1:5979/mcp
```

It speaks MCP over Streamable HTTP: `POST /mcp` with JSON-RPC 2.0, answered as `application/json`. A notification gets 202, `GET` gets 405, there are no sessions and no SSE. It drives the app's current editor window, or the connect window when no session is open.

Points are in the target window's content dp. `window` is optional: 0 is the main window (default), 1 and up are the popup windows in `popupWindows()` order (see `list_windows`). A popup has its own keyboard target: `press ESCAPE` with `window: 1` closes it, with the default it goes to the main window.

| Tool | Arguments |
|---|---|
| `list_windows` | none: index, kind (main or popup), title, bounds |
| `screenshot` | `window?`: PNG image content; no `window` gives one image per window |
| `move` | `x`, `y`, `window?` |
| `click` | `x`, `y`, `button` (left, right, middle), `count`, `window?` |
| `drag` | `fromX`, `fromY`, `toX`, `toY`, `window?` |
| `scroll` | `x`, `y`, `notches` (positive scrolls down), `window?` |
| `type` | `text`, `window?`: goes to what has Compose focus, so click it first |
| `press` | `key` (an AWT `VK_` name without the prefix: `ENTER`, `ESCAPE`, `F`), `modifiers` (shift, ctrl, alt, meta), `window?` |

A failure (no window, bad index, unknown key) is a tool result with `isError: true` and a plain message, not a protocol error. `AgentMcpServerTest` checks the JSON-RPC handling.

## Driving the desktop UI in tests (2026-11-20)

`desktop/src/test/.../DesktopDriverTest.kt` runs the real IDE screen (`ClientScreen` on a live `ClientSession`)
inside the test JVM with Compose's UI test layer (`compose.uiTest`, `runDesktopComposeUiTest`). It finds nodes
by text or content description, clicks them through Compose's own input, and renders PNGs. It uses no window
and no macOS accessibility, so nothing depends on focus or pixel positions.

```
DRIVER_LINK='tcp://127.0.0.1:5991#jt=…' DRIVER_OUT=/tmp/driver \
  ./gradlew :desktop:test --tests '*DesktopDriverTest*' --rerun-tasks
```

Without `DRIVER_LINK` the test is skipped. Against the Mac test backend on a small Gradle project it connected,
clicked the bar's "Gradle" button, then the toolbar's "Expand All", and wrote `1-connected.png`,
`2-gradle.png`, `3-expanded.png` and `semantics.txt` (every node: text, content description, actions) in
about 14 s. Steps name nodes as the user sees them: stripe buttons by their tool window title, toolbar buttons
by their action's text ("Sync All Gradle Projects"), tree rows by their text.

An icon without a content description can't be found this way; give it one (it also helps screen readers).
Compose Desktop also exposes the same semantics to macOS accessibility (the AX tree), which works for a real
window, but the in-process test is more reliable.

### Driving the real window (`WindowDriver`)

`desktop/src/test/.../WindowDriver.kt` opens the real window, set up as `Main.kt` does (`IdeateLocals`: a `ComposeWindow`, each popup in its own small OS window, fonts, icons), and drives it with synthetic AWT events. The input and screenshot code is `ComposeWindowInput` in the app's own sources (it takes any `java.awt.Window`, nothing IDEate specific); `WindowDriver` adds the IDEate window, `ClientSession` waits and the file output. Unlike `runDesktopComposeUiTest`, popups are real windows, so tests can check where they open. It never moves the user's cursor (no `java.awt.Robot`), never takes keyboard focus (the window is not focusable) and needs no macOS permission, so you can keep working while it runs.

| Variable | Meaning |
|---|---|
| `DRIVER_LINK` | join link of a backend, e.g. `tcp://127.0.0.1:5991#jt=…` (one use only: restart the backend before each run; without it the test is skipped) |
| `DRIVER_OUT` | folder for the screenshots (default `desktop/build/driver`) |

Use a backend port of your own (the self-test used 5991). Coordinates are in the window's content dp; the window is 1200 × 800 at (40, 40). `popupWindows()` lists the open popup windows; pass one as `inside =` to aim a click or key at it. Screenshots come from each window's Skia layer (`screenshot(name)` waits for every window's first frame, then writes `name-0.png` for the window and `name-1.png`, ... for popups) and work with the default Metal renderer, so `SKIKO_RENDER_API` is not needed.

```kotlin
val session = ClientSession(JoinLink.parse(link), initialFile = null)
session.start()
val driver = WindowDriver.open(session)
try {
  driver.waitUntil(description = "the project") { session.state.value.projectName != null }
  driver.click(75, 47) // the Project tool window's title: a menu opens in its own window
  driver.waitUntil(description = "a popup") { driver.popupWindows().isNotEmpty() }
  driver.screenshot("menu")
  driver.dismissPopups()
  driver.type("Q") // goes to whatever has Compose focus, so click it first
} finally {
  driver.close()
  session.stop()
}
```

`WindowDriverTest` is the full example (run, hover the Rerun button and check its tooltip window stays and does not blink, open a menu, click a tool window button, type into an editor):

```
DRIVER_LINK="$(cat link.txt)" DRIVER_OUT=/tmp/driver \
  ./gradlew :desktop:test --rerun --tests '*WindowDriverTest*'
```

Known limits: keys reach an editor only after a click gave it Compose focus (the window never has real focus). Escape and a click outside don't close a popup window, because a popup closes when its window loses real focus; `dismissPopups()` sends that event. A right click on a Project tree row opened no menu in the self-test.
