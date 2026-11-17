# SSH connections (plan)

Goal: an **SSH** page on the connect screen that works like JetBrains Gateway's, so IDEate can replace Gateway for
any backend reachable over SSH, including a Mac backend shown on the phone:

1. Add a host (address, port, user, how to log in).
2. IDEate lists the IntelliJ installs on that host.
3. The host appears in the SSH page with its recent projects under it, also ones opened from Gateway or another
   client. Tapping one opens it, starting the backend when it isn't running.

Status: **plan, nothing built yet** (2026-11-17). The findings below come from JetBrains Gateway 2026.3
(`GW-263.7307.184`), read statically (decompiled `lib/intellij.gateway.core.jar` and `lib/intellij.gateway.ssh.jar`,
symbol and string tables of the Go worker in `lib/remote-dev-workers/`), from Gateway's log and settings, and from
the files on the phone and the Mac. **Seen** = read in code, binaries, logs or files. **Inferred** = reasoned
from names or behaviour.

## What Gateway does

### Saved connections

| File (in `~/Library/Application Support/JetBrains/JetBrainsGateway2026.3/options/`) | Holds | Example |
|---|---|---|
| `sshConfigs.xml` | one `sshConfig` per host: id, host, port, username, `authType`, `useOpenSSHConfig` | `host="192.168.0.107" port="8022" username="safeuq" authType="OPEN_SSH" id="586e63d9-…"` |
| `sshRecentConnectionsHost.xml` | the config ids shown in the SSH page | `586e63d9-…` |
| `sshRecentConnections.v2.xml` | per config id: projects with path, product code, IDE path and build, date | `projectPath=…/Xflow/xtool`, `pathToIde=…/intellij/idea-IU-263.7307.200`, `date=1794893545672` |

The last file is only a cache, shown as placeholder rows until the host answers. On every refresh Gateway
replaces a config's list with what the host reports (`LocalRecentConnectionsManager.replaceAll`). Nothing is
merged across configs. **Seen.**

### New connection dialog

- **Fields:** host, port (default 22), user, and an optional private key file.
- **Log-in methods** (`AuthType`): `OPEN_SSH`, `KEY_PAIR` and `PASSWORD`.
  - `OPEN_SSH` uses `~/.ssh/config` and the agent. It's the default for new configs.
  - `KEY_PAIR` is an OpenSSH or PuTTY key file.
  - `PASSWORD`: the password and any key passphrase are asked in a dialog that can save them permanently or until
    restart.
- **Connection check:** 5 s timeout (`gateway.ssh.connection.check.timeout`).
- **Host keys:** an OpenSSH-like `known_hosts` check with the modes YES, NO, ASK and ACCEPT_NEW. It asks before
  accepting an unknown or changed key.
- **SSH library:** SSHJ. The log shows `SSH-2.0-IntelliJ__Gateway_GW-263.7307.184__SSHJ_0.38.1_SNAPSHOT`.

**Seen.**

### Commands on the host: Gateway's worker

Gateway uploads its own Go program, `remote-dev-worker`, to each host and runs everything through it:
- **Upload:** SFTP to `<RemoteDev>/remote-dev-worker/remote-dev-worker_<sha256>`, then `chmod 755`, then the
  upload is checked with `get-sha256`.
- **How it's called:** `<$SHELL> -lc "echo REMOTE_EXEC_OUTPUT_MARKER_ && <worker> --name=value …"`. Output
  before the marker line (motd and rc-file noise) is dropped.
- **Replies:** one JSON line, for example `{"type":"multi","data":["{…}", …]}` or `{"type":"single","data":"…"}`.
  An error comes back as `{"type":"error","errorCode":"…","data":"…"}`.

**Seen.** The commands that matter here:

| Command | Arguments | Answer |
|---|---|---|
| `installed-ides` | none | `multi` of `{idePath, linkPath, productInfo}`; `productInfo` is the text of `product-info.json` |
| `recent-projects-data` | none | `single`: `{rawStatuses:[…], alivePids:[…], installedIdes:[…]}`; each status is an `UnattendedHostStatus` |
| `active-projects` | none | `multi` of the running backends' statuses |
| `host-status` | `--ide-path --project-path` | `single`: the backend's `UnattendedHostStatus`, or exit 14 while it starts |
| `host-launch` | `--ide-path --project-path --stdout-file --stderr-file` | starts the backend detached |
| `list-files` | `--root --max-depth [--dirs-only=true]` | `multi` of `{path, isDir}`; the folder browser |
| `remove-recent-project` | `--project-path --product-code` | deletes the host's recent entry ("recent status file and recents.json") |

The worker has one build per OS and CPU (`linux-arm64`, `darwin-arm64`, …). The phone already has
`~/.cache/JetBrains/RemoteDev/remote-dev-worker/remote-dev-worker_37fb36…`, the `linux-arm64` one, uploaded by
Gateway on 2026-11-16.

