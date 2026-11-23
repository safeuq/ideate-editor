# Android app

The split-mode client as an Android app, named Ideate on the phone (applicationId `io.github.safeuq.ideate.client.android`). It uses the `:compose` module shared with the desktop client (connection, session, UI; see [`client.md`](client.md)) and adds only the Android pieces: the connect screen (link prompt, backends on this phone, history; Termux commands), fonts and icons from assets, and the soft keyboard hook. How it works and what was tested: [`split-protocol.md`](split-protocol.md), "The Android app (step 5)".

## Build

Needs:
- the Android SDK, as `sdk.dir` in `local.properties` (see [`client.md`](client.md), "Run") or `ANDROID_HOME`; AGP downloads platform 37 itself;
- Java 17 or newer to run Gradle; IntelliJ's bundled JBR works;
- the Gradle distribution (9.6.0) on disk. The wrapper's own download failed on the Mac: `Downloading from https://services.gradle.org/distributions/gradle-9.6.0-bin.zip failed: timeout (10000ms)` (the zip is served from github.com, which Java on that Mac doesn't reach), while `curl -L` of the same URL worked. Fetch it with curl and put it where the wrapper looks: `~/.gradle/wrapper/dists/gradle-9.6.0-bin/<hash>/gradle-9.6.0-bin.zip` (the `<hash>` folder is the one the failed run created);
- JetBrains Client **263.7307.200**, the same build as the backend. The build takes the protocol jars and the fonts from it, and every SVG from its `lib/` and `plugins/` jars into one `icons.jar` asset (about 6,100 icons, 4.5 MB compressed): the backend names icons by path, and plugin icons (Kotlin's file and class icons are in `intellij.kotlin.base.resources.jar`) aren't in `intellij.platform.icons.jar`. Before this, Kotlin files and other plugin icons showed no icon. The platform jar goes first, so its icons win when two jars have the same path.

```sh
# sdk.dir and jetbrains.client.lib in local.properties, as in client.md
export JAVA_HOME="$HOME/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew :android:assembleDebug        # android/build/outputs/apk/debug/ideate-debug.apk, about 47 MB
adb install -r android/build/outputs/apk/debug/ideate-debug.apk
```

The protocol jars are patched for ART on the way (class file version, `sun.misc.Unsafe` in rd's `UnsafeBuffer`); `android/rd-patch/` holds the shim.

## Run

The app opens on the connect screen, shared with the desktop since 2026-11-17 (`compose/.../connect/`; [`connections.md`](https://github.com/safeuq/intellij-backend-android/blob/main/docs/connections.md)):

| Part | What it does |
|---|---|
| Join link | Paste the link the backend printed, optionally an absolute file path on the backend, and tap **Connect**. |
| On this phone | Backends running here: from Termux (`intellij-backend status --json`, once the app may run Termux commands), or any of the ports 127.0.0.1:5990–5999 that accept a connection. A bare port needs its link pasted. With Termux, the ports are probed only after `status` answers (2 s or more), so Termux's own port never shows as a bare `127.0.0.1:5990` row while it is still answering. |
| History | Connections that worked, newest first. A tap connects again with the stored link and client identity, also after the app was closed. When the backend has restarted, the old link is refused: paste the new one, and the entry is updated. Termux entries ask Termux for the current link, and offer `intellij-backend start <project>` when none is running. |

| Backend | Join link |
|---|---|
| in Termux on the same phone | as printed, `tcp://127.0.0.1:5990#jt=…&fp=…` |
| on a computer, phone or emulator on USB | as printed, after `adb reverse tcp:5990 tcp:5990` |

Termux commands need `allow-external-apps = true` in `~/.termux/termux.properties` (then `termux-reload-settings`); the app asks for Termux's "Run commands in Termux environment" permission when you tap the Termux row.

For scripted runs, the link and file can come as extras; the app then connects at once:

```sh
adb shell "am start -S -n io.github.safeuq.ideate.client.android/.MainActivity --es joinLink 'tcp://127.0.0.1:5990#jt=…&fp=…' --es openFile '/path/on/backend/Main.java'"
```

The client's log (connection steps, errors) goes to logcat under `System.out`: `adb logcat -s System.out`.

The launcher icon brings the app back as it was. Android matches the icon's intent (`act=MAIN cat=LAUNCHER`) against the one that started the task; a task started another way (`adb shell am start -n …/.MainActivity`) doesn't match, and Android used to stack a second connections window, with a second `ConnectController`, over the editor (2026-11-23). `MainActivity` now closes itself at once when it isn't the task's root and was started by the launcher intent, so the editor below shows.

## Screen off and reconnecting

**Why the connection dropped with the screen off.** With the screen off the activity stops and the app is only
"top sleeping". Once the phone dozes, Android cuts such an app off the network, even from a port on the same
phone. The connection then hears nothing for 15 s and ends with `Nothing received for 15 s`. Reproduced on the
emulator (2026-11-17): screen off, `dumpsys battery unplug`, `dumpsys deviceidle force-idle`, and 15 s later the
app was back on the connect screen with that message. The cached-app freezer wasn't the cause: the app stayed
"top sleeping", not frozen.

**The screen stays as it was on reconnect.** When the backend accepts the new session, its state starts empty and fills
in over a second or more. Until then the screen shows the old session's last state (`session/HandOver.kt`): each panel
(tree, editors, tool windows, terminals, status bar, …) keeps the old content until the new session has sent its
first content for that panel, then follows the new one for good. Open editors stay as they were but read-only until the
new session's editor for that file is editable, so nothing typed goes to a document it hasn't loaded. Popups, menus and
failures never carry over. The old state is dropped once every panel has switched, or after 10 s at most. If the connection drops, the screen keeps showing what it showed last (a dropped session clears its own state), also
when the new session breaks while filling in. Pane weights are kept across the swap. Only reconnects inside the running app are covered, not a restart.

The Project tree's open folders and selected row are node ids that the new session doesn't know, so `ConnectController` passes their file paths to the new `ClientSession` (`expandedPaths`, `selectedPath`), which opens and selects those rows as they arrive; the tree switches only once it shows every row the old one showed. A terminal tab of the new session starts at the size the backend already has for it, so its restored screen isn't laid out at 80 × 24 first. Open (2026-11-23): the restored terminal screen comes back top-aligned with long lines cut instead of wrapped, so it doesn't look exactly as before.

**No keep-alive on purpose.** Ideate follows the normal Doze rules: with the screen off the connection may drop,
and it is reconnected when the app comes back. A foreground service would keep the network on in Doze (tried
2026-11-17: forced deep Doze on the emulator for a minute, and about 20 minutes locked on the phone, with no drop).
It was taken out again: Android requires a visible notification for one, and it exempts the app from Doze for the
whole session, using battery for a connection nobody is looking at. Termux keeps the backend itself running with
its own foreground service.

**Reconnecting.** Ideate does what JetBrains Client does (`RdUiBundle`: "No connection to IDE Backend. Trying to
reconnect…"):
- While the app is in the background (the activity is stopped) nothing is tried: under Doze every attempt
  would fail. Compose doesn't run the effect that notices the loss until the activity starts again either.
- When the app is back, the screen stays as it was, under that popup, and the first attempt starts at once.
- It connects again with the same link and client identity after 1 s, 2 s, 4 s, … (at most 10 s apart), for
  2 minutes of the app being in the foreground. Leaving the app during that pauses it; coming back restarts
  the 2 minutes.

For this and any other drop (a network change, a backend on another machine, the backend killed):
- The first session the backend accepts replaces the old one. The Project tree reloads, and the editor
  reopens the file it was opened with.
- After 2 minutes, or on Cancel, it shows the connect screen, where the history entry still reconnects.
- A backend taken over by another client isn't retried; that keeps its "Take back" question.
- While it reconnects, the old session has stopped but its screen is still shown. A tap or key there used to
  reach the old session's stopped rd thread and came up as "Task … rejected from …SingleThreadSchedulerBase…
  [Terminated …]" (desktop, 2026-11-20). Such an action is now dropped and logged ("Session stopped; dropped an
  action"); `StoppedSchedulerTest` covers it.
- Tested on the emulator: the app was paused for 40 s (`kill -STOP` via `run-as`), so the backend closed the
  wire. After the resume the app reconnected in under half a second.

**Back.** Back from a tool window page (phone held upright) goes to the editor. Back from the editor goes to the
connect screen: the session ends, the backend keeps running, and the history entry reconnects.

## Addendum (2026-11-23): the screen stays as it was when the app comes back

What the S24 showed (Termux backend, big project, 2 editors, a terminal): the user left Ideate, used other
apps, and opened it from Recents. Once the editor window had closed (connections screen), once the reconnect
popup sat over a blank screen until the client was back. The process was not killed: Samsung's freezer froze it
(`am_freeze`). After the thaw SplitWire's idle check fired ("Nothing received for 15 s"), and the 2-minute
reconnect window had run out while the app was frozen (it was checked before `inForeground`).

Wishes: no foreground service; no connection checks in the background; when Ideate comes back it shows the last
screen unchanged, and parts change only when the backend sends something different.

What changed:
- **No checks in the background.** `SplitWire` takes `inForeground`. While the app is away, a read timeout no
  longer ends the wire; the ping thread keeps "last activity" at now, so the 15 s start again when the app is back
  (the idle end then comes 15 to 30 s later, because the blocked read still wakes every 15 s). Pings still go out;
  a failed write only starts a reconnect, which waits for the foreground.
- **The 2 minutes count foreground time only** (`ForegroundClock`). A drop while frozen or in the background never
  ends the session or closes the editor window.
- **No 10 s cap on the hand-over.** It ends when every panel has its first value from the new session, or on
  `SessionState.loaded`: the project has loaded and every reopened editor is opened or found gone
  (`ClientSession.markLoaded`, from `isLoaded` and `finishedOpening`). The text of an editor may still be on its
  way then, so an editor stays read-only and old until the new one is editable. Two steps, because the project name,
  tree and terminals arrive some seconds after `loaded` (seen on the emulator: ending the hand-over 2 s after
  `loaded` showed "..." and a blank terminal):
  - 2 s after `loaded` (`SETTLE_MS`): panels the backend never sent (a console that is gone, breadcrumbs of a deleted
    file) show the new, empty value, and an editor the new session doesn't have is dropped.
  - 15 s after that (`FORCE_MS`): the project name, tree, tab icons, scheme, tool windows and terminals too (a
    deleted folder never makes the tree "show every old row").
  A new terminal tab counts as sent only when it has text; before that it is a blank screen.
- **The saved screen.** `SavedScreen` (@Serializable, `session/SavedScreen.kt`) holds the project name, tree,
  open editors (text, caret, highlights, guides), tab icons, selected editor, scheme, tool windows, the open
  tool windows by place, and each terminal's visible lines and cursor (not the 5000-line history). It is a
  `SavedSession` with the link, identity, project and `editorsKey`. `EditorScheme` now keeps flat maps (key to
  style, key to color) instead of the rd models, so it can be saved. Pane sizes and open files were already kept
  per `editorsKey` in `OpenEditors`.
- **When it is written.** `ConnectController.saveScreen()` runs in `EditorActivity.onStop` and writes the
  `connection/savedSession` preference. Not for SSH connections (their local forward is gone with the process).
  It is cleared when the session ends for good: Back (`leave`), 2 foreground minutes without success, a backend that
  was taken over.
- **After process death.** `MainActivity.onCreate` calls `restoreSaved()`. It puts a stand-in `ClientSession` (never
  started) in `session`, shows the saved screen as the hand-over source, and runs the normal `reconnect` loop with the
  saved link and identity, so the reconnect popup sits over the saved screen and the editor window opens at once. The
  recreated `EditorActivity` (`redirected`) must not call `leave()` when it finishes. The connections window shows
  nothing until the editor covers it.
- The screen under the popup is not interactive: the popup is a dialog window that takes all touches.

Tests: `SavedScreenTest` (round trip), `ForegroundClockTest`, `HandOverTest` (settled and forced).

Two more causes found on the emulator (freeze of 60 s, reopen from Recents), both fixed:
- `SplitWire.close` set `connected = false` before telling the session, so rd ended its models and emptied the
  session's state a few ms before `connectionLost` was set; the screen then saved that empty state as "what was
  shown" (title "...", no editor text). It now calls `onClosed` first.
- `collectAsState()` keeps the old session's last value for one frame after `session` changes. The hand-over took
  that full old state for "the new session has sent everything" and switched every panel at once. `EditorApp` now
  reads `session.state.value`, and `handOverFrom` holds the new session with the old state (`incoming`), so a frame
  that still has the old session shows the saved state.
- An editor that had colors waits for the new session's colors as well as its text; the backend's tool windows count
  as arrived when all the old ones are back.

Tested on the emulator (Pixel 3a, API 34; screenshots show the editor, title, tool window bar and terminal
unchanged in every frame):
- Freeze 60 s, reopen from Recents: unchanged; the reconnect popup fades in over the unchanged screen.
- The backend paused 15 s during the reconnect: unchanged for the whole time under the popup; after the reconnect the
  terminal view moves to the backend's own screen (the backend sends the whole scrollback, so the visible lines can
  differ from the saved ones by a few lines).
- `am kill`, reopen from Recents: the editor window with the saved screen and the popup at once, no connections screen;
  the first attempt failed ("Broken pipe"), the second connected with the saved identity 14 s later.
- Back, then the process killed and reopened: the connections screen; the saved entry is empty.

**Backend caveat.** When a client vanishes without closing (reinstalling the APK, a killed process whose socket the
phone still holds open), the backend keeps it as `controllerConnected` and answers a new client, even with its saved
identity, with `USER_DECLINED`. Leaving with Back avoids it. After `am kill` the saved identity was accepted again
within about 14 s (the second attempt), so Ideate's retry loop covers it; a reinstall wipes the identity, which is a
different case (the join token stays bound to the old client until the backend is restarted).

## Addendum (2026-11-23): leaving off the main thread; a stale saved session

- **Back threw `NetworkOnMainThreadException`.** `ClientSession.stop()` ended the rd lifetime on the UI thread, and rd sends each model's last state through the wire while it ends (`RdExtBase.sendState` → `SplitWire.send`). `stop()` now ends the lifetime on a thread of its own. Checked on the emulator: Back logs no exception, and joining again right after is accepted. The backend's status still said `controllerConnected: true`, with `secondsSinceLastControllerActivity` counting from before the test, so that flag is not a sign of a client stuck on the backend; the `USER_DECLINED` seen earlier came after a reinstall killed the app mid-connection.
- **A launch with a `joinLink` extra no longer restores the saved screen.** The restore and the new link both ran; the link connected, but the restored stand-in stayed on screen and kept retrying its old link.
- **A saved link whose backend has restarted is given up at once.** The backend's certificate is new, so every attempt fails with "Server fingerprint … does not match fp …" (`FingerprintTrustManager`). Example: saved `fp=F66A…`, backend now `A2D3…`. The reconnect loop now stops after the first such failure: a Termux backend goes on to `termuxStatus` (Termux gives the new link), a link connection shows "The backend was restarted; connect to it again with its new link." on the connections screen, instead of 2 minutes of "Trying to reconnect".
