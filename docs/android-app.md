# Android app

The split-mode client as an Android app, named IDEate on the phone (applicationId `intellijandroid.client.android`). It uses the `:compose` module shared with the desktop client (connection, session, UI; see [`client.md`](client.md)) and adds only the Android pieces: the connect screen (link prompt, backends on this phone, history; Termux commands), fonts and icons from assets, and the soft keyboard hook. How it works and what was tested: [`split-protocol.md`](split-protocol.md), "The Android app (step 5)".

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
| On this phone | Backends running here: from Termux (`intellij-backend status --json`, once the app may run Termux commands), or any of the ports 127.0.0.1:5990–5999 that accept a connection. A bare port needs its link pasted. |
| History | Connections that worked, newest first. A tap connects again with the stored link and client identity, also after the app was closed. When the backend has restarted, the old link is refused: paste the new one, and the entry is updated. Termux entries ask Termux for the current link, and offer `intellij-backend start <project>` when none is running. |

| Backend | Join link |
|---|---|
| in Termux on the same phone | as printed, `tcp://127.0.0.1:5990#jt=…&fp=…` |
| on a computer, phone or emulator on USB | as printed, after `adb reverse tcp:5990 tcp:5990` |

Termux commands need `allow-external-apps = true` in `~/.termux/termux.properties` (then `termux-reload-settings`); the app asks for Termux's "Run commands in Termux environment" permission when you tap the Termux row.

For scripted runs, the link and file can come as extras; the app then connects at once:

```sh
adb shell "am start -S -n intellijandroid.client.android/.MainActivity --es joinLink 'tcp://127.0.0.1:5990#jt=…&fp=…' --es openFile '/path/on/backend/Main.java'"
```

The client's log (connection steps, errors) goes to logcat under `System.out`: `adb logcat -s System.out`.

## Screen off and reconnecting

**Why the connection dropped with the screen off.** With the screen off the activity stops and the app is only
"top sleeping". Once the phone dozes, Android cuts such an app off the network, even from a port on the same
phone. The connection then hears nothing for 15 s and ends with `Nothing received for 15 s`. Reproduced on the
emulator (2026-11-17): screen off, `dumpsys battery unplug`, `dumpsys deviceidle force-idle`, and 15 s later the
app was back on the connect screen with that message. The cached-app freezer wasn't the cause: the app stayed
"top sleeping", not frozen.

**What keeps it.** `ConnectionService` is a foreground service that runs while a session is open, with a
notification "Connected to <project>".
- A UID with a foreground service keeps its network in Doze. On the emulator the same forced deep Doze then
  ran for a minute with no drop.
- On the phone (Samsung, Termux backend on 127.0.0.1:5990, 2026-11-17): locked and unplugged for about
  20 minutes with light Doze IDLE, and the same connection was still up on unlock, with no reconnect. Deep
  Doze never started there: battery saver was on (`low_power=1`), and even `dumpsys deviceidle force-idle deep`
  stopped at `QUICK_DOZE_DELAY`.
- Termux keeps its own backend alive the same way, with its own foreground service.
- The type is `specialUse`: `dataSync` is limited to 6 hours a day from Android 15.
- The app asks once for notification permission. Without it the service still runs, with no visible
  notification.

**When it still drops** (a network change, a backend on another machine, the backend killed), IDEate does what
JetBrains Client does (`RdUiBundle`: "No connection to IDE Backend. Trying to reconnect…"):
- The screen stays as it was, under that popup.
- It connects again with the same link and client identity after 1 s, 2 s, 4 s, … (at most 10 s apart), for
  2 minutes.
- The first session the backend accepts replaces the old one. The Project tree reloads, and the editor
  reopens the file it was opened with.
- After 2 minutes, or on Cancel, it shows the connect screen, where the history entry still reconnects.
- A backend taken over by another client isn't retried; that keeps its "Take back" question.
- Tested on the emulator: the app was paused for 40 s (`kill -STOP` via `run-as`), so the backend closed the
  wire. After the resume the app reconnected in under half a second.

**Back.** Back from a tool window page (phone held upright) goes to the editor. Back from the editor goes to the
connect screen: the session ends, the backend keeps running, and the history entry reconnects.
