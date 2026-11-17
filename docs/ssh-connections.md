# SSH connections (plan)

Goal: an **SSH** page on the connect screen that works like JetBrains Gateway's, so IDEate can replace Gateway for
any backend reachable over SSH, including a Mac backend shown on the phone:

1. Add a host (address, port, user, how to log in).
2. IDEate lists the IntelliJ installs on that host.
3. The host appears in the SSH page with its recent projects under it, also ones opened from Gateway or another
   client. Tapping one opens it, starting the backend when it isn't running.

Status, 2026-11-17:
- **Step 1, done:** the SSH layer in `compose/.../ssh/` (`SshSession`, `SshKeys`, the `RemoteHost` interface).
- **Step 2, done:** `RemoteHost` in two implementations, `WorkerHost` and `ShellHost`, picked by `RemoteHosts`.
- **Step 3, done:** saved hosts, secrets, known hosts and the log-in methods.
- **Step 4, done:** the SSH page, the new-connection and Choose IDE dialogs, the folder browser over SSH, and
  the open-project flow.
- **Step 2:** waits for `RemoteHosts.forSession` (intellij-android, #16). The findings below come from JetBrains Gateway 2026.3
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

## Step 1: the SSH layer (done)

`SshSession` (SSHJ 0.41.2) opens a connection with a password or an OpenSSH private key. It asks a
`HostKeyCheck` about every host key, giving the type and OpenSSH's SHA-256 fingerprint. It runs commands
directly, or through the login shell with Gateway's marker line, and forwards a local port Gateway's way.
`SshKeys.generateEd25519` makes the key pair IDEate offers: the OpenSSH private key and an `authorized_keys`
line.

Checked on 2026-11-17 against a Linux test host, `lscr.io/linuxserver/openssh-server` in Docker on the Mac:

| Check | Desktop (`SshSessionTest`) | Android (emulator, Pixel 3a API 34) |
|---|---|---|
| Password log-in, `uname -sm` in the login shell | `Linux aarch64` | `Linux aarch64` |
| Login-shell output after the marker, quoting | `echo one; echo 'it'\''s'` gives `one`, `it's` | |
| Existing key file log-in | ok | |
| Log-in with a key IDEate generated, after adding it to `authorized_keys` | ok | ok |
| Forward to the host's loopback (its own SSH port) | banner `SSH-2.0-OpenSSH_10.3` | local 2222, same banner |
| Host key fingerprint | | `SHA256:sXrwbXg8SMoVJG1Nyj3CNK+CqAeD5MnUk8KYDOq33dY`, the same as `ssh-keyscan … \| ssh-keygen -lf -` |

Found on the way:
- **BouncyCastle on Android.** SSHJ asks the JCA for provider "BC". Android ships a cut-down provider under that
  name, so `SshSession` swaps in the full BouncyCastle. It's added last, so lookups by algorithm (the TLS
  connection's) still find the platform's providers first. `ClientCertificate` keeps its own instance.
  BouncyCastle moves from 1.82 to 1.84, the version SSHJ 0.41.2 is built against.
- **The forward's local port.** Java's `ServerSocket` sets `SO_REUSEADDR`. On macOS that lets the bind succeed
  on a port another program already listens on: our 127.0.0.1:2222 shadowed Docker's 2222. With reuse off, a
  busy port fails the bind and the forward takes a free one, as Gateway's does.
- **Forwarding needs the server's permission.** The linuxserver image ships with `AllowTcpForwarding no`; the
  test host sets it to `yes`. Termux's sshd allows forwarding, since Gateway uses it there. A refused tunnel
  closes the local connection at once, so `SshSession` users must report that clearly.
- **APK size:** +0.9 MB (46.9 to 47.9 MB) for SSHJ, `bcprov` and SLF4J.

Running the integration test (skipped unless the variables are set):

```sh
IDEATE_SSH_TEST_HOST=127.0.0.1 IDEATE_SSH_TEST_PORT=2222 IDEATE_SSH_TEST_USER=dev \
  IDEATE_SSH_TEST_PASSWORD=<password> IDEATE_SSH_TEST_KEY=<path to id_ed25519> ./gradlew :compose:jvmTest --tests '*SshSessionTest*'
```

## Step 2: commands on the host (done)

`RemoteHosts.forSession(session)` gives the `RemoteHost` for one connected host, usable for the session's life.
It looks in `<RemoteDev>/remote-dev-worker/` (newest first, `ls -t`) for a worker that is executable and passes
`worker-check`, and uses `WorkerHost` with it, else `ShellHost`. Both run every command through
`SshSession.execInLoginShell`. They read the same files, so they give the same answers. The integration test
checks that on the phone.

| Call | `WorkerHost` | `ShellHost` |
|---|---|---|
| `os()` | through the shell: `uname -sm` | `uname -sm` |
| `remoteDevDir()` | through the shell | Linux `${XDG_CACHE_HOME:-$HOME/.cache}/JetBrains/RemoteDev`, macOS `$HOME/Library/Caches/JetBrains/RemoteDev` |
| `installedIdes()` | `installed-ides` | `ShellHost.INSTALLED_IDES`: each `dist/*` and `userProvidedDist/*`, resolved with `pwd -P`, that has `bin/remote-dev-server.sh`; `product-info.json` from the IDE folder or `Resources/` (macOS) |
| `recentProjects()` | `recent-projects-data` (`rawStatuses`, `alivePids`) plus `active-projects` | `ShellHost.RECENT_STATUSES`: every file in `recent/` and `active/`, alive when `kill -0` reaches the pid in its name |
| `status()` | `host-status`; exit 14 (starting) gives null | `<ide>/bin/remote-dev-server.sh status <project>`, the JSON after `STATUS:` |
| `launch()` | `get-logs-dir`, then `host-launch` with `<logs>/stdout.log` and `stderr.log` | `mkdir -p <logs> && nohup <ide>/bin/remote-dev-server.sh run <project> >> <logs>/stdout.log 2>> <logs>/stderr.log < /dev/null &` |
| `listFolders()` | through the shell (the worker's `list-files` has no project or has-folders flags) | `Termux.LIST_FOLDERS` |
| `removeRecent()` | `remove-recent-project` | removes the `recent/` files whose one project is that path and whose `appVersion` starts with the product code |

**One login shell per refresh (2026-11-17).** Every `execInLoginShell` starts the user's login shell, which costs
0.3 to 2 s on the phone (zsh with oh-my-zsh). The SSH page's first load took about 20 s. So:
- `RemoteHosts.forSession` runs one probe script (`RemoteHosts.PROBE`). It prints `uname -sm`, the RemoteDev
  folder, `$HOME` and the newest worker that passes `worker-check`. Before, that was 4 login shells: `ls`,
  `worker-check`, `uname` and the folder. `RemoteHost.home()` gives the probe's `$HOME`, so the page no longer
  asks for it separately.
- `RemoteHost.projectsAndIdes()` gives recent projects and IDEs in one call. `WorkerHost` uses a single
  `recent-projects-data`, which already holds `rawStatuses`, `alivePids` and `installedIdes`. It leaves out
  `active-projects`: a running backend writes its `recent/` file when it opens the project, and `alivePids`
  marks it running. `ShellHost` runs `INSTALLED_IDES` and `RECENT_STATUSES` as one script.
- Skipping `worker-check` when the same worker already passed isn't needed. It now runs inside the probe's one
  login shell, and the worker itself starts in milliseconds.

| | Login shells before | After |
|---|---|---|
| Connect (`forSession`) | 4 | 1 |
| Refresh (SSH page, every 10 s) | 3 with the worker (`recent-projects-data`, `active-projects`, `installed-ides`), 2 with the shell | 1 |
| New connection's `$HOME` | 1 | 0 (from the probe) |

Measured on the phone from the desktop (`RemoteHostSshTest`, `WorkerHost`, 2026-11-17, three runs each):

| | Before (home network) | After (Mac on the phone's hotspot) |
|---|---|---|
| One login shell (`true`) | 328 to 425 ms | 258 to 290 ms |
| Connect (`forSession`) | 2351 to 3417 ms | 333 to 428 ms |
| Refresh | `recentProjects` + `installedIdes`: 1107 to 1246 ms | `projectsAndIdes`: 279 to 440 ms (the old pair in the same runs: 948 to 1043 ms) |

The networks differ, but a login shell costs about the same on both. Connecting is now about one login shell
instead of three seconds, and a refresh about a third of what it was. Against the Linux container (a fast local
shell), `forSession` took 11 to 30 ms, about one login shell (7 to 26 ms).

Shared rules (`HostData`):
- **Recent projects:** Gateway's `fromStatus`. Only statuses with exactly one project, dated by `dateLastOpened`;
  dropped without it. A project several backends served is listed once, with the newest date, running when any
  of their pids is alive. Newest first.
- **IDEs:** product codes outside Gateway's list are dropped. The name comes from the product code (`IU` →
  "IntelliJ IDEA"), because the worker's `installed-ides` sends a shortened `product-info` without `name`. The
  integration test found that difference.
- **Logs folder:** Gateway's `get-logs-dir`: `<cache>/JetBrains/RemoteDev-<code>/<project path, '/' as '_'>/log`.
  Example: `/data/data/com.termux/files/home/.cache/JetBrains/RemoteDev-IU/_data_data_com.termux_files_home_intellij_smoke/log`.
  It is the RemoteDev folder's path with `-<code>` added. The worker answers it in plain text, not JSON.
- **Ready:** `HostStatus.isReady` is `unattendedMode`, not `backendUnresponsive`, and a link or projects. With no
  backend running, `host-status` and `status` still answer: it's the status command's own process, with
  `unattendedMode: false` and no link, so it isn't ready.
- **Errors:** a worker `error` reply, a missing reply, or a shell command's non-zero exit throw
  `RemoteHostException` with the message.

What the phone's worker (`remote-dev-worker_37fb36…`, linux-arm64) showed on 2026-11-17:

| Command | Seen |
|---|---|
| `worker-check` | `HELLO_STDOUT` and `{"type":"error","errorCode":"CommandError","data":"ERROR_TEST Nothing to worry about"}` on stdout, `HELLO_STDERR` on stderr, **exit 1**. `WorkerHost.passesCheck` looks at the output only |
| a missing flag | a Go `panic: Flag 'product-code' not found.` on stderr, no JSON |
| `get-logs-dir` | needs `--product-code` and `--project-path` |
| `get-common-logs-dir` | needs `--ide-path`; gives `~/.cache/JetBrains/IntelliJIdea2026.3/log` |
| `list-files --root=/nonexistent` | `{"type":"error","errorCode":"CommandError","data":"open /nonexistent: no such file or directory"}`, exit 0 |
| `recent-projects-data` | 3 files in `recent/`, 2 `rawStatuses`: the file without projects (`30916`) is left out |
| `--help` | the command list (exists, pid-alive, host-status, host-launch, list-files, get-logs-dir, worker-check, …) and an `UnknownCommand` error |

Tests:

| Test | What | Result, 2026-11-17 |
|---|---|---|
| `RemoteHostTest` (9) | parsing with replies shaped like the phone's; `RemoteHosts` picks the worker and calls it Gateway's way; falls back to the shell; status null on exit 14 | pass |
| `ShellHostTest` (3) | the real scripts with the Mac's `/bin/sh` in a temporary `HOME`: IDEs through a `userProvidedDist` link, recent and active files, `removeRecent`, `status` and `launch` with a fake `remote-dev-server.sh`, folders | pass |
| `RemoteHostSshTest` (read only, skipped without `IDEATE_SSH_TEST_HOST`) | `RemoteHosts.forSession` against a real host; `WorkerHost` and `ShellHost` must agree; folders; `status` of the newest recent project | phone (Termux): `WorkerHost`, one IDE (`idea-IU-263.7307.200`), two recent projects (xtool, smoke), not ready (nothing ran). Linux container: `ShellHost`, no IDEs, no recents |
| macOS, by hand | `ShellHost.INSTALLED_IDES` with `sh` on the Mac's RemoteDev folder | `/Users/safeuq/Applications/IntelliJ IDEA.app/Contents` through `userProvidedDist/_Users_safeuq_Applications_IntelliJ IDEA.app_Contents`, `product-info.json` from `Contents/Resources` |

Not tested yet:
- `launch` on a real host. The phone needs the user's go; the Mac needs Remote Login.
- `status` and `launch` on the Mac over SSH. A plain `remote-dev-server.sh` there hands its command to the
  user's running desktop IntelliJ (see `split-protocol.md`, "Running a backend on the Mac for client work"). So a
  Mac host may need its own config folders, or no desktop IntelliJ running. Check this when Remote Login is on.

## Step 3: hosts, secrets, known hosts, log-in (done)

| Piece | What it does | Example |
|---|---|---|
| `SshHosts`, `SshHostEntry` | the SSH page's hosts, as JSON in `StoredText` | `{"id":"586e…","host":"192.168.0.107","port":8022,"user":"u0_a404","method":"GENERATED_KEY"}`, shown as `u0_a404@192.168.0.107:8022` (Gateway's `user@host`, `:port` unless 22) |
| `LoginMethod` | `GENERATED_KEY`, `KEY_FILE`, `PASSWORD`, `OPEN_SSH` (desktop) | |
| `SecretStore` | keys and passwords, encrypted | Android: `AndroidSecretStore`, AES-GCM under an Android Keystore key, values in the `ssh-secrets` preferences. Desktop: `FileSecretStore`, files in `~/.ideate/secrets/` readable by the user only |
| `IdeateKey` | one Ed25519 key for the app, made on first use | public line `ssh-ed25519 AAAA… IDEate on Pixel 3a` for the user to add to `~/.ssh/authorized_keys` |
| `KnownHosts` | OpenSSH `known_hosts` lines in `StoredText`, checked with SSHJ's parser. `KNOWN` passes; `UNKNOWN` asks the user with the fingerprint; `CHANGED` is refused unless the user accepts it (`forget`, then `trust`) | `[192.168.0.107]:8022 ssh-ed25519 AAAA…`. On the desktop `~/.ssh/known_hosts` also counts, read only |
| `SshLogins.resolve` | a saved host into host, port, user and the methods to try. A missing password or key raises `MissingSecret`, so the UI asks and calls again | |
| OPEN_SSH | `~/.ssh/config` (`Host` patterns with `*`, `?`, `!`; `HostName`, `Port`, `User`, `IdentityFile`; first value wins), then the agent (`SSH_AUTH_SOCK`, SSHJ's `AgentProxy`), then the identity files, by default `~/.ssh/id_ed25519`, `id_ecdsa`, `id_rsa`. A port typed for the host wins, like `ssh -p`. Keys that can't be read (encrypted, unknown format) are skipped, as OpenSSH does | `Host phone` with `HostName 192.168.0.107`, `Port 8022` gives `192.168.0.107:8022` |

`HostKeyCheck` now gets the `PublicKey`, so the page can save the key it trusts. `SshSession.keyType` and
`fingerprint` give what to show.

Not supported in `~/.ssh/config`: `Match` blocks (skipped), `Include`, and `%` tokens other than `%h` in
`HostName`.

Tests: `SshSetupTest` (6 tests, unit) covers the config, known hosts, saved hosts and log-in. `SshSessionTest`
(3 tests, against the Docker test host) now goes through `KnownHosts`: the first connection is `UNKNOWN` and gets
trusted, the second is `KNOWN`.

## Step 4: the SSH page (done)

The SSH page copies Gateway 2026.3's, captured from the user's Gateway window on 2026-11-17 (read only). The
texts are Gateway's own, from `messages/GatewayBundle.properties` in `intellij.gateway.core.jar`; the host-key
questions come from IntelliJ's `messages/SshBundle.properties`.

| Part | Gateway | IDEate |
|---|---|---|
| Sidebar | Connections: SSH (`expui/nodes/ssh.svg`), then Link | This phone (an inlined phone outline; IntelliJ has no phone icon) or This computer (`expui/nodes/desktop.svg`), SSH, Link. Portrait: three bottom tabs |
| Header | "Recent SSH Projects", **New Project** split button | the same, without the drop-down |
| Host group | `user@host` (`:port` unless 22), then at the right: red "Host unreachable" (Label.errorForeground, Red7 `#DB5C5C`) or "Connecting…" / "Getting projects…" / "Authenticate…"; Reconnect when unreachable, Open SSH Terminal, Open Project (+), the gear menu | the same, without Open SSH Terminal; the gear menu has Remove Host |
| Unreachable host | paths in disabledText (Gray6 `#5A5D63`), no status, no ⋮ | the same |
| Project rows | link-blue path shortened in the middle, "Last active: 52 minutes ago", ⋮ with "Remove from Recent Projects" | the same (`TextOverflow.MiddleEllipsis`) |
| No hosts | "SSH Connection", "Work with a project on a machine reachable using SSH", New Connection | the description and New Connection |
| A host without projects | "Projects will appear after the first connection…" | the same |
| Refresh | every 10 s, unreachable hosts every 30 s | the same, while the page shows |

Dialogs, drawn like IntelliJ's (the folder chooser's frame: 475 dp wide, 32 dp title):

| Dialog | Gateway | IDEate |
|---|---|---|
| Connect to SSH | Username, Host, Port (22), "Specify private key", **Check Connection and Continue** | the same fields, then the log-in method: IDEate key (its public line and **Copy Public Key**), Specify private key (paste the key and its passphrase), Password (with Save password), and on the desktop "OpenSSH config and authentication agent". The host is saved only once it logs in |
| Host key, unknown | "The authenticity of host '{0}' cannot be established. {1} key fingerprint is {2}. Are you sure you want to continue connecting?" | the same text in the page, Continue / Cancel |
| Host key, changed | "Warning: remote host identification has changed! …" | the same; accepting replaces IDEate's entry |
| Choose IDE and Project | "IDE version:" (installed ones marked "installed"), "Project directory:" with a browse button, **Start IDE and Connect** | the installed IDEs only (no downloads), the path (`~` allowed), **…** opens the folder browser |
| Choose Remote Project | the remote file browser from `/` | the shared FolderChooser with `root` `/` (or Termux's files folder when `$HOME` is in Termux) and Home = the host's `$HOME` |
| Authentication | "Authenticating to:", "Password:" or "Key passphrase for the file:", **Authenticate** | the same |
| Progress | "Checking for already running IDE Backend", "Starting the remote IDE Backend", "Waiting for the IDE Backend to start", "Performing the SSH port forwarding" | the same titles in the start popup, with Cancel |

Opening a project (`SshController.openProject`):
1. `status`.
2. If it isn't ready, `launch`, then `status` every second, for up to 3 minutes.
3. Take the join link.
4. Forward its port (`SshSession.forward`).
5. Rewrite the link to `tcp://127.0.0.1:<local port>#<same fragment>`.
6. Connect with `ConnectionKind.SSH`.

SSH connections are not saved in history: the SSH page lists the host's projects itself, and a forwarded port
is gone after a restart.

Storage: on the desktop, `~/.ideate/ssh-hosts.json`, `~/.ideate/known_hosts` and `~/.ideate/secrets/`. On
Android, the `ssh` preferences (`hosts`, `known_hosts`) and the Keystore-encrypted `ssh-secrets`. The key's
comment is the device: `IDEate on Pixel 3a` (Android `Build.MODEL`), or the computer's host name.

Checked on 2026-11-17:
- **Desktop, against the Docker test host:**
  - with a saved password host, IntelliJ's unknown-host question showed the right fingerprint;
  - Continue saved `[127.0.0.1]:2222 ssh-ed25519 …` to `~/.ideate/known_hosts`, and the host became ready;
  - the Choose IDE dialog said no IDE is installed;
  - the folder browser listed `/` and revealed `$HOME` (`/config`);
  - Connect to SSH showed the generated key.
- **Emulator:** three bottom tabs; Connect to SSH without the OpenSSH option; the same public key on both
  openings, so the Keystore encryption round-trips.

- **Desktop, against the phone with the real `RemoteHosts` (92d71ae), read only:**
  - an OPEN_SSH host `safeuq@192.168.0.107:8022` logged in with the Mac's `~/.ssh` key, without a question,
    because `~/.ssh/known_hosts` already has the phone;
  - it listed the same two projects as Gateway: "…/Xflow/xtool, Last active: 23 minutes ago" and
    "…/intellij/smoke, Last active: Yesterday 1:02 pm";
  - the first load took about 20 s: finding and checking the worker plus three worker calls, each a phone login
    shell.

"Last active" follows IntelliJ's `DateFormatUtil.formatPrettyDateTime`, as Gateway does (UtilBundle):
- within an hour: "Moments ago", "A minute ago", "52 minutes ago", "1 hour ago";
- then "Today" or "Yesterday" and the time in the locale's short format;
- else the short date and time.

The JDK puts a narrow no-break space (U+202F) before am/pm (`LastActiveTest`).

Not checked yet: opening a project end to end. It needs `RemoteHosts` (step 2) and a host with an IDE.

## Steps

| # | Step | Who | Depends on |
|---|---|---|---|
| 1 | Spike: SSHJ on Android and desktop; key and password log-in; exec; local forward; APK size. **Done**. Agent log-in moves to step 3 | intellij-android-ui | |
| 2 | `RemoteHost` with `ShellHost` and `WorkerHost`, tested against the Mac, Termux on the phone and a Linux container. **Done** (launch and the Mac over SSH still untested, see step 2) | intellij-android | 1 (interface first, so 2 and 3 can run in parallel) |
| 3 | Saved hosts, `SecretStore` (Android Keystore, desktop), `KnownHosts`, log-in methods. **Done** | intellij-android-ui | 1 |
| 4 | SSH page, new-connection dialog, host groups, refresh. **Done** (open project not yet run end to end) | intellij-android-ui | 2, 3 |
| 5 | Open project: IDE pick, folder browser over SSH, start, forward, history entry, reconnect after a backend restart | split between the sessions | 4 |
| 6 | Docs (this file becomes the description), emulator and phone tests, Mac backend from the phone | both | 5 |

Test hosts:
- **The Mac:** needs **Remote Login** switched on (System Settings → General → Sharing). That's the user's setting.
- **Termux on the phone:** SSH on 8022. Its address changes with the network: 192.168.0.107 on the home Wi-Fi, 172.22.26.84 when the Mac is on the phone's hotspot (2026-11-17).
- **A Linux container:** sshd in Docker on the Mac.

## Open questions

- Where the `recents.json` that `remove-recent-project` deletes lives: not under `RemoteDev/` on the phone or the
  Mac.
- Which marker file makes an IDE in `dist/` "properly installed" (`GetExpandSucceededMarkerPath`, name not
  readable). It doesn't matter until IDEate downloads IDEs, which it won't.
- The exact command line `host-launch` uses. `ShellHost` uses Gateway's older shell form, which the phone's
  `intellij-backend` wrapper also accepts.
- Whether `controllerConnected` should offer "Take over?" for SSH hosts too, as Termux entries do. Probably yes.
