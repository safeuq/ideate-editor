# Setting up the MCP servers

Two MCP servers let an agent (Claude Code, or any MCP client) look at and drive the running desktop app. Both work on the app started with hot reload, so the agent can change code, reload and look again without a restart or a new backend connection. Subagents can have their own (section 7).

| Server | Comes from | Talks over | What it can do | Can't do |
|---|---|---|---|---|
| `compose-hot-reload` | the Compose Hot Reload plugin (`./gradlew :desktop:hotMcpServer`), started through [compose-hot-reload-proxy](https://github.com/safeuq/compose-hot-reload-proxy) | stdio (the client starts it) | screenshot, read the Compose semantics tree, click or type into a node by id, scroll, read the app's log, reload, restart | anything inside backend-painted parts (the Run toolbar, Git windows, backend dialogs and popups): they have no semantic nodes |
| `ideate-window` | this repo: `AgentMcpServer.kt`, started inside the app by `-PagentMcpPort` | HTTP on `127.0.0.1` | move, click, drag, scroll, type and press keys at points in the main window or a popup window; screenshot every window | read the semantics tree, reload |

Use them together: `compose-hot-reload` for Ideate's own UI and for reloading, `ideate-window` for hover and clicks inside backend-painted content. Neither moves your mouse cursor or takes keyboard focus from what you are doing. The tools of `ideate-window` are listed in [`client.md`](../client.md), "Agent MCP server".

## 1. Before you start

- The desktop build works: `local.properties` at the repo root has `jetbrains.client.lib`, as in [`client.md`](../client.md), "Run".
- `JAVA_HOME` is a JetBrains Runtime 25 or newer. IntelliJ's bundled one works:
  `/Users/you/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home`.
  Hot reload stops with a message when it isn't.
- A backend to connect to, and its join link, e.g. `tcp://127.0.0.1:5992#jt=4f1c…&fp=9a0e…`. A join token works for one client: start a fresh backend for each new client ([`split-protocol.md`](../split-protocol.md), "Running a backend on the Mac for client work").

## 2. Get the proxy

The plugin's server only sees the app started from the folder it runs in, and its documented registration (`./gradlew hotMcpServer`) works only when Claude Code was started inside that folder. [compose-hot-reload-proxy](https://github.com/safeuq/compose-hot-reload-proxy) removes that limit. It is registered once. When Claude Code starts inside a Gradle project, it uses that project; otherwise, or to switch, its `use_project(path, task)` tool starts the plugin's server in the checkout or worktree you name. All other tools are the plugin's own, passed through.

```sh
git clone https://github.com/safeuq/compose-hot-reload-proxy.git /path/to/compose-hot-reload-proxy
```

It needs Python 3.9 or newer, nothing else.

## 3. Register the servers (once)

In Claude Code, from the folder you start Claude Code in. `--scope local` keeps them to that folder and to you; `--scope user` makes them available everywhere.

```sh
claude mcp add --scope local compose-hot-reload \
  -e JAVA_HOME="/Users/you/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" \
  -- python3 /path/to/compose-hot-reload-proxy/compose_hot_reload_proxy.py

claude mcp add --scope local --transport http ideate-window http://127.0.0.1:5979/mcp
```

`claude mcp list` should show both. Claude Code loads new servers when a session starts; in a running session, `/reload-plugins` or `/mcp` picks them up.

Other MCP clients: `compose-hot-reload` is the command above (stdio); `ideate-window` is a Streamable HTTP server at `http://127.0.0.1:5979/mcp`.

## 4. Start the app

```sh
cd /path/to/ideate-editor    # or any worktree of it
export JAVA_HOME="/Users/you/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
./gradlew :desktop:hotRunAsync -PjoinLink='tcp://127.0.0.1:5992#jt=…&fp=…' -PagentMcpPort=5979
```

- `-PjoinLink` connects at once (hot run has no `--args`).
- `-PagentMcpPort=5979` turns `ideate-window` on. Without it the server doesn't start at all. It listens on `127.0.0.1` only. The port must match the URL you registered.
- The app's output goes to `desktop/build/run/main/hotRun.stdout.txt`.

## 5. Check it works

| Ask the agent to call | Expect |
|---|---|
| `compose-hot-reload` `use_project` with `{"path": "/path/to/ideate-editor", "task": ":desktop:hotMcpServer"}` (not needed when Claude Code was started in that folder) | `Compose Hot Reload tools now use …`; the first time in a folder can take a few minutes |
| `compose-hot-reload` `status` | `{"connected": true, "reloadState": "ok", …}` |
| `compose-hot-reload` `take_screenshot` | the app window |
| `ideate-window` `list_windows` | `[{"index": 0, "kind": "main", "title": "Ideate", "bounds": {…}}]` |

Or without an agent:

```sh
curl -s -X POST http://127.0.0.1:5979/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_windows","arguments":{}}}'
```

## 6. Day to day

1. Edit code under `compose/` or `desktop/` in the folder the app runs from.
2. Reload: `compose-hot-reload` `reload`, or `./gradlew :desktop:hotReloadMain`.
3. Look and drive: `take_screenshot` / `get_semantic_tree` / `click`, or `ideate-window` `move` / `click` / `screenshot` for backend-painted parts.

Composables and code called after the reload use the new code. Code that ran once when the connection opened, such as most of `ClientSession`'s protocol binding, keeps the old behaviour until the app reconnects: restart it (`compose-hot-reload` `restart`, or stop it and start it again with a fresh join link).

Stop the app when done: close its window, or `kill` its process id (`ps` shows it with the run folder in its command line). Stop the backend you started for it too.

## 7. Subagents

Subagents share the main session's servers, and so the one project its `compose-hot-reload` is using. To give each subagent its own Compose Hot Reload server, declare one in a subagent definition, `~/.claude/agents/ideate-ui.md`. The server starts with the subagent and stops when it finishes; `ideate-window` is shared by name. Give the subagent's server its own name (`compose-ui` below): an inline server with the same name as one of the main session's is easy to confuse with it.

```markdown
---
name: ideate-ui
description: Implements a UI change in the Ideate desktop client and checks it live with hot reload and the MCP tools.
model: sonnet
mcpServers:
  - compose-ui:
      type: stdio
      command: python3
      args: ["/path/to/compose-hot-reload-proxy/compose_hot_reload_proxy.py"]
      env:
        JAVA_HOME: /Users/you/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home
  - ideate-window
---

Start the app from your worktree with hotRunAsync … -PagentMcpPort=5979, then call
compose-ui use_project with the worktree's path and task ":desktop:hotMcpServer".
```

To hand a UI task to one: create its worktree, copy `local.properties` in, and name the worktree in the brief. A running Claude Code session picks up a new or changed agent file without a restart, but not at once: a subagent spawned within a minute or so of the change can still get the previous version.

`ideate-window` listens on one port (5979), so only one subagent at a time can drive a live app with it.

## Troubleshooting

| What you see | Why | Fix |
|---|---|---|
| `status` says `{"connected": false}` | no app is running, or it was started from another folder than the one `compose-hot-reload` uses | start the app from that folder, or call `use_project` with the app's folder |
| a tool answers "... call use_project" | Claude Code wasn't started in a Gradle project and no folder was chosen | call `use_project` |
| `ideate-window` tools fail to connect | the app isn't running, or was started without `-PagentMcpPort`, or with another port | start it with `-PagentMcpPort=5979` |
| `ideate-window` `press` does nothing to a popup | keys go to the main window unless told otherwise | pass `window: 1` (the popup's index from `list_windows`) |
| `type` goes nowhere | text goes to what has Compose focus | `click` the field first |
| hot run stops with "set JAVA_HOME to a JetBrains Runtime 25 or newer" | `JAVA_HOME` is another JDK | set it as in step 1, also in the `-e JAVA_HOME=…` of the registration |
| the app opens on the connect screen, not the project | the join link was used already | start a fresh backend and use its new link |

## Removing them

```sh
claude mcp remove compose-hot-reload -s local
claude mcp remove ideate-window -s local
rm ~/.claude/agents/ideate-ui.md    # if you made it
```
