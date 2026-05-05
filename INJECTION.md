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

## Step 5: Required dependencies

The CLAUDE.md workflow the bridge installs in your project actively instructs
the LLM to use `clojure.repl.deps/add-lib`, `clj-reload`, and `clj-kondo`. If
they are missing, the LLM follows the documented workflow and crashes on the
first attempt (e.g. `add-lib` throws `ClassNotFoundException` when
`org.clojure/tools.deps` is absent from the runtime classpath). Therefore
these are required, not optional.

Use a dedicated `:nrepl` alias rather than putting them in `:deps` -- they
are dev-tooling, they should not be in the production classpath. Paste this
into your `deps.edn`:

```clojure
:nrepl {:extra-deps {nrepl/nrepl                 {:mvn/version "1.7.0"}
                     cider/cider-nrepl           {:mvn/version "0.59.0"}
                     org.clojure/tools.deps      {:mvn/version "0.29.1598"}
                     io.github.tonsky/clj-reload {:mvn/version "1.0.0"}
                     clj-kondo/clj-kondo         {:mvn/version "2026.04.15"}}
        :main-opts  ["-m" "nrepl.cmdline"
                     "--port" "<your-port>"
                     "--middleware" "[cider.nrepl/cider-middleware]"]}
```

Then start nREPL with `clj -M:nrepl`.

**The version numbers above are reference points, not pinned ceilings.**
These are dev tooling -- it is normally safe (and preferred) to use the
latest stable release of each. If a newer version exists on Clojars (or
Maven Central, for `org.clojure/*` artifacts which are not on Clojars),
prefer it. Bump on injection, bump again whenever convenient. The exact
version listed here will go stale; the latest stable will not.

What each one is for:

- `nrepl/nrepl` + `cider/cider-nrepl` -- the nREPL server and middleware that
  power `nrepl_send`. Without these, the bridge has nothing to talk to.
- `org.clojure/tools.deps` -- required at runtime for
  `clojure.repl.deps/add-lib` to work. The `clj` launcher uses tools.deps
  to compute your classpath, but does NOT add it to your runtime classpath;
  you have to add it explicitly.
- `io.github.tonsky/clj-reload` -- the bridge's CLAUDE.md tells the LLM to
  use `(reload/reload)` rather than `(require ... :reload)` to avoid
  load-order errors. Without clj-reload on the classpath, that instruction
  fails.
- `clj-kondo/clj-kondo` -- the bridge's "After Every Source File Edit"
  step calls clj-kondo as a library through `nrepl_send`. Without it on
  the classpath, the lint step fails.

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
