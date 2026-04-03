# nrepl-bridge Template

## What this is

A **vendored toolchain**: infrastructure committed into the project rather than
installed as an external dependency. The `.nrepl-bridge/` directory is the
toolchain. Everything else (`src/`, `deps.edn`, `shadow-cljs.edn`, etc.) is
your application code.

The split is: **scaffold** (the full template) becomes **vendored toolchain**
(`.nrepl-bridge/`) + **application code** once the project starts evolving.
The toolchain is versioned independently from your app via git tags
(`template/v1`, `template/v2`, `template/v3`, ...).

## Starting a new project from the golden image

```bash
# 1. Clone the template into your new project directory
git clone D:\projects\nrepl-bridge D:\projects\my-new-app
cd D:\projects\my-new-app

# 2. Check out the golden image tag
git checkout template/v4

# 3. Start a fresh history (detach from template commits)
git checkout --orphan main
git commit -m "init: from nrepl-bridge template/v4"

# 4. Convert CLAUDE.md for downstream use and remove template-only files
sed -e '/<!-- TEMPLATE-ONLY -->/,/<!-- \/TEMPLATE-ONLY -->/d' \
    -e '/<!-- DOWNSTREAM-ONLY/d' \
    -e '/DOWNSTREAM-ONLY -->/d' \
    CLAUDE.md > CLAUDE.md.new && mv CLAUDE.md.new CLAUDE.md
rm GIT-WORKFLOW.md
git add -A
git commit -m "chore: strip template-only content"

# 5. Delete template tags (clean break from template history)
git tag -l 'template/*' | xargs git tag -d
git reflog expire --expire=now --all && git gc --prune=now

# 6. Drop the template remote
git remote remove origin

# 7. (Optional) Point at your own remote
git remote add origin https://github.com/you/my-new-app.git
```

After that:

1. `npm install`
2. `npx shadow-cljs watch app`
3. Open `http://localhost:8280` in a browser
4. Start a Claude Code session -- ACTIVATION.md runs preflight checks automatically
5. Write your app in `src/clj/`, `src/cljs/`, `src/cljc/`, add dependencies to `deps.edn`
6. Copy tests from `.nrepl-bridge/test/examples/` as a starting point for app-level tests

## Propagating template updates to existing projects

When the template evolves (bug fixes, new features, test improvements), you
want to pull those changes into projects that were created from an older tag.

### Add the template as a remote (one-time setup)

In your project:

```bash
git remote add nrepl-template D:\projects\nrepl-bridge
git fetch nrepl-template --tags
```

### See what changed between versions

```bash
# Full diff between two tags
git diff template/v2..template/v3 -- .nrepl-bridge/ CLAUDE.md .mcp.json

# Just file names
git diff --name-only template/v2..template/v3 -- .nrepl-bridge/ CLAUDE.md .mcp.json
```

### Apply the update

```bash
# Patch method: generate and apply a diff
git diff template/v2..template/v3 -- .nrepl-bridge/ CLAUDE.md .mcp.json | git apply

# Review, then commit
git add .nrepl-bridge/ CLAUDE.md .mcp.json
git commit -m "chore: update nrepl-bridge template/v2 -> template/v3"
```

If the patch fails, use `git apply --3way` to get merge conflict markers that
you can resolve manually. CLAUDE.md patches may fail near `<!-- TEMPLATE-ONLY -->`
marker boundaries -- this is expected; those sections don't exist in downstream.
Resolve by keeping your version of any conflicting lines near markers.

## Reporting a bug back to the template

If you find a bug in `.nrepl-bridge/` while working in a downstream project,
the fix must end up in the template repo. Two approaches:

### Option A: Fix in the template first (preferred)

1. Open `D:\projects\nrepl-bridge`
2. Make the fix in `.nrepl-bridge/`
3. Run the bridge test suite (see "Test suite" below)
4. Commit
5. Pull the fix into your project using the "Apply the update" steps above

The fix flows one direction: template -> projects. Clean and traceable.

### Option B: Fix in your project, then backport

