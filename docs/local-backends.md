# Backends on this computer (plan)

Plan, 2026-11-18. The desktop app's **This computer** page should work like the phone's **This phone** page: list
running and recent projects, start a backend for one that isn't running, connect without pasting a link, stop it.

## Why

Today the desktop page only checks ports 5990 to 5999 on `127.0.0.1` and asks for a pasted join link
([`client.md`](client.md), "Connections"). A link is tied to the backend's certificate, and every backend start
makes a new one. After a restart the saved link fails:

| | Started (IST) | `fp=` in the join link |
|---|---|---|
| Link the app had saved | 18 Nov, 00:29 | `7EC80616…` |
| Backend now on port 5992 | 18 Nov, 01:15 | `06061483…` |

```
Couldn't connect: javax.net.ssl.SSLHandshakeException: (certificate_unknown) Server fingerprint
06061483… does not match fp 7EC80616…
```

The phone avoids this because Termux's `intellij-backend status --json` prints the current link and `start` starts
a backend. The SSH page does the same for remote hosts with `ShellHost` ([`ssh-connections.md`](ssh-connections.md)).

## Design

Run the SSH page's `ShellHost` on this computer: the same shell scripts, run by the local `/bin/sh` instead of over
SSH. `ShellHostTest` already runs them that way.

| Action | Command run locally | Result |
|---|---|---|
| List projects | read IDE history and backend history (next sections) | `smoke`: **Running**; `IDLReasonerChoco`: **Last active 24 Apr 2025** |
| Open a running project | `<ide>/bin/remote-dev-server.sh status <project>` | its current link, e.g. `tcp://127.0.0.1:5993#…&fp=0606…` |
| Open a project that isn't running | `nohup <ide>/bin/remote-dev-server.sh run <project> … &`, then `status` once a second | starts it, then connects |
| New Project | the SSH page's folder browser (`listFolders`) | any folder |
| Stop | `<ide>/bin/remote-dev-server.sh stop <project>` (IntelliJ's own stop; Termux's `intellij-backend stop` uses it too) | the backend saves and exits |
| Saved link refused | `status` again for that project | reconnects with the new link |

No port forwarding: the backend already listens on `127.0.0.1`.

Which IDE the commands use, and which projects are listed, depends on the **host's** operating system from
`uname -sm` (`HostOs`), not on where Ideate runs. So a Mac reached over SSH gets the same lookups:

| Ideate on | Backend machine | `uname -sm` | Mac lookups |
|---|---|---|---|
| Mac, This computer | the same Mac | `Darwin arm64` | yes |
| Phone, SSH page | a Mac | `Darwin arm64` | yes |
| Mac, SSH page | a Linux server | `Linux x86_64` | no; Linux paths |
| Windows desktop | itself | no `/bin/sh` | page stays as today (ports, pasted link) |

### Finding IDEs

In this order, keeping the first copy of each path. An app counts only if it has `Contents/bin/remote-dev-server.sh`;
its name, product code and build come from `Contents/Resources/product-info.json`.

| Source | Path | Example on this Mac |
|---|---|---|
| Toolbox channel files (macOS) | `~/Library/Application Support/JetBrains/Toolbox/channels/*.json`, field `channel.installationDirectory` | `IDEA-U-21175….json` → `/Users/safeuq/Applications/IntelliJ IDEA.app` |
| Toolbox 1.x layout (macOS) | `…/Toolbox/apps/*/ch-*/*/*.app` | none (empty `apps/IDEA-C`, `apps/PyCharm-C` left over) |
| Applications folders (macOS) | `/Applications/*.app`, `~/Applications/*.app` | IntelliJ IDEA, CLion, GoLand, PyCharm Professional Edition; Gateway and Android Studio dropped (no `remote-dev-server.sh`) |
| Gateway's folders (all) | `<RemoteDev>/dist/*`, `<RemoteDev>/userProvidedDist/*` | what `ShellHost` reads today |

The channel files cover a custom Toolbox install folder (Toolbox settings → install location), which no fixed path
does. `sed` takes `installationDirectory` out of each file; macOS has no `jq`:

```sh
sed -n 's/.*"installationDirectory" *: *"\([^"]*\)".*/\1/p' "$T"/channels/*.json
```

Linux Toolbox (`~/.local/share/JetBrains/Toolbox/channels`) has the same layout; left out until someone needs it.

### Listing projects

Two histories, merged by project path:

