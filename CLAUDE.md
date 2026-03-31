# Project Instructions

This is the **canonical source repository** for nrepl-bridge. It is not a
project built on the template — it IS the template. Golden image tags
(`template/v1`, `template/v2`, `template/v3`, ...) are cut from this repo
and consumed by downstream projects via the workflow in TEMPLATE.md.

## What that means for you (Claude)

- You MAY edit files inside `.nrepl-bridge/` — that is the whole point of
  this repo. In downstream projects, `.nrepl-bridge/` is read-only
  infrastructure; here it is the primary deliverable.
- Changes to bridge code, tests, docs, and schema happen here first.
  Downstream projects pull updates via `git diff` between tags.
- The app stub (`src/cljs/app/core.cljs`, `resources/public/index.html`) exists
  only so `npx shadow-cljs watch app` compiles. It is not a real app.
  Do not add application logic to it.
- After any change to `.nrepl-bridge/`, run the bridge test suite to confirm
  nothing broke (see TEMPLATE.md "Test suite" section).
- Before tagging a new golden image, all 5 test scripts must pass (or SKIP
  where documented). No exceptions.

---

# nREPL Bridge (do not remove)

## What This Is

A Babashka MCP server that gives you a shell-free path to evaluate Clojure and ClojureScript against this project's running nREPL servers. Every eval is recorded in SQLite under `.workbench/db/toolchain.db`. The server is registered in `.mcp.json` and launches automatically with your session.

## Port Discovery

The bridge finds the nREPL port automatically, checking in order:

1. `--backend-port` CLI arg in `.mcp.json` (explicit override)
2. `.nrepl-port` in project root (written by `clj -M:nrepl`)
3. `.shadow-cljs/nrepl.port` (written by `npx shadow-cljs watch`)
4. `node_modules/shadow-cljs-jar/.nrepl-port` (fallback)

No hardcoded ports. Start nREPL, the bridge finds it.

To override (e.g., remote nREPL), add `"--backend-port", "7888"` to the args in `.mcp.json`.

## How to Evaluate Clojure

Use the `nrepl_send` tool. It is the only correct way to send Clojure forms to the REPL in this project.

`nrepl_send {form: "(+ 1 2 3)", target: "backend"}` -- JVM Clojure (default)
`nrepl_send {form: "...", target: "frontend"}` -- ClojureScript in the browser

For frontend evaluation (ClojureScript projects with shadow-cljs), prefer `cljs-eval` via the backend nREPL rather than `target: "frontend"` directly:

```clojure
(shadow.cljs.devtools.api/cljs-eval :app "(.-title js/document)" {})
```

For known long-running operations, set `timeout_ms` explicitly rather than relying on the 30-second default.

## Forbidden Patterns

Do NOT use the Bash tool for Clojure evaluation. Shell encoding on Windows corrupts paths, Unicode, and special characters. This is the entire reason nrepl-bridge exists.

Do NOT use curl, wget, Python requests, PowerShell `Invoke-WebRequest`, or any shell-based HTTP tool. Use `clj-http`, `ring.mock.request`, or direct Ring handler calls through `nrepl_send`.

Do NOT use Playwright, Puppeteer, or Selenium via Python. Use etaoin through `nrepl_send` with `target: "backend"`.

If a library is needed but not in deps.edn, add it dynamically through `nrepl_send` via `clojure.repl.deps/add-lib` rather than editing deps.edn and restarting the REPL. Persist to deps.edn only after confirming it works.

## After Every Source File Edit

Run this verification sequence. Do not skip steps.

1. Confirm the paren repair hook fired (check tool output for "hook succeeded")
2. Run clj-kondo as a library: `(clj-kondo.core/run! {:lint ["path/to/file.clj"]})`
3. Require the namespace: `(require '[the.namespace] :reload)`
4. Test pure functions adversarially with edge cases through `nrepl_send`

A clean paren repair does not mean the code is correct. Structural validity and semantic validity are independent.

## Etaoin Browser Testing

Etaoin executes WebDriver commands faster than the browser can render or complete network requests. Add short pauses between actions that trigger DOM updates, re-frame dispatches, or backend calls:

```clojure
(e/click driver {:id "submit-btn"})
(e/wait driver 0.5)                    ;; let re-frame + React re-render
(e/get-element-text driver {:id "result"})
```

Without pauses, you will read stale DOM state and tests will appear to fail.

## Before Long-Running Operations

Confirm with the user before any `nrepl_send` expected to take more than a few seconds. This includes: starting servers, running test suites, etaoin browser scenarios, large compilation cycles, dataset processing, blocking I/O against external services.

## Before Any Frontend Work

Only relevant for ClojureScript projects with shadow-cljs. Check exactly one browser instance is connected:

```clojure
(count (shadow.cljs.devtools.api/repl-runtimes :app))
```

Must return `1`. If `0`, ask user to open the app in a browser. If more than `1`, ask user to close extras.

## Directory Layout

```
your-project/
  .mcp.json                  -- MCP server registration (auto-discovery, no port needed)
  .gitignore                 -- excludes .workbench/, .nrepl-bridge is tracked
  CLAUDE.md                  -- this file
  deps.edn                   -- all Clojure/Script dependencies
  shadow-cljs.edn            -- CLJS build config (:deps true reads deps.edn)
  package.json               -- shadow-cljs + react
  src/clj/                   -- JVM Clojure source (.clj)
  src/cljs/                  -- ClojureScript source (.cljs)
  src/cljc/                  -- shared source (.cljc)
  resources/public/index.html -- frontend entry point
  .nrepl-bridge/             -- bridge infrastructure (do not edit)
    server.bb                -- MCP server entrypoint
    schema.sql               -- SQLite schema
    diagnose.bb              -- troubleshooting tool
    src/nrepl_bridge/        -- server source
    test/                    -- bridge acceptance tests
    test/examples/           -- example app-level tests (copy and adapt)
    ACTIVATION.md            -- session preflight checks
  .workbench/                -- runtime data (gitignored)
    db/toolchain.db          -- SQLite eval history
    logs/nrepl-bridge.log    -- MCP server diagnostics
    dumps/eval-NNN.edn       -- full results for large evals
```

## Starting the REPL

The default setup uses shadow-cljs, which provides a single process for both JVM Clojure and ClojureScript:

```
npm install          -- first time only
npx shadow-cljs watch app
```

This starts nREPL (port auto-discovered by bridge), CLJS compilation, hot-reload, and a dev HTTP server.

### Backend-only alternative
For pure JVM Clojure without frontend:
```
clj -M:nrepl
```

### Adding Dependencies
Edit `deps.edn`. Shadow-cljs reads it via `:deps true`. No duplication needed.

## Template Versioning

Current template version: **template/v3**

Golden image snapshots are stored as git tags (`template/v1`, `template/v2`, etc.). Tags are immutable — they cannot be accidentally overwritten. The `master` branch is the working copy where the template evolves.

To see what changed since the golden image: `git diff template/v3..master`

When creating a new template version:
1. Tag: `git tag template/vN`
2. Update the version string in this section to match
3. Update ACTIVATION.md if preflight checks changed

Do NOT skip step 2. If the tag and this document disagree, the documentation is wrong.

## Console Cyrillic

Console may show `???` for Cyrillic. Data in SQLite, nREPL, and the MCP pipeline is always clean UTF-8. Not a bug.
