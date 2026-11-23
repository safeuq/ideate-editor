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