| Source | Path | Gives |
|---|---|---|
| IDE history (new) | macOS `~/Library/Application Support/JetBrains/<Product><Version>/options/recentProjects.xml`; Linux `${XDG_CONFIG_HOME:-~/.config}/JetBrains/…` | projects opened in the desktop IDE, last-used time, product code |
| Backend history (what `ShellHost` reads today) | `<RemoteDev>/recent/`, `<RemoteDev>/active/` | projects a backend served; running or not |

One entry of `IntelliJIdea2026.3/options/recentProjects.xml` (50 entries on this Mac):

```xml
<entry key="$USER_HOME$/Files/Projects/Java/OpenSource/IDLReasonerChoco">
  <RecentProjectMetaInfo frameTitle="IDLReasonerChoco – OASSolver.java" …>
    <option name="activationTimestamp" value="1745454457133" />
    <option name="build" value="IU-243.26053.27" />
    <option name="productionCode" value="IU" />
```

Rules:

- Only the newest settings folder per product: `IntelliJIdea2026.3`, not `IntelliJIdea2026.2`.
- `$USER_HOME$` becomes the host's `$HOME`.
- A project in both histories is one row: **Running** if a backend serves it, else the later of
  `activationTimestamp` and `dateLastOpened`.
- A project whose folder is gone is hidden, as IntelliJ's welcome screen does.
- `productionCode` (`IU` IntelliJ IDEA Ultimate, `PY` PyCharm, `GO` GoLand, `CL` CLion) picks the installed IDE with
  that code, newest build first; none installed → the user picks.

The shell prints each `recentProjects.xml` after a marker line, as it already prints status files; Kotlin parses it
with `javax.xml` (desktop Java and Android both have it).

## Steps

- [x] **0. Check shared settings.** A backend started this way uses the desktop IDE's own settings folder
  (`IntelliJIdea2026.3`). With IntelliJ open, the launcher hands `run` and `status` to the open IDE: `run` prints
  `The command 'remoteDevHost' requires Remote Development backend` and exits 1, and `status` never has a link.
  Found over SSH to this Mac (2026-11-18); the SSH page now says so within about 2 s
  ([`ssh-connections.md`](ssh-connections.md), "A launch that ends early"). Still to check: IntelliJ closed (start,
  `status`, `stop`), and whether a backend with its own settings folder runs beside the open IDE.
- [ ] **1. Finding IDEs.** `ShellHost`: a `MAC_APPS` script run before `INSTALLED_IDES` when `os()` is macOS; each
  path goes through the same `Contents` check. Test: `ShellHostTest` with a made-up home holding a channel file, an
  `~/Applications` app, a Toolbox 1.x app and an app without `remote-dev-server.sh`.
- [ ] **2. Listing projects.** `ShellHost` prints the newest `recentProjects.xml` per product; `HostData` parses and
  merges. `RecentProject` gets `productCode` and a source. Tests: parsing the entry above, the newest-folder rule,
  `$USER_HOME$`, the merge, missing folders.
- [ ] **3. Stop.** `RemoteHost.stop(idePath, projectPath)`: `ShellHost` runs `remote-dev-server.sh stop`;
  `WorkerHost` too, unless the worker has its own command. The SSH page's **Running** rows get the stop square
  Gateway shows.
- [ ] **4. This computer page.** Desktop `Main.kt` gives `ConnectController` a local `RemoteHost`
  (`ShellHost(LocalShell::run)`, where `LocalShell` runs `/bin/sh -c` with `ProcessBuilder`); null on Windows. The page
  shows the SSH page's project list for that host, its folder browser and its open flow without the port forward.
  Ports not matched to a listed project stay as `This computer · port 5992` rows needing a pasted link.
- [ ] **5. History and reconnect.** Entries from this page are saved with their project; when the saved link is
  refused, ask `status` for a fresh link and connect with it, the way Termux entries do.
- [ ] **6. Check on this Mac.** Open a project that isn't running; open it again after `stop`; restart the backend
  and reconnect from history (no certificate error); a PyCharm project opens in PyCharm. Before/After shots of the
  page on the UI artifact.
- [ ] **7. Docs.** This file's steps marked done with results; `client.md` "Connections" row updated.

The connect screen is the UI session's area: tell it before step 4 and agree who builds the page.

## Addendum (2026-11-25): no open-port rows

Step 4's "Ports not matched to a listed project stay as `This computer · port 5992` rows" no longer
applies: the port rows were removed from the connect screen because the user found them not useful.
A backend started by hand is reached with Link.

## Addendum (2026-11-25): decisions and the build plan

### Decisions