### Where the data lives on the host

All of it is under the RemoteDev cache folder. **Seen** on the phone and the Mac:

| Host | RemoteDev folder |
|---|---|
| Linux, Termux | `${XDG_CACHE_HOME:-~/.cache}/JetBrains/RemoteDev` (phone: `/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev`) |
| macOS | `~/Library/Caches/JetBrains/RemoteDev` |

| Sub-folder | What | Example |
|---|---|---|
| `dist/` | IDEs Gateway downloaded | empty on the phone and the Mac |
| `userProvidedDist/` | symlinks to IDEs registered with `remote-dev-server.sh registerBackendLocationForGateway` | phone: `_data_data_com.termux_files_home_intellij_idea-IU-263.7307.200 -> …/intellij/idea-IU-263.7307.200`; Mac: `_Users_safeuq_Applications_IntelliJ IDEA.app_Contents -> /Users/safeuq/Applications/IntelliJ IDEA.app/Contents` |
| `active/<pid>` | a running backend's status, written by the backend | `joinLink`, `idePath`, `projects[]` |
| `recent/<pid>`, `recent/<pid>_<hash>` | status snapshots of every project a backend served; the source of "recent projects" | phone: `32232_-1431083394` for `…/Xflow/xtool` |

A status (`UnattendedHostStatus`) has `appPid`, `appVersion`, `idePath`, `joinLink` and `projects[]`. Each project
has `projectPath`, `dateLastOpened` (epoch ms), `joinLink` and `controllerConnected`.

The backend writes these files, not Gateway. So the recent list is the same from every client, which is why a new
Gateway connection to the phone immediately listed "smoke" with the date from the old connection.

### Opening a project

1. **OS and CPU:** `uname -sm`. Gateway allows only Linux by default; macOS and Windows hosts are behind registry
   flags that are off by default.
2. **Is a backend running?** `host-status`. A good status is `unattendedMode` and not `backendUnresponsive`, with
   a join link.
3. **If not, start one:** `host-launch`, then poll `host-status` once a second. On a timeout, show the reason from
   stderr between `###FailureReason###` and `###FailureReasonEnd###`.
4. **The join link** comes from that status.
5. **Port forwarding:** Gateway listens on `127.0.0.1` at the backend's port if that's free (5990), else on a
   random port (57085 on 2026-11-17). Every accepted client connection opens a new SSH tunnel to the backend's
   port. The link the client gets is the same link with the local port: `tcp://127.0.0.1:57085#jt=…&p=IU&fp=…`.
6. **Restarts:** a monitor watches the backend's pid. When the backend restarts, it reads `host-status` again and
   points the forward at the new port.

The worker builds the exact command line for starting the backend; it can't be read statically. Gateway's older
shell path runs `nohup <ide>/bin/remote-dev-server.sh run <project> >> stdout.log 2>> stderr.log`.

### Refreshing

While the SSH page is open, Gateway asks each host for `recent-projects-data` and `active-projects`. It does this
every 10 s for reachable hosts and every 30 s for unreachable ones.

## Decisions (the user, 2026-11-17)

| Question | Answer |
|---|---|
| Log-in methods | all four: a key IDEate generates; password; an imported private key file; on the desktop, `~/.ssh/config` and the agent |
| Host systems | Linux, Termux and macOS (Windows later) |
| Commands on the host | Gateway's worker when it is already on the host, else IDEate's own shell commands. We don't ship JetBrains' worker in the app: it's their proprietary binary |
| Projects not in the recent list | a folder browser, like Gateway's |

## Design

**Library:** SSHJ, the library Gateway uses. It's pure Java and builds on BouncyCastle, which `:compose` already
has (`bcpkix-jdk18on 1.82`). A spike checks it on Android first (step 1): BouncyCastle as provider, Ed25519, and
the APK size.

**Code** in `:compose`, package `intellijandroid.client.ssh`. The platform parts come in through interfaces, like
`Termux` and `StoredText` today:

| Piece | What |
|---|---|
| `SshHostEntry` + storage | saved hosts as JSON in `StoredText`: id, host, port, user, log-in method, key reference |
| `SshConnector` | SSHJ session: log-in, host-key check, exec, SFTP, local port forward |
| `KeyStore` (platform) | Android: the generated or imported key in app-private storage, encrypted with an Android Keystore key. Desktop: `~/.ideate/`, plus `~/.ssh` and the agent |
| `KnownHosts` | the app's own known-hosts file. Unknown key: ask, showing the SHA-256 fingerprint as `ssh-keygen -l` does. Changed key: refuse with a warning. On the desktop, `~/.ssh/known_hosts` is also read |
| `RemoteHost` | the host commands below, in two implementations |
| `WorkerHost` | used when `<RemoteDev>/remote-dev-worker/remote-dev-worker_*` exists, is executable and passes `worker-check`. Calls `installed-ides`, `recent-projects-data`, `host-status`, `host-launch`, `list-files` in Gateway's way (login shell, marker line, JSON lines) |
| `ShellHost` | POSIX `sh` over SSH, for the same answers (next table) |
| `SshForward` | Gateway's forwarder: a persistent listener on `127.0.0.1` (backend port, else random), one SSH tunnel per accepted connection, re-pointed when the backend restarts |

