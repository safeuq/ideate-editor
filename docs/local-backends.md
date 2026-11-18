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
`uname -sm` (`HostOs`), not on where IDEate runs. So a Mac reached over SSH gets the same lookups:

| IDEate on | Backend machine | `uname -sm` | Mac lookups |
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
