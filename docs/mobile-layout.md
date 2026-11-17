# Mobile layout: phone, tablet, portrait, landscape

How the main window (`compose/src/commonMain/kotlin/intellijandroid/client/ui/ClientScreen.kt`, `ToolWindows.kt`) arranges
the editor, the tool windows and the bars on each screen shape. From the user's brainstorm of 2026-11-16
(`optimize-for-portrait.md` next to the repo) and the answers to its questions; the pane layout below was redone on
2026-11-18 from the user's spec, reviewed as a preview first (https://claude.ai/artifact/ChLM2aSm57qt7EESoPh3zR,
"Agreed details" at its end).

## Which layout

The layout is picked from the window's size in dp, measured with `BoxWithConstraints`, so a desktop window or a
split-screen app gets the layout that fits it:

| Window | Layout (`ScreenLayout`) | Example |
|---|---|---|
| taller than wide | portrait (phone and tablet upright) | Pixel 3a 393 × 807, the user's phone 411 × 891, a tablet 800 × 1280 |
| wider than tall, at least 600 dp tall | wide (desktop, tablet sideways) | desktop window 1200 × 800, emulator at `wm density 240` sideways 1480 × 720 |
| wider than tall, under 600 dp tall | compact (phone sideways) | phone sideways 891 × 411 |

600 dp is Android's own tablet line (`sw600dp`, `Configuration.smallestScreenWidthDp`). There is no reliable device
identifier for "tablet"; Android itself uses this size. A desktop window shorter than 600 dp gets the phone's
sideways rules.

The activity no longer pins `sensorLandscape`: it follows the sensor, and `configChanges` keeps the session alive
across a rotation.

## All layouts

- **Main toolbar on top** (32 high in compact mode, 40 in normal; sizes for both densities:
  [`intellij-look.md`](intellij-look.md) section 11), a reduced copy of IntelliJ's: the project widget (blue square with the
  project's initials, `smoke` → `SM`, and its name), then on the right the run widget ("Current File"), Build, Run,
  Debug and More. Only the project name is live; the others show "… isn't supported in this client yet" in the
  status line for 3 s.
- **Tool windows that work**: Project, and Structure ([`structure-protocol.md`](structure-protocol.md)): the tree
  of the selected file, all expanded; a tap selects and moves the caret (IntelliJ's "Navigate with Single Click"),
  a double tap also slides back to the editor on a phone held upright.
- **Six places.** Every tool window has a side (left, right, bottom) and IntelliJ's split flag (`BeToolWindow.sideTool`,
  `WindowInfo.isSplit`), which puts it in the second half of its side. Left and right split top/bottom, the bottom
  splits left/right (`ToolWindowAnchor.isSplitVertically`). `Place` in `ToolWindows.kt`:

  | Place | Windows by default | Upright | Desktop, tablet sideways | Phone sideways |
  |---|---|---|---|---|
  | left top | Project, Commit, Pull Requests | left page, upper half | left pane, upper half | left of the editor |
  | left bottom | Structure | left page, lower half | left pane, lower half | left of the editor |
  | bottom left | Version Control, Problems, Terminal, Services | middle page, in the editor's place, upper half | under the editor, left half | right of the editor |
  | bottom right | none (a bottom window marked split) | middle page, lower half | under the editor, right half | right of the editor |
  | right top | Notifications, AI Chat, Database, Gradle, Maven | right page, upper half | right pane, upper half | right of the editor |
  | right bottom | none (a right window marked split) | right page, lower half | right pane, lower half | right of the editor |

  One window per place is open at a time; opening another of the same place replaces it. A window alone in its
  side (or in the bottom area) takes all of it; two share it (`SplitPane`). Before 2026-11-18 Structure sat on the
  right so one window could be open on each side.
- **Tool window bars**: the places' buttons in groups, a 1 dp line between groups, ⋯ (windows without a button) at
  the end of the left top group. Empty groups and their lines are left out. Every bar scrolls when its buttons don't
  fit. Icons are IntelliJ's `expui/toolwindows/<name>.svg` at the same size as the main toolbar's, as in IntelliJ:
  16 in compact mode (buttons 32 in a 40-thick bar), 20 in normal (buttons 40, bar 48). Only Project and Structure
  open; the rest say they aren't supported.
- **Header line:** when a tool window covers the editor (phone held upright), a 1 dp line in
  `ToolWindow.Header.borderColor` (`#26282C`) runs under its header.
- **Tapping an open window's button closes it**, as on IntelliJ's stripes.
- **Every split can be resized**: the border between two panes (the halves of a side, a side pane against the
  editor, the editor against the bottom area, bottom left against bottom right) drags. A finger shows a pill on the
  border while it moves; a mouse gets the resize pointer and no pill. Each pane keeps at least 60 dp. The sizes are
  IntelliJ's weights (`WindowInfoImpl`: a side or the bottom area 0.33 of the room, a split 0.5) and are kept per
  project (`PaneWeights`, `SplitPane`; [`intellij-look.md`](intellij-look.md) section 17).

