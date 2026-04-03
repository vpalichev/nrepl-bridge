# Injecting nrepl-bridge into an existing project

Use this when you have a **brownfield** Clojure project (existing deps.edn,
src/, application code) and want to add the nrepl-bridge eval workflow.

For creating a new project from scratch, see TEMPLATE.md instead.

## Prerequisites

- [Babashka](https://github.com/babashka/babashka) (`bb`) on PATH
- A running nREPL server (via `clj -M:nrepl`, `npx shadow-cljs watch`, or similar)
- Claude Code with MCP support

## Step 1: Copy the bridge directory

From the template repo, copy the entire `.nrepl-bridge/` directory into your
project root:

```bash
cp -r /path/to/nrepl-bridge/.nrepl-bridge /path/to/your-project/.nrepl-bridge
```

This is the only directory you need. Do not edit files inside it.

## Step 2: Create .mcp.json

In your project root, create `.mcp.json`:

```json
{
  "mcpServers": {
    "nrepl-bridge": {
      "type": "stdio",
      "command": "bb",
      "args": [".nrepl-bridge/server.bb"]
    }
  }
}
```

**If your nREPL runs on a fixed port** (not auto-discovered from port files):
```json
"args": [".nrepl-bridge/server.bb", "--backend-port", "7888"]
```

**If another project already uses dashboard port 9500:**
```json
"args": [".nrepl-bridge/server.bb", "--dashboard-port", "9501"]
```

## Step 3: Update .gitignore

Append to your `.gitignore`:

```
.workbench/
```

The `.nrepl-bridge/` directory should be tracked (committed). The `.workbench/`
directory is runtime data (SQLite DB, logs, dumps) and must not be committed.

## Step 4: Add bridge instructions to CLAUDE.md

Copy the bridge section from the template's `CLAUDE.md` — everything from
`# nREPL Bridge (do not remove)` to the end of file — and append it to your
project's `CLAUDE.md`.

If your project doesn't have a `CLAUDE.md`, create one with just the bridge
section. Add any project-specific instructions above it.

Do not remove the `(do not remove)` marker — it helps identify the section
for future template updates.

## Step 5: Optional dependencies

The CLAUDE.md workflow references these libraries. Add to `deps.edn` if you
want the full post-edit verification workflow:

```clojure
;; In :deps or an alias
clj-reload/clj-reload {:mvn/version "0.7.1"}    ;; namespace reloading in dependency order
clj-kondo/clj-kondo   {:mvn/version "2024.11.14"} ;; lint as library via nrepl_send
```

These are optional. The bridge itself works without them.

## Step 6: Verify

1. Start your nREPL server
2. Open the project in Claude Code — the bridge starts automatically via `.mcp.json`
3. Run: `nrepl_send {form: "(+ 1 1)"}`
4. Check the dashboard: `http://localhost:9500` (or see `.workbench/dashboard.url`)
5. Run: `bridge_control {action: "status"}` — should show build version and 0 missed writes

If step 3 fails, run the diagnostics tool:
```bash
bb .nrepl-bridge/diagnose.bb
```

## What NOT to do

- Do not edit files in `.nrepl-bridge/` — they are updated via template diffs
- Do not add `.nrepl-bridge/` to `.gitignore` — it should be committed
- Do not duplicate dependencies from `deps.edn` into `shadow-cljs.edn` if
  shadow uses `:deps true`
- Do not hardcode ports unless you have a specific reason

## Updating the bridge later

See TEMPLATE.md "Propagating template updates to existing projects" for how
to pull fixes and features from newer template versions via `git diff` patches.

## Port discovery order

The bridge checks these locations for an nREPL port (first match wins):

1. `--backend-port` CLI arg in `.mcp.json`
2. `.nrepl-port` in project root
3. `.shadow-cljs/nrepl.port`
4. `node_modules/shadow-cljs-jar/.nrepl-port`

If your project writes the port file somewhere else, use `--backend-port` to
override.

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `nrepl_send` returns connection error | nREPL not running | Start nREPL, then `/mcp` to restart bridge |
| Dashboard shows stale data | Old bridge process | `/mcp` restart (auto-kills zombies) |
| `bb` not found | Babashka not on PATH | Install bb, restart terminal |
| Port collision on 9500 | Another project's bridge | Add `--dashboard-port` to `.mcp.json` |
| Eval works but no DB entry | First run, migration needed | Restart bridge — migration runs on init |