| Question | Decision | Why |
|---|---|---|
| Own settings folders for the backend (`-Didea.config.path=~/.intellij-remote/config` and so on)? | **No.** Use the IDE's own folders, as Gateway and the SSH page do. | Gateway's SSH start sets no folders (`intellij.gateway.ssh.jar` names only `remote-dev-server.sh`); separate folders need copied settings (`jdk.table.xml`) and a second trust. |
| IntelliJ IDEA 2026.3 is open with the same folders | **Refuse with a message**, the SSH page's: "Another IntelliJ IDEA process is using the same settings, so a backend can't start. Close it, then try again." | The launcher hands `run` to the process holding the folders; only a desktop IDE answers `requires Remote Development backend` (a backend takes the project, exit 0). `HostData.launchFailure` already looks for that text. The message does not guess which process it is. |
| Port rows (`This device · port 5992`) | **Gone** (edda9fa). | The user found them not useful. |
| Step 5 (history and reconnect) | **Dropped.** | Like the SSH page: every open asks `status` for the current link, so a refused saved link never comes up. Nothing is saved to history for this page. |

### Build plan

The page is the SSH page's host section for one more host, **this computer**, reached without SSH.

1. **`LocalShell`** (new, `desktop/src/main/kotlin/io/github/safeuq/ideate/client/desktop/LocalShell.kt`):
   `object LocalShell { fun run(commandLine: String): ExecResult }` runs `/bin/sh -c <commandLine>` with
   `ProcessBuilder`, reads stdout and stderr fully (stderr on its own thread), waits, returns
   `ExecResult(exitCode, stdout, stderr)`. Desktop `Main.kt` passes
   `localHost = { RemoteHosts.forCommands(LocalShell::run) }` in `SshSetup` when `File("/bin/sh").canExecute()`, else
   `null` (Windows). Android passes nothing (default `null`).