The connect screen follows the same rule: upright, its connection kinds (This phone or This computer, Link) are tabs in a bar at
the bottom instead of Gateway's sidebar ([`intellij-look.md`](intellij-look.md) §10).

## Portrait (phone and tablet upright)

- One bar at the bottom, grouped left top | left bottom | bottom left | bottom right | right top | right bottom.
- Three pages side by side, each one screen wide: the left page (left top over left bottom), the middle page (the
  editor, or the bottom windows in its place: bottom left over bottom right) and the right page (right top over right
  bottom). Only one shows at a time.
- A button opens its window and shows its page. Tapping it again closes it; closing the last window of a side page
  goes back to the middle. A button of a window open on another page shows that page.
- **Back to the editor**: the main toolbar ends with a divider and a chevron while a side page or the bottom windows
  show (IntelliJ's `expui/general/chevronRight.svg` on the left page, `chevronLeft.svg` on the right page and on the
  bottom windows). It, and Back, go from a side page to the middle, and from the bottom windows to the editor
  (closing them). The run controls make room for it ("Current File" shortens first); the project widget keeps its
  width up to 200 dp. The tool window headers no longer have a back chevron.
- **Swipes** (the user's answer to question 1):

  | From | Finger moves right | Finger moves left |
  |---|---|---|
  | middle page | left page slides in | right page slides in |
  | left page | nothing | back to the middle |
  | right page | back to the middle | nothing |

  The content follows the finger, like a pager: the left page is to the left of the middle, so dragging right
  pulls it in. A swipe to a side page with nothing open opens the windows last open there (Project at first); a side
  that never had a window doesn't slide in.
- A drag counts as a swipe once it has moved the touch slop and is at least twice as far sideways as up or down.
  Past a third of the width, or a flick faster than 1000 px/s, it moves one page; otherwise it springs back.
- **On the editor, a sideways drag scrolls the editor first** (since 2026-11-17, user report "sideways scrolling
  always switches to tool windows"). The editor scrolls sideways like IntelliJ's: the gutter stays, the text area
  moves, up to the longest line plus two columns. The pager takes a sideways drag only when the editor is already
  at that edge: at the left edge a drag to the right opens the left tool window, at the right edge a drag to the
  left opens the right one. The pager decides once, when the drag passes the touch slop, and keeps that decision
  for the whole gesture. So one swipe either scrolls or switches: a scroll that reaches the edge stops there, and
  the next swipe switches (`SidewaysScroll.canScroll`, `SwipePager`).
  Checked on the emulator (Pixel 3a, `Big.java` with a 360-character line), 2026-11-17 12:06 IST: a drag left
  scrolled the text with the gutter fixed. A long drag right that crossed back to the left edge mid-gesture stayed
  on the editor; the next drag right opened Project. Flinging to the right edge, then one more drag left, opened
  Structure.
- **Tool windows scroll sideways the same way** (since 2026-11-17, user report "A lengthy file name in the project
  window cannot be revealed by scrolling"). The Project and Structure trees scroll sideways when a row is wider than
  the panel: the row's content moves. The selection band stays 12 dp from the right side; its left side follows the
  content (12 dp minus the scroll, going negative), so after a scroll it still covers the shifted icon and name (user
  report with a screenshot, 2026-11-17: the band had stayed 12 dp in and left the icon outside it). The row's edge
  clips it, so the side touching the edge is square, not rounded, as the viewport clips IntelliJ's tree (user
  report, 2026-11-17: the band stopped at 0 with its rounded corners at the edge). Example: scroll 40 dp draws the
  band from -28 dp. The scroll range is
  the widest row seen so far plus the 12 dp side inset. Each tool window keeps its own `SidewaysScroll`, and the
  pager asks the one on the current page with the same rule as the editor:
  | Page | Finger moves left | Finger moves right |
  |---|---|---|
  | left page (Project, Structure) | tree scrolls on; at its right edge, back to the middle | tree scrolls back; at its left edge, nothing |
  | right page | tree scrolls on; at its right edge, nothing | tree scrolls back; at its left edge, back to the middle |

  With two windows on a page, the swipe is the trees' when either can still scroll that way. The sideways layouts
  have no pager; the trees scroll sideways there too (touch drag, trackpad or
  Shift+wheel). Checked on the emulator (Pixel 3a) and the desktop, 2026-11-17 against the Mac backend:
  `AVeryLongClassNameThatDoesNotFitInTheProjectToolWindowAtAllEvenOnTablets` in `src/demo`; a drag left on Project
  scrolled to the name's end and stayed on Project, the next drag left went back to the editor.
- **Flush when alone** (since 2026-11-18, user request): a page at rest with one island (the editor alone, one tool
  window, one bottom window) draws it square and flush with the screen's sides; with no side bars upright, the gap
  and corners only waste room. The 6 dp gap and the rounded corners come back, animated, as soon as the pages move
  (a swipe passes the touch slop, or a bar button slides to another page) and while the page shows two islands
  (a split). When a swipe or slide settles on a page with one island, it goes flush again. The pages' position is
  kept in pages, not px, so the gap growing or shrinking doesn't move them (`SwipePager`, `LocalIslandRadius`).
  Wide and compact keep IntelliJ's gaps and corners. Checked on the emulator, 2026-11-18 ~00:51 IST: editor and
  Project flush; mid-swipe both islands rounded with the gap; released on the editor, flush again; Project over
  Structure rounded with gaps.
- One page is the screen width plus the island gap (0 when flush), so two islands keep their gap and rounded corners
  while sliding (before 2026-11-17, they touched mid-swipe).
- Opening a file from the Project tool window (double tap) slides back to the editor, closing the bottom windows if
  they were in its place.
- The swipe takes the touch events in Compose's `Initial` pass, before the editor sees them.
- **Dragging selects text only with a mouse.** On touch a drag scrolls. Before 2026-11-17 the editor also had a
  touch drag-to-select handler whose start moved the caret; it competed with scrolling for the same drag and,
  depending on timing, moved the caret to where a scroll began (user report "scroll sometimes registers as a
  click"). A tap that only stops a fling doesn't move the caret either. Checked: caret placed on line 6, seven
  vertical flings, caret still on line 6.
- The editor now moves the caret and takes focus on **tap**, not on press. On press, every swipe that started on
  the editor focused it and opened the keyboard. When a tool window covers the editor, focus is cleared, so the
  keyboard closes.

## Wide (desktop, tablet sideways)

- A bar on each side, as in IntelliJ's new UI: the left bar has left top and left bottom, with the bottom-left
  windows at its foot; the right bar has right top and right bottom, with the bottom-right windows at its foot.
- One side pane at a time, split when both of its halves are open. Opening a window on the other side closes this
  side's pane.
- The bottom area sits under the editor, as wide as the editor: bottom left and bottom right side by side. It is
  open or closed on its own, whatever the side panes do. Any mix works, from the editor alone to a split side pane
  plus both bottom halves.
- Project is open at start.

## Compact (phone sideways)

- One bar on the left with every group, separated by lines (scrolls up and down).
- The editor keeps its full height with at most one tool window beside it, never split: left windows on its left,
  right and bottom windows on its right (a bottom window under the editor would leave each about 180 dp of a 411 dp
  screen). Opening any window replaces the open one.

After a rotation the open windows are cut to what the new layout shows (`ToolWindowState.fit`): wide keeps the left
side over the right, compact keeps the first window by place.

## Checked

On the Mac's Android emulator (Pixel 3a, API 34) against the isolated Mac backend, 2026-11-18 ~00:30–00:40 IST, and
on the desktop:
- Upright: the bar's groups Project ⋯ | Structure | Version Control, Problems | Database. Project then Structure
  split the left page; › went back to the editor; a swipe right brought the split page back; closing Structure and
  then Project went back to the editor. Dragging the border between Project and Structure moved it and showed the
  pill; it went on release.
- Sideways (891 × 411): one left bar; Project left of the editor; Structure replaced it.
- `wm density 240` sideways (1480 × 720, wide): bars on both sides, Version Control and Problems at the left bar's
  foot, Database on the right; Project over Structure in the left pane.
- Desktop (1200 × 800, wide): the same bars and the left pane.
- With a throwaway build that let every window open: upright, Version Control took the editor's place with ‹ in the
  toolbar, and Back closed it; wide, Version Control under the editor, then Database closed the left pane and kept
  the bottom area.

Earlier layout:

On the Mac's Android emulator (Pixel 3a, API 34) against the isolated Mac backend, 2026-11-16 ~14:20 IST: landscape
with Project open; phone portrait editor, swipe right → Project, swipe left twice → editor → Structure; tablet
portrait (`adb shell wm density 240`) with Project and Structure side by side under the editor; Structure showed
`Main.java › Main › main(String[]): void`, a tap on `main` moved the caret to line 4, and typing `int count` added
`count: int`. Not yet on the user's phone.

## Testing notes

- The emulator rotates only when the app allows it. `adb shell wm user-rotation lock 0` holds portrait;
  `adb emu rotate` turns the emulator. While the activity pinned `sensorLandscape`, the window stayed landscape
  whatever the emulator's setting (`dumpsys window` showed `mCurrentAppOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`).
- `adb shell wm density 240` on the 1080-px-wide emulator gives a 720-dp-wide window; sideways it is 720 dp tall,
  enough for the wide layout. `adb shell wm density reset` undoes it.
- `adb shell settings put system user_rotation 1` (with `accelerometer_rotation` 0) turns the emulator sideways.
- `adb shell input motionevent DOWN|MOVE|UP x y` holds a drag open for a screenshot (the split's pill).
- `adb shell input swipe 200 1200 900 1200 200` is a 700-px swipe right in 200 ms.
