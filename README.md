# CrossPick

CrossPick is a PyCharm / IntelliJ Platform plugin for cherry-picking commits from a
**foreign repository ("git A")** into the **currently open project's repository
("git B")**, without ever leaving the IDE and without ever pushing anything on
your behalf.

It adds a **CrossPick** tool window (right-hand side, branch icon) where you can:

1. Point it at a source repo URL + branch + an "initial commit" SHA (the last
   commit you've *already* incorporated, exclusive).
2. **Scan** — it fetches the source branch into a dedicated local remote and lists
   every commit between the initial SHA and the tip of that branch that you have
   not yet transferred.
3. Click **Cherry-pick** on any row to `git cherry-pick -x` that commit into your
   currently checked-out branch in the open project.
4. If a cherry-pick conflicts, the IDE's merge dialog opens on the conflicted files.
   Resolve them and commit as usual; CrossPick notices the commit and marks the row
   transferred. See [Conflicts](#conflicts) below.

CrossPick **never runs `git push`**. Everything it does stays local until you
decide to push manually — the tool window says so directly under the commit table.

## Project layout

```
crosspick/
├── build.gradle.kts                 # IntelliJ Platform Gradle Plugin 2.1.0 build script
├── settings.gradle.kts              # rootProject.name = "crosspick"
├── gradle.properties
├── gradlew / gradlew.bat / gradle/  # Gradle 8.10 wrapper
└── src/main/
    ├── kotlin/com/alexkoleda/crosspick/
    │   ├── model/CommitRow.kt              # CommitRow data class + TransferStatus enum (incl. SKIPPED)
    │   ├── state/CrossPickState.kt         # Persistent project-level state (last inputs + transferred/skipped SHAs)
    │   ├── git/GitOps.kt                   # Thin git4idea GitLineHandler wrappers (remote, fetch, log, cherry-pick, ...)
    │   ├── service/CrossPickService.kt     # Project service orchestrating background scan/cherry-pick work
    │   └── ui/
    │       ├── CrossPickToolWindowFactory.kt
    │       ├── CrossPickPanel.kt           # Form (Kotlin UI DSL v2) + commit table + status line + right-click menu
    │       ├── CommitTableModel.kt         # ListTableModel<CommitRow> + strikethrough/dim-italic renderers
    │       └── ButtonColumn.kt             # Renders/edits the "Cherry-pick" action button column
    └── resources/META-INF/plugin.xml
```

## Building

### Requirements (Choose One)

**Option A: Docker (Recommended — No local Java required)**
- **Docker** — [Download Docker Desktop](https://www.docker.com/products/docker-desktop)
- No Java installation needed

**Option B: Local Build**
- **JDK 21** — The plugin requires Java 21
- **Gradle wrapper** — Included in the repository (no separate Gradle installation needed)

### Build Steps

#### Option A: Build with Docker (Recommended)

1. Navigate to the crosspick directory:
   ```bash
   cd crosspick
   ```

2. Run the build script:
   ```bash
   ./build.sh
   ```

   This will:
   - Build a Docker image with JDK 21
   - Compile the plugin inside the container
   - Extract the plugin archive to `build/distributions/crosspick-1.0.0.zip`

#### Option B: Local Build with Gradle

1. Navigate to the crosspick directory:
   ```bash
   cd crosspick
   ```

2. Build the plugin archive:
   ```bash
   ./gradlew buildPlugin --no-daemon
   ```

   The `--no-daemon` flag prevents Gradle daemon issues on macOS. You can omit it on Linux/Windows if you prefer.

3. The distributable plugin ZIP is written to:
   ```
   build/distributions/crosspick-1.0.0.zip
   ```

### Installing in PyCharm

1. Open **PyCharm** (any JetBrains IDE works; this extension is for PyCharm but compatible with other IDEs using the IntelliJ Platform)
2. Open **Preferences** (macOS: `Cmd+,`) or **Settings** (Windows/Linux: `Ctrl+Alt+S`)
3. Navigate to **Plugins**
4. Click the **⚙️ (gear)** icon in the Plugins panel
5. Select **Install Plugin from Disk…**
6. Browse to and select `build/distributions/crosspick-1.0.0.zip`
7. Click **OK** and restart PyCharm when prompted

The plugin is now installed and ready to use.

## Testing in a Sandbox IDE

To test the plugin without affecting your main PyCharm installation:

```bash
./gradlew runIde
```

This launches a sandboxed PyCharm Community 2024.3 instance with CrossPick
pre-installed. You can experiment against real repositories without touching your
main IDE installation. Sandbox data is isolated in a temporary directory.

## Usage

### Quick Start

1. **Open a project** — Launch PyCharm and open the repository you want commits cherry-picked *into* (target repository, "git B").

2. **Open the CrossPick tool window** — Look for the **CrossPick** icon (branch symbol) on the right tool window bar. Click it to open the tool window.

3. **Fill in the source repository details:**
   - **Source repo URL** — The repository URL to pull commits from (e.g., `https://github.com/org/repo.git`)
   - **Source branch** — The branch name to scan (e.g., `main`, `develop`)
   - **Initial commit SHA** — The last commit you've already incorporated; CrossPick will show commits *after* this one (use `git log` to find the SHA, e.g., `abc1234`)

4. **Click "Scan"** — CrossPick will:
   - Create a temporary remote (`crosspick-<hash>`) pointing to the source URL
   - Fetch the source branch
   - Verify the initial SHA exists and is an ancestor
   - List all non-merge commits from the initial SHA to the branch tip
   - Mark commits that were already transferred (based on `cherry picked from commit` trailers or prior transfers)

5. **Review the commit table** — Shows Short SHA, Date, Author, Subject, and a Cherry-pick button for each commit.

6. **Cherry-pick commits**:
   - Click **Cherry-pick** on any row to apply that commit to your current branch using `git cherry-pick -x`
   - Only one CrossPick operation runs at a time; clicks while one is running are ignored
   - Successfully transferred commits are marked **Transferred** and shown with strikethrough
   - Already-applied commits are marked **Already Applied** (including picks that turn out
     empty because the changes are already in your branch; CrossPick clears git's halted
     state for those itself)
   - If a pick fails for another reason (e.g. it would overwrite untracked files), the row
     stays pending and the status line shows git's error

7. **Handle conflicts** (if they occur):
   - The merge dialog opens automatically and the row shows **Resolving…**
   - Resolve the conflicts, then commit (the commit message is pre-filled by git)
   - The row switches to **Transferred** once the commit lands
   - Or click **×** to abort the cherry-pick; the row goes back to pending
   - See [Conflicts](#conflicts) for details

8. **Push manually** — Nothing is pushed automatically. Push your changes from PyCharm's Git menu or the terminal once you're satisfied.

### State Persistence

CrossPick remembers:
- Your last scan inputs (URL, branch, initial SHA)
- Which commits you've transferred and which you've skipped
- All state is stored per-project in `.idea/crosspick.xml`, keyed by the source URL the
  table was scanned from

Re-open the tool window later and the form is pre-filled with your last inputs, making repeated scans faster.

## Skipping commits

"Skipping a commit" can mean two things:

1. **Just don't click Cherry-pick.** In the normal case, skipping a commit is
   simply never pressing the button for that row. CrossPick doesn't require
   every row to be acted on.
2. **Mark as skipped, to record intent.** If you want CrossPick to remember
   that you deliberately decided *not* to transfer a commit (so it stays out of
   your way on future scans and is visually distinguished from a commit you
   just haven't gotten to yet), click the row's **×** button, or select one or
   more rows, right-click, and choose **Mark as skipped**. Skipped rows render
   dimmed and italicized (but *not* struck through — strikethrough is reserved
   for rows that were actually transferred) and their action button reads
   **Skipped** (disabled). The right-click menu supports multi-select
   (`Ctrl`/`Cmd`-click or shift-click rows first), so you can mark many rows at
   once. **Mark as transferred** and **Reset to pending** are also available
   from the same menu if you need to correct a row's state by hand, and
   **Copy full SHA** copies the selected rows' full commit SHAs to the clipboard.

### Conflicts

Each transfer is a single-commit `git cherry-pick -x <sha>`. When it stops on
conflicts:

- CrossPick refreshes the IDE's view of the repository and opens the standard
  merge dialog on the conflicted files (no need to run "Update Project").
- The row shows **Resolving…** and no other commit can be picked until this one
  is finished.
- Resolve the conflicts and commit through the IDE (or the terminal). The commit
  message is pre-filled by git, including the `(cherry picked from commit …)`
  line. As soon as git leaves the cherry-picking state with a new HEAD, the row
  is marked **Transferred**.
- To give up instead, click **×** on the row (runs `git cherry-pick --abort`)
  or abort from the IDE's Git menu; the row goes back to pending.
- If you rescan while a pick is halted, CrossPick recognises the commit behind
  `CHERRY_PICK_HEAD` and shows that row as **Resolving…** again.

## Known limitations / v1.1 ideas

- **No multi-select batch pick** — commits must still be cherry-picked one at a
  time; a v1.1 could add a single "Cherry-pick selected" action that walks the
  list sequentially and stops on the first conflict. Note that multi-row
  selection itself now exists (`MULTIPLE_INTERVAL_SELECTION` on the table) and
  is wired up for the right-click **Mark as skipped / Mark as transferred /
  Reset to pending / Copy full SHA** status-marking actions — batch *picking*
  specifically is still not implemented.
- **No diff preview** — there's no way to preview a commit's diff before picking
  it; adding a preview pane (reusing `Show Diff` from git4idea) would help users
  decide what to pick.
- **No filter/search box** — long commit lists aren't searchable/filterable by
  author, date range, or subject text yet.
- **No remote cleanup action** — the `crosspick-*` remotes added during scans are
  never removed; a "Remove CrossPick remotes" button would keep `git remote -v`
  tidy.
- **No credentials UI** — authentication for private source repos relies entirely
  on whatever credential helpers/SSH agent are already configured on the host;
  there's no in-plugin credential prompt or storage.

## Design notes / API choices

CrossPick deliberately avoids internal/unstable git4idea APIs
(`GitCherryPicker`, `GitLogUtil`, `GitConflictResolver`) in favor of the stable,
long-lived `GitLineHandler` + `Git.getInstance().runCommand(handler)` pattern with
raw `git` command parsing. This trades a small amount of convenience for much
better compatibility across IntelliJ Platform versions.
