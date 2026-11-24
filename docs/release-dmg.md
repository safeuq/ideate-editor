# macOS disk image (DMG) of the desktop app

Written 24 Nov 2026, 19:06 IST, with the first build that worked.

## Build

```
export JAVA_HOME=~/.sdkman/candidates/java/25.0.5.0+8-tem
./gradlew :desktop:packageDmg
```

The result is `desktop/build/dmg/Ideate-1.0.0.dmg` (172 MB). It holds `Ideate.app` (275 MB) and a
link to `/Applications`, so installing is the usual drag.

| Setting | Example | Meaning |
|---|---|---|
| `-PideateVersion=1.1.0` | file becomes `Ideate-1.1.0.dmg` | the app's version; default `1.0.0`. `jpackage` refuses a version that starts with 0 on macOS, so `0.1` (the Android app's `versionName`) cannot be used |
| `-Pjpackage=<path>` | `-Pjpackage=$HOME/.sdkman/candidates/java/25.0.5.0+8-tem/bin/jpackage` | which `jpackage` to run. Needed when the Java 25 that Gradle finds is a JetBrains Runtime (for example when `JAVA_HOME` is IntelliJ's, as hot reload wants): that runtime has no `jpackage`, and the task fails at once saying so |

`jetbrains.client.lib` must be set as for any desktop build ([`client.md`](client.md), "Run"), and
the build needs Xcode's command line tools for `vtool` and `codesign`.

## Tasks

All in `desktop/build.gradle.kts`, run in this order by `packageDmg`.

| Task | Writes | What it does |
|---|---|---|
| `dmgIconsJar` | `build/dmg/icons/icons.jar` | every `.svg` from the client's `lib/**/*.jar` and `plugins/**/*.jar` in one jar, the platform icons jar first so its copy of a path wins. The same idea as `ClientAssets` in `android/build.gradle.kts` |
| `dmgInput` | `build/dmg/input/` | what becomes `Ideate.app/Contents/app`: `desktop.jar`, the run-time classpath (81 jars with the two below), and a `client/` folder |
| `dmgAppImage` | `build/dmg/image/Ideate.app` | `jpackage --type app-image`, then marks the launcher as built with macOS SDK 26 and signs the app ad hoc |
| `packageDmg` | `build/dmg/Ideate-<version>.dmg` | `jpackage --type dmg --app-image …` |

Compose's own `packageDmg` (`compose.desktop.application.nativeDistributions`) is not used. It
builds the runtime with `jlink` from `javaHome`, and the runtime the app needs, JetBrains Client's
JBR 25 (`JBR-25.0.4.1+1-635.79-nomod`), has no `jlink` and no `jmods`. `jpackage --runtime-image`
copies that JBR as it is.

## What is inside `Ideate.app`

| Path under `Contents/` | Size | From |
|---|---|---|
| `runtime/` | 172 MB | a copy of `<JetBrains Client>/Contents/jbr`. The title bar needs JBR's window decorations (`MacTitleBar.kt`) |
| `app/*.jar` | 103 MB with `client/` | `desktop.jar`, `compose-jvm.jar`, Compose and Skiko for macOS arm64, sshj, and the protocol and Fleet jars from JetBrains Client (`protocolJars`, `fleetJars` in the root `build.gradle.kts`) |
| `app/client/lib/intellij.platform.icons.jar`, `app/client/lib/icons.jar` | | the icons `DesktopResources.JarIcons` reads |
| `app/client/jbr/Contents/Home/lib/fonts/` | | the six fonts `DesktopResources.fonts` reads: four JetBrains Mono, two Inter |
| `MacOS/Ideate` | | `jpackage`'s launcher |
| `Resources/Ideate.icns` | | `app-icon/dark/macos/Ideate.icns` |

`client/` has the shape of a JetBrains Client install (`lib` next to `jbr/Contents/Home/lib/fonts`),
and the launcher passes `-Djetbrains.client.lib=$APPDIR/client/lib`. So `Main.kt` and
`DesktopResources.kt` find fonts and icons the same way as in a Gradle run and needed no change.
The client's real `lib` and `plugins` folders are 2.5 GB, which is why the icons are merged into one
jar instead of copied.

The other JVM option is `-Dcompose.application.configure.swing.globals=true`, which Compose's `run`
task also sets.

## The launcher and the title bar

macOS 26 chooses the title bar's layout from the SDK the app's executable was built with
([`intellij-look.md`](intellij-look.md) section 25). `jpackage`'s launcher comes from the JDK that
ran `jpackage`, so `dmgAppImage` rewrites its build version with `vtool -set-build-version macos
11.0 26.0`, as `prepareSdk26Jbr` does for the `java` launcher of a Gradle run. That breaks the
signature `jpackage` made, so the whole app is signed again with `codesign --force --deep --sign -`.

## Checked

On the DMG built from this commit, 24 Nov 2026, 19:05 IST, on the Mac that built it:

- Mounted the DMG and copied `Ideate.app` out of it.
- `vtool -show-build Contents/MacOS/Ideate`: `minos 11.0`, `sdk 26.0`.
- `codesign --verify --deep --strict`: valid.
- `Info.plist`: identifier `io.github.safeuq.ideate`, version `1.0.0`, icon `Ideate.icns`.
- `CLIENT_GALLERY=<file.png> Contents/MacOS/Ideate` drew the component gallery with Inter and the
  icons from the bundled jars.
- Started normally, the app opened its 800 by 650 window titled "Ideate" (read through System
  Events).

Not checked: connecting to a backend from the packaged app, the title bar's height by eye, and
opening the DMG on another Mac.

## Open before a public release

1. **Signing.** This Mac has no Developer ID certificate (`security find-identity -p codesigning`
   lists none), so the app is signed ad hoc and not notarized. On another Mac a downloaded copy is
   blocked by Gatekeeper until the user runs
   `xattr -dr com.apple.quarantine /Applications/Ideate.app`. A Developer ID certificate needs a
   paid Apple Developer account; with one, `dmgAppImage` would sign with it and the hardened
   runtime, and the DMG would be sent to `notarytool`.
2. **Licence of the bundled JetBrains files.** The DMG contains JetBrains Client's runtime, its
   protocol and Fleet jars, its icons and fonts, as the Android APK already does. Whether these may
   be given to other people has not been checked.
3. **Apple silicon only.** The runtime and Skiko are arm64. An Intel build needs the x64 JetBrains
   Client and a build on (or for) x64.
4. **Version.** `1.0.0` is a placeholder forced by `jpackage`'s rule; the Android app says `0.1`.
5. **Where it is published.** Nothing uploads the DMG; a GitHub release would be
   `gh release create v1.0.0 desktop/build/dmg/Ideate-1.0.0.dmg`.
6. **Backend version.** The bundled protocol jars are build 263.7307.200 and only talk to a backend
   of the same build, as in a Gradle run.

## Addendum (24 Nov 2026, 19:08 IST): the version is 0.1.0

The default version is now `0.1.0`, so the file is `desktop/build/dmg/Ideate-0.1.0.dmg`; where the
text above says `1.0.0` or `Ideate-1.0.0.dmg`, read `0.1.0`. Open point 4 ("Version") is closed.

`jpackage` 25 still refuses it: `--app-version 0.1.0` fails with "The first number in an app-version
cannot be zero or negative". macOS itself accepts such a version. So the build gives `jpackage` the
fixed version `1.0.0` (`jpackageVersion` in `desktop/build.gradle.kts`) and then writes the real one:

| Where | Value with the default | Set by |
|---|---|---|
| `Info.plist` `CFBundleShortVersionString` and `CFBundleVersion` | `0.1.0` | `dmgAppImage`, with `PlistBuddy`, before signing |
| DMG file name | `Ideate-0.1.0.dmg` | `packageDmg` renames `jpackage`'s `Ideate-1.0.0.dmg` |
| `-Djpackage.app-version` in `Contents/app/Ideate.cfg` | `1.0.0` | `jpackage`; left as it is, nothing reads it |

Checked on the rebuilt DMG: both `Info.plist` keys print `0.1.0`, `codesign --verify --deep
--strict` passes, and the launcher still shows `sdk 26.0`.