`ShellHost` commands:

| `RemoteHost` call | Shell command | Answer |
|---|---|---|
| `os()` | `uname -sm` | `Linux aarch64` → Linux/ARM_64 |
| `remoteDevDir()` | Linux: `echo "${XDG_CACHE_HOME:-$HOME/.cache}/JetBrains/RemoteDev"`; macOS: `$HOME/Library/Caches/JetBrains/RemoteDev` | path |
| `installedIdes()` | for each entry of `dist/*` and `userProvidedDist/*`: resolve the symlink, read `product-info.json` (`<ide>/product-info.json`, or `<app>/Contents/Resources/product-info.json` on macOS), keep it if `bin/remote-dev-server.sh` exists | `buildNumber`, `productCode`, `version`, IDE path |
| `recentProjects()` | `cat` every file in `recent/` and `active/`; `kill -0 <pid>` for the active ones | statuses; like Gateway, only statuses with one project are kept, dated by `dateLastOpened` |
| `status(ide, project)` | `<ide>/bin/remote-dev-server.sh status <project>` (prints `STATUS:` and JSON; about 1.4 s on the phone) | `UnattendedHostStatus` |
| `launch(ide, project)` | `nohup <ide>/bin/remote-dev-server.sh run <project> >> <logs>/stdout.log 2>> <logs>/stderr.log &`, then `status` once a second | join link |
| `listFolders(path)` | the folder script `Termux.LIST_FOLDERS` already uses, run over SSH | sub-folders, with flags for project and has-folders |
| `removeRecent(project)` | delete that project's `recent/` files | none |

Termux on the phone already works this way: its `remote-dev-server.sh` is the `intellij-backend` wrapper, which
sets `IDEA_JDK` and the other variables.

**UI:** a third sidebar entry, **SSH**, between "This phone/computer" and "Link", using Gateway's `ssh.svg`. The
phone page gets a device icon instead.

| Part | Like Gateway |
|---|---|
| Header | "SSH Connections" with a **New Connection** button |
| Host groups | `user@host:port` with refresh and ⋮ (edit, remove), and its recent projects underneath: path, **Running** with a stop square, or **Last active** |
| New connection | host, port 22, user, log-in method (generated key with copy-public-key, password, key file, desktop agent), **Check connection and continue**, then the host-key prompt, then the IDEs found |
| Open a project | pick the IDE (if more than one), then the folder browser, then Gateway's start popup ("Starting the remote IDE backend", Cancel) |
| Refresh | 10 s while the page shows, 30 s for unreachable hosts |

**History:** a new `ConnectionKind.SSH`. An entry stores the host id, IDE path, project path, the link and the
client identity. A tap resumes with the saved identity, and when that's refused it asks the host for a fresh link,
the way Termux entries do now.

## Steps

| # | Step | Who | Depends on |
|---|---|---|---|
| 1 | Spike: SSHJ on Android and desktop; key, password and agent log-in; exec; local forward. Measure the APK size | intellij-android-ui | |
| 2 | `RemoteHost` with `ShellHost` and `WorkerHost`, tested against the Mac, Termux on the phone and a Linux container | intellij-android | 1 (interface first, so 2 and 3 can run in parallel) |
| 3 | Saved hosts, `KeyStore` (Android Keystore, desktop), `KnownHosts`, `SshConnector` log-in methods | intellij-android-ui | 1 |
| 4 | SSH page, new-connection dialog, host groups, refresh | intellij-android-ui | 2, 3 |
| 5 | Open project: IDE pick, folder browser over SSH, start, forward, history entry, reconnect after a backend restart | split between the sessions | 4 |
| 6 | Docs (this file becomes the description), emulator and phone tests, Mac backend from the phone | both | 5 |

Test hosts:
- **The Mac:** needs **Remote Login** switched on (System Settings → General → Sharing). That's the user's setting.
- **Termux on the phone:** SSH on 8022, at 192.168.0.107 since 2026-11-17.
- **A Linux container:** sshd in Docker on the Mac.

## Open questions

- Where the `recents.json` that `remove-recent-project` deletes lives: not under `RemoteDev/` on the phone or the
  Mac.
- Which marker file makes an IDE in `dist/` "properly installed" (`GetExpandSucceededMarkerPath`, name not
  readable). It doesn't matter until IDEate downloads IDEs, which it won't.
- The exact command line `host-launch` uses. `ShellHost` uses Gateway's older shell form, which the phone's
  `intellij-backend` wrapper also accepts.
- Whether `controllerConnected` should offer "Take over?" for SSH hosts too, as Termux entries do. Probably yes.