Sometimes you find the bug mid-work and fix it on the spot. That's fine:

1. Fix it in your project's `.nrepl-bridge/`
2. Open `D:\projects\nrepl-bridge` and make the same fix manually
3. Run the bridge test suite there
4. Commit

Don't use `git cherry-pick` across repos -- the histories are unrelated
(the project was created with `--orphan`), so it won't apply cleanly.

**The rule:** the template is the source of truth. Whichever option you use,
the fix must always end up committed in `D:\projects\nrepl-bridge`.

### What gets updated

| Path              | Updated by template? | Notes                                    |
|-------------------|----------------------|------------------------------------------|
| `.nrepl-bridge/`  | Yes                  | Bridge infrastructure -- do not edit      |
| `CLAUDE.md`       | Partially            | Bridge section updates; your project section preserved |
| `.mcp.json`       | Rarely               | Only if MCP registration format changes   |
| `src/clj,cljs,cljc/` | Never            | Your application code                     |
| `deps.edn`        | Never                | Your dependencies                         |
| `shadow-cljs.edn` | Never                | Your build config                         |

### Version check

In the template repo, the version is recorded in two places:

- `CLAUDE.md` (inside `<!-- TEMPLATE-ONLY -->` block) -- "Current template version: **template/vN**"
- `.nrepl-bridge/ACTIVATION.md` -- "Template version: **template/vN**"

Both must agree. In downstream projects, only ACTIVATION.md carries the version
(the CLAUDE.md version block is stripped during creation).

## Test suite

### Bridge tests (validate the toolchain itself)

Run these after any template update or to verify a fresh setup:

```
bb .nrepl-bridge/test/test-phase0.bb      # Core pipeline (17 tests)
bb .nrepl-bridge/test/test-phase1.bb      # Edge cases + adversarial (18 tests)
bb .nrepl-bridge/test/test-sqlite.bb      # SQLite audit layer (30 tests)
bb .nrepl-bridge/test/test-dashboard.bb   # Dashboard HTML + API (12 tests)
bb .nrepl-bridge/test/test-gate.bb        # Approval gate (7 tests)
```

Prerequisites: `npx shadow-cljs watch app` running. A browser on `localhost:8280`
is optional -- test-phase1 T1.16 skips gracefully without it.

All 84 tests should pass (or T1.16 SKIP) on a bare template with no app code.

### Example app tests (reference for your own tests)

Located in `.nrepl-bridge/test/examples/`:

- `example-app-backend.bb` -- Ring/Reitit HTTP route testing
- `example-app-frontend.bb` -- ClojureScript UI testing via shadow-cljs
- `example-app-etaoin.bb` -- Browser automation with etaoin + ChromeDriver

These test application code (`proof.*` namespaces) that does not exist in the
bare template. Copy and adapt them when building your app.

## Injecting into an existing project

See **INJECTION.md** for step-by-step instructions on adding nrepl-bridge
to a brownfield Clojure project that already has its own deps.edn, src/, etc.

## Tag history

| Tag           | Description                                        |
|---------------|----------------------------------------------------|
| `template/v1` | Backend-only (clj -M:nrepl, no shadow-cljs)       |
| `template/v2` | Full-stack: shadow-cljs as default REPL            |
| `template/v3` | Test suite reorganized for bare template use       |
| `template/v4` | Split src/ into src/clj, src/cljs, src/cljc        |

### Changes on master since template/v4

- Async DB writes via Clojure agent (MCP response never blocked)
- Dashboard: live WebSocket updates with inline DOM manipulation
- Dashboard: DB write health bar (50-block indicator)
- Dashboard: stats-only API refresh instead of full page reload
- SQLite CHECK constraint widened (exception, class-not-found, db-timeout)
- Migration to widen constraint on existing DBs automatically
- Write audit log at `.workbench/db/write-audit.edn`
- Auto-kill zombie bridge processes on startup (project-scoped)
- `bridge_control status` reports full write health details
- `shutdown-bridge.sh` for manual process cleanup