2. **`SshSetup.localHost: (() -> RemoteHost)? = null`**. In `SshController`:
   - `const val LOCAL_HOST_ID = "local"` and `val localEntry = SshHostEntry(LOCAL_HOST_ID, "localhost", 0, "", LoginMethod.GENERATED_KEY)`
     (never saved to `ssh-hosts.json`).
   - `remoteFor(entry)`: for `LOCAL_HOST_ID`, return the cached `setup.localHost()` result; no `SshSession`.
   - `SshPageState.local: SshHostView?` (null when `setup.localHost == null`), kept apart from `hosts`, so the SSH
     page never lists it. `refresh()` and `poll` refresh it too. `setHost` writes `local` for `LOCAL_HOST_ID`.
   - `openProject`: for `LOCAL_HOST_ID` the backend's link is used as is (no `forward`); `connect(link, projectPath,
     THIS_DEVICE)`.
3. **Shared host section.** Move the body of `SshPage`'s `state.hosts.forEachIndexed` (the `HostGroup` and its
   `ProjectRowView`s) into `@Composable internal fun HostSection(host: SshHostView, title: String, removable: Boolean,
   search: String, actions: SshActions, styles: ConnectStyles, onShowError: (SshHostView) -> Unit)`. `removable = false`
   drops the "Remove Host" menu item. `SshPage` calls it per host with `title = host.entry.name, removable = true`.
4. **This device page on the desktop.** `PhonePage` when `state.termux == null` and `actions.ssh?.state?.local != null`
   (pass what it needs): header "Recent Projects" with **New Project** (`actions.ssh.newProjectOn(localEntry)`, the SSH
   page's Choose IDE dialog and folder browser), then `HostSection(local, title = THIS_DEVICE, removable = false, …)`. No
   Termux rows. Errors and progress use `SshOverlays`, already shown above every page.
5. **Finding IDEs on macOS** (`ShellHost.INSTALLED_IDES`, so SSH to a Mac gets it too): after `dist/*` and
   `userProvidedDist/*`, when `uname` is `Darwin`: every `installationDirectory` in
   `~/Library/Application Support/JetBrains/Toolbox/channels/*.json` (the `sed` above), then `/Applications/*.app` and
   `~/Applications/*.app`. Each candidate `<x>.app` counts only with `Contents/bin/remote-dev-server.sh`; it prints the
   same `@@IDE@@<tab>idePath<tab>` record with `idePath = <x>.app/Contents` and `Contents/Resources/product-info.json`.
   Keep the first copy of each resolved path (`pwd -P`). Test in `ShellHostTest` with a made-up home: a channel file, an
   `~/Applications` app, an app without `remote-dev-server.sh` (dropped), and the same app twice (listed once).
6. **Listing projects from `recentProjects.xml`** (`ShellHost`, both OSes): a `RECENT_IDE_PROJECTS` script prints,
   per product, the newest settings folder's `options/recentProjects.xml` after a `@@XML@@<tab>folder` line
   (macOS `~/Library/Application Support/JetBrains/<Product><Version>`, Linux `${XDG_CONFIG_HOME:-~/.config}/JetBrains/…`;
   newest = highest version, e.g. `IntelliJIdea2026.3` over `IntelliJIdea2026.2`). `HostData.ideRecentProjects(xml,
   home, ides)` parses with `javax.xml` (`entry key` → path with `$USER_HOME$` → home; `activationTimestamp`, else
   `projectOpenTimestamp`; `productionCode`), picks the installed IDE with that product code (newest build) as
   `idePath`, and drops entries whose IDE isn't installed. `projectsAndIdes()` merges them with the backend history by
   path: a running backend wins, else the later time. Folders that no longer exist are dropped (`[ -d ]` in the script).
   Tests in `HostDataTest`: the entry quoted above, `$USER_HOME$`, newest-folder rule, merge, a missing folder.
7. **Stop** (step 3): `RemoteHost.stop(idePath: String, projectPath: String)`: `ShellHost` runs
   `remote-dev-server.sh stop <project>`; `WorkerHost` delegates to its shell. `SshActions.stopProject(entry,
   project)`; `HostSection` gives **Running** rows `onStop`, then refreshes the host.
8. **Docs**: an addendum here with the results of the live check; a `client.md` addendum for the page.

Live check on this Mac with IntelliJ IDEA open: the page lists IntelliJ's recent projects; opening one shows the
settings-in-use message; a PyCharm or GoLand project (IDE not open) starts, connects, and stops with the red square.

## Addendum (2026-11-25): items 5–7 done

What was built (items 5, 6 and 7 of the build plan above):

- **5. Finding IDEs on macOS.** `ShellHost.INSTALLED_IDES` now looks in `dist/*` and `userProvidedDist/*` first, then,
  only when `uname` prints `Darwin`, in every Toolbox channel file's `installationDirectory`, `/Applications/*.app`
  and `~/Applications/*.app`. An app counts only with `Contents/bin/remote-dev-server.sh`; its `idePath` is
  `<x>.app/Contents`. Each path is resolved with `pwd -P` and listed once. A missing RemoteDev folder no longer ends
  the script early, so a Mac without Gateway still lists its apps. `WorkerHost.installedIdes()` adds these apps to
  the worker's list. Tests: `ShellHostTest.findsMacAppsOnlyOnMac` (channel file, `~/Applications` link to the same
  app listed once, an app without `remote-dev-server.sh` dropped; runs only on a Mac).
- **6. Projects from `recentProjects.xml`.** A new script, `ShellHost.RECENT_IDE_PROJECTS`, prints each
  `<settings folder>/options/recentProjects.xml` after a `@@XML@@<tab>folder<tab>$HOME` line, then a
  `@@GONE@@<tab>key` line for each entry whose folder no longer exists (`sed` only pulls out the keys; the XML is
  parsed in Kotlin). `HostData.ideRecentProjects` parses it with `javax.xml`, keeps only the newest folder per
  product (`IntelliJIdea2026.3` over `IntelliJIdea2026.2`), replaces `$USER_HOME$` with `$HOME`, dates an entry by
  `activationTimestamp`, else `projectOpenTimestamp`, and opens it with the newest installed IDE of its
  `productionCode`. Entries whose IDE isn't installed are dropped. `HostData.mergedRecent` merges with the backend
  history by path: a running backend wins, else the latest date. `projectsAndIdes()` is still one shell command (the
  three scripts, each in its own subshell); `recentProjects()` returns the same merged list. `WorkerHost` keeps its one
  worker call and adds one shell command (`ShellHost.idesAndIdeHistory`) for the Mac apps and the IDE history.
  `RecentProject` did not need a new field. Tests: `HostDataTest` (the entry quoted above, `$USER_HOME$`,
  `projectOpenTimestamp` fallback, newest-folder rule, a missing folder, the merge) and
  `ShellHostTest.listsTheIdesRecentProjects` (the real script, a gone folder dropped).
- **7. Stop.** `RemoteHost.stop(idePath, projectPath)`: `ShellHost` runs `remote-dev-server.sh stop <project>`,
  `WorkerHost` hands it to its shell. `SshActions.stopProject(entry, project)` runs it off the UI thread, shows
  "Couldn't stop …" on failure, then refreshes the host. The SSH page's **Running** rows show the stop square.
  Test: `ShellHostTest.stopRunsTheLaunchersStop`.

Not done here: removing a row that came only from `recentProjects.xml` still removes only backend history, so the row
comes back on the next refresh.

Real data on this Mac (scripts run read-only against the real home, 2026-11-25):

| | Found |
|---|---|
| IDEs | IntelliJ IDEA `IU-263.7307.200` (also in `userProvidedDist`, listed once with its link), CLion `CL-243.21565.238`, GoLand `GO-251.26094.127`, PyCharm Professional `PY-243.21565.199`; Gateway and Android Studio dropped (no `remote-dev-server.sh`) |
| `recentProjects.xml` files | `CLion2024.3` (2 entries), `GoLand2025.1` (2), `IntelliJIdea2026.2` (50, skipped: older), `IntelliJIdea2026.3` (50), `PyCharm2024.3` (5) |
| Folders gone | CLion 1, GoLand 2, IntelliJIdea2026.3 8, PyCharm 5 (all of GoLand's and PyCharm's) |
| Rows after the merge | 69: 68 open in IntelliJ IDEA, 1 in CLion; none running |

## Addendum (2026-11-25): This device page built

What was built (items 1 to 4 and 8 of the build plan above):

- **1. `LocalShell`** (`desktop/.../desktop/LocalShell.kt`): runs `/bin/sh -c <command>` with `ProcessBuilder`,
  reads stderr on its own thread, returns `ExecResult`. `Main.kt` passes `localHost = { RemoteHosts.forCommands(LocalShell::run) }`
  when `/bin/sh` can run, else null. `RemoteHosts.forCommands` was `internal` to the compose module and is now public,
  so the desktop module can call it.
- **2. `SshSetup.localHost`, `SshController`.** `LOCAL_HOST_ID` and `localEntry` are top-level in `SshController.kt`
  (not members), so the page can pass `localEntry` to `newProjectOn` and `reconnect`. `remoteFor` caches the local
  `RemoteHost` in `remotes`; `SshPageState.local` holds its view; `refresh()` and `refreshReachable()` include it;
  `openProject` skips the forward and names the host `This device` in the progress popup, in error messages and in
  `connect`. The Choose IDE dialog and the "No IDE backend is installed on …" message also say `This device`.
- **3. `HostSection`** in `SshPage.kt`: the host group and its rows. `removable = false` drops Remove Host. Reconnect
  stays in the menu for this computer: it refreshes the list.
- **4. Desktop This device page** (`ConnectScreen.kt`, `LocalPage`): "Recent Projects" with New Project, the search
  row, then `HostSection(local, This device, removable = false)`. It refreshes this computer every 10 s with
  `reconnect(localEntry)` (no SSH host is touched). Without a local host (Windows) the old page stays with the
  notice "Use SSH or Link."; Android is unchanged.

Live check on this Mac (IntelliJ IDEA 2026.3 open), 2026-11-25:

| Check | Result |
|---|---|
| Page lists projects | about 69 rows, newest first (e.g. `scratchpad/tb/proj` 9 minutes ago, then `bankgateway`, `xtool`, …; oldest `RESTest` 6/20/25) |
| Open `RESTest` (IntelliJ IDEA) | "This device: Another IntelliJ IDEA process is using the same settings, so a backend can't start. Close it, then try again." No `remote-dev-server` process left. |
| New Project → Choose IDE | IntelliJ IDEA 2026.3, CLion 2024.3, GoLand 2025.1.2, PyCharm 2024.3 |
| PyCharm, `scratchpad/device-proj` | The open flow showed "remote-dev-server.sh status failed (exit 3): Problems found loading plugins: Open Policy Agent … requires build 242.* or older", but the backend kept starting and the row then showed **Running** with the red stop square. |
| Open the running PyCharm row | Link used as is (`127.0.0.1`, no forward), TLS fingerprint matched, PIN shown, then `Couldn't connect: java.io.EOFException: Unexpected end of ZLIB input stream` (PyCharm 243 backend with this 263 client; not this page). |
| Stop | the backend exited (no `remote-dev-server` process), the row went back to "Last active: 2 minutes ago" |

Not done or open:

- `status` failing with exit 3 because of a plugin warning ends the open flow with an error while the backend keeps
  starting. Same code as the SSH page; not changed here.
- Rows show no IDE name (the existing `ProjectRowView` has none).
