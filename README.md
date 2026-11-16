# IDEate

A native client for IntelliJ's split mode (remote development), written with Compose. It connects to an IntelliJ backend and shows IntelliJ's Project and Structure tool windows and an editor with the backend's highlighting and completion, in IntelliJ's Islands Dark look. It runs as an Android app (IDEate) and on the desktop JVM.

The backend side, IntelliJ running on an Android phone in Termux with our JBR build and the `intellij-backend` script the app calls, is in [safeuq/intellij-backend-android](https://github.com/safeuq/intellij-backend-android). The contract between the two is in its [`connections.md`](https://github.com/safeuq/intellij-backend-android/blob/main/docs/connections.md).

```
compose/        shared code: connection/ (TLS, frames, rd wire), session/ (rd models), ui/
desktop/        the JVM launcher (./gradlew :desktop:run)
android/        the Android app (./gradlew :android:assembleDebug)
spikes/         rd-on-art (rd and the protocol models on ART), session-capture (summarizes an rd trace)
docs/           all documentation (below)
```

| Doc | What |
|---|---|
| [`client.md`](docs/client.md) | code layout and modules; run the desktop build |
| [`android-app.md`](docs/android-app.md) | build, install and run the Android app |
| [`split-protocol.md`](docs/split-protocol.md) | how JetBrains Client talks to the backend, and running rd on Android |
| [`edit-protocol.md`](docs/edit-protocol.md) | the editing messages in detail |
| [`completion-protocol.md`](docs/completion-protocol.md) | code completion over Fleet RPC |
| [`structure-protocol.md`](docs/structure-protocol.md) | the Structure tool window over rd (`CodeStructureTreeModel`), and the Fleet RPC `StructureTreeApi` the Ctrl+F12 popup uses |
| [`mobile-layout.md`](docs/mobile-layout.md) | the main window on phone and tablet, portrait and landscape: bars, swipes, which layout when |
| [`intellij-look.md`](docs/intellij-look.md) | IntelliJ's Islands Dark look: colors, fonts, metrics, icons |

Every finding goes into the matching doc in the same commit as the work; a new doc gets a row above.
