# nrepl-bridge Code Reference

Auto-generated catalog of every `def` / `defn` in the bridge source.
Build: 2026-04-03a

---

## nrepl-bridge.db (`src/nrepl_bridge/db.clj`)

SQLite operations via pod-babashka-go-sqlite3. All pod calls serialized through a single lock.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `db-dir` | priv | 8 | — | Directory path `.workbench/db` |
| `db-path` | priv | 9 | — | Full path to `toolchain.db` |
| `pod-lock` | priv | 13 | — | Lock object serializing all pod stdin/stdout calls |
| `sq-query` | priv | 15 | `[& args]` | `sqlite/query` under pod-lock |
| `sq-execute!` | priv | 18 | `[& args]` | `sqlite/execute!` under pod-lock |
| `schema` | priv | 21 | — | DDL: evals table with constraints |
| `now-utc` | priv | 48 | `[]` | Current time as ISO-8601 string |
| `migrations` | priv | 51 | — | ALTER TABLE statements (decision, feedback, intent columns) |
| `apply-migrations!` | priv | 56 | `[]` | Run migrations, skip already-applied |
| `init-db!` | **pub** | 67 | `[]` | Create dir, apply schema + migrations, set WAL + busy_timeout |
| `resolve-orphaned-evals!` | **pub** | 82 | `[]` | Mark "evaluating" rows as error on startup |
| `db-write-timeout-ms` | priv | 95 | — | 3000 ms hard deadline for writes |
| `fallback-path` | priv | 96 | — | `.workbench/db/missed-writes.edn` |
| `missed-write-count` | **pub** | 97 | — | Atom: count of timed-out writes |
| `dump-to-fallback!` | priv | 99 | `[label data]` | Append missed write to fallback EDN |
| `with-db-timeout` | priv | 112 | `[label f fallback-data]` | Run f in future with deadline; fallback on timeout |
| `insert-eval!` | **pub** | 132 | `[{:keys [target port ns form ...]}]` | INSERT with status "evaluating", return id (negative on timeout) |
| `update-eval!` | **pub** | 149 | `[{:keys [id status value ...]}]` | UPDATE with results; no-op for negative temp IDs |
| `recent-evals` | **pub** | 162 | `([] [n])` | Last n evals, newest first (default 20) |
| `eval-by-id` | **pub** | 169 | `[id]` | Single eval by id |
| `error-evals` | **pub** | 175 | `([] [n])` | Recent non-ok evals |
| `eval-stats` | **pub** | 183 | `[]` | Aggregate counts by status + avg eval time |
| `filtered-evals` | **pub** | 199 | `[{:keys [status limit offset]}]` | Paginated evals with optional status filter |
| `pending-approvals` | **pub** | 210 | `[]` | Evals with decision='pending' |
| `update-decision!` | **pub** | 216 | `[id decision feedback]` | Set decision + feedback |
| `insert-gated-eval!` | **pub** | 223 | `[{:keys [target port ns form ...]}]` | INSERT with decision='pending' |
| `eval-decision` | **pub** | 233 | `[id]` | Read current decision for an eval |
| `duration-stats` | **pub** | 239 | `[]` | p50/p95/avg/min/max for successful evals |

---

## nrepl-bridge.web (`src/nrepl_bridge/web.clj`)

Dashboard HTTP server + WebSocket broadcasting via http-kit.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `!build` | **pub** | 15 | — | Atom: build version string |
| `!ws-clients` | **pub** | 19 | — | Atom: set of connected WS channels |
| `broadcast!` | **pub** | 21 | `[msg]` | JSON-send to all WS clients |
| `truncate` | priv | 33 | `[s n]` | Truncate string, append "..." |
| `status-badge` | priv | 38 | `[status]` | Colored HTML span for status |
| `decision-badge` | priv | 54 | `[decision]` | Colored HTML span for approval decision |
| `escape-attr` | priv | 65 | `[s]` | HTML-escape for data attributes |
| `parse-ts` | priv | 75 | `[ts]` | ISO-8601 → epoch millis |
| `format-gap` | priv | 81 | `[gap-sec]` | Seconds → "5m30s" display |
| `eval-row-html` | priv | 90 | `[row gap-sec]` | Render one `<tr>` for the eval table |
| `pending-section` | priv | 130 | `[pending-rows]` | Approval UI with approve/reject buttons |
| `page-html` | priv | 152 | `[{:keys [evals stats ...]}]` | Full dashboard HTML page |
| `parse-query-params` | priv | 407 | `[query-string]` | URL query string → keyword map |
| `json-response` | priv | 416 | `[body]` | 200 JSON response with CORS |
| `html-response` | priv | 422 | `[body]` | 200 HTML response |
| `read-log-tail` | priv | 427 | `[n]` | Last n lines of log file |
| `handle-ws` | priv | 436 | `[req]` | Register/deregister WS client |
| `route-request` | priv | 445 | `[req]` | HTTP router (dashboard, API, WS) |
| `!server` | **pub** | 527 | — | Atom: server instance (defonce) |
| `!actual-port` | **pub** | 528 | — | Atom: bound port |
| `port-available?` | priv | 530 | `[port]` | Test port bindability |
| `start!` | **pub** | 537 | `[preferred-port]` | Start http-kit, scan ports if taken |
| `stop!` | **pub** | 566 | `[]` | Stop server, clear state |

---

## nrepl-bridge.nrepl-client (`src/nrepl_bridge/nrepl_client.clj`)

Low-level nREPL TCP/bencode communication.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `bytes->str` | priv | 10 | `[v]` | Bencode value → UTF-8 string |
| `status-done?` | priv | 18 | `[status]` | Check if status contains "done" |
| `escape-non-ascii` | **pub** | 24 | `[s]` | Replace non-ASCII with `\uXXXX` |
| `strip-markdown-fences` | **pub** | 36 | `[code]` | Remove `` ```lang ``` `` wrappers |
| `nrepl-eval` | **pub** | 44 | `[{:keys [port code ns ...]}]` | TCP eval with timeout + result aggregation |
| `wrap-frontend-form` | **pub** | 109 | `[form shadow-build]` | Wrap form in `shadow.cljs.devtools.api/cljs-eval` |
| `test-connection` | **pub** | 116 | `[port timeout-ms]` | TCP connect test → boolean |
| `clone-session` | **pub** | 128 | `[port timeout-ms]` | Clone new nREPL session → session-id |
| `interrupt-session!` | **pub** | 159 | `[port session timeout-ms]` | Send interrupt op to cancel eval |
| `session-alive?` | **pub** | 189 | `[port session timeout-ms]` | Eval `(+ 41 1)`, expect "42" |

---

## nrepl-bridge.paren-repair (`src/nrepl_bridge/paren_repair.clj`)

Syntax validation and automatic bracket repair.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `delimiter-pairs` | priv | 10 | — | `{( ) [ ] { }}` |
| `closing-delimiters` | priv | 13 | — | Set: `) ] }` |
| `check-syntax` | **pub** | 16 | `[form-str]` | Parse with edamame → `{:ok? true}` or error |
| `infer-missing-closers` | priv | 40 | `[form-str]` | Walk form tracking unclosed delimiters → closing string |
| `attempt-repair` | **pub** | 70 | `[form-str error-type]` | Append inferred closers, re-validate |
| `process-form` | **pub** | 88 | `[raw-form]` | Full pipeline: strip fences → check → repair if needed |

---

## nrepl-bridge.gate (`src/nrepl_bridge/gate.clj`)

Human-in-the-loop approval gate for side-effecting forms.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `unsafe-patterns` | priv | 13 | — | Regexes: `!`-ending fns, spit, System/exit, alter-var-root, etc. |
| `safe-allowlist` | priv | 32 | — | Regexes: require, println, re-frame dispatch |
| `classify-form` | **pub** | 43 | `[form]` | → `:auto` or `:gated` |
| `wait-for-decision!` | **pub** | 62 | `[eval-id timeout-ms]` | Poll DB every 500ms → `:approved`/`:rejected`/`:timeout` |

---

## nrepl-bridge.logging (`src/nrepl_bridge/logging.clj`)

File-based logging to `.workbench/logs/nrepl-bridge.log`.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `log-dir` | priv | 7 | — | `.workbench/logs` |
| `log-file` | priv | 8 | — | Full log file path |
| `dir-ensured?` | priv | 9 | — | Atom: directory created flag |
| `init!` | **pub** | 11 | `[]` | Create log directory |
| `ensure-dir!` | priv | 17 | `[]` | Lazy init |
| `now-utc` | priv | 21 | `[]` | ISO-8601 timestamp |
| `log!` | **pub** | 24 | `[level msg]` | Append `[ts] [LEVEL] msg` to log |
| `log-startup!` | **pub** | 31 | `[check-name passed? detail]` | Log startup check PASS/FAIL |
| `log-eval!` | **pub** | 38 | `[{:keys [id target ...]}]` | Structured eval event log line |

---

## nrepl-bridge.dashboard (`src/nrepl_bridge/dashboard.clj`)

CLI dashboard commands (not the web dashboard).

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `truncate` | priv | 6 | `[s n]` | Truncate string |
| `format-row` | priv | 11 | `[row]` | Format eval as multi-line text |
| `cmd-recent` | **pub** | 27 | `[]` | Show last 20 evals |
| `cmd-errors` | **pub** | 37 | `[]` | Show recent failures |
| `cmd-stats` | **pub** | 47 | `[]` | Print aggregate stats |
| `cmd-detail` | **pub** | 59 | `[id]` | Full detail for one eval |
| `cmd-tail` | **pub** | 86 | `[]` | Live-poll new evals (Ctrl+C to stop) |
| `-main` | **pub** | 102 | `[& args]` | CLI dispatcher |

---

## etaoin-extras (`src/nrepl_bridge/etaoin_extras.clj`)

Safe wrappers around etaoin that return nil instead of throwing.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `q1` | **pub** | 14 | `[driver selector]` | query → first match or nil |
| `exists?` | **pub** | 21 | `[driver selector]` | → boolean |
| `absent?` | **pub** | 26 | `[driver selector]` | → boolean |
| `text` | **pub** | 33 | `[driver selector]` | Element text or nil |
| `texts` | **pub** | 39 | `[driver selector]` | All matching texts as vec |
| `next-sibling` | **pub** | 46 | `[driver selector]` | Next DOM sibling or nil |
| `prev-sibling` | **pub** | 52 | `[driver selector]` | Previous DOM sibling or nil |
| `parent` | **pub** | 58 | `[driver selector]` | Parent element or nil |
| `computed-style` | **pub** | 66 | `[driver selector prop]` | CSS computed property via JS |
| `visible?` | **pub** | 74 | `[driver selector]` | display≠none && visibility≠hidden |
| `inspect` | **pub** | 84 | `[driver selector props]` | Batch JS property/attribute extraction |
| `inner-html` | **pub** | 104 | `[driver selector]` | innerHTML string via JS |

---

## server.bb (main script)

MCP server entry point — JSON-RPC over stdio, tool dispatch, session management.

| Name | Vis | Line | Params | Summary |
|------|-----|------|--------|---------|
| `parse-args` | pub | 44 | `[args]` | CLI flags → options map |
| `discover-nrepl-port` | priv | 62 | `[]` | Auto-find live nREPL port from port files |
| `config` | pub | 86 | — | Atom: runtime config |
| `cli-backend-port` | priv | 90 | — | Original `--backend-port` CLI value |
| `get-backend-port` | priv | 92 | `[]` | Return configured or discovered port |
| `invalidate-backend-port!` | priv | 101 | `[]` | Force re-discovery on next call |
| `startup-checks` | pub | 111 | — | Atom: startup check results |
| `!sessions` | pub | 115 | — | Atom: `{:backend sid :frontend sid}` |
| `!msg-counter` | pub | 116 | — | Atom: incrementing message ID |
| `next-msg-id` | priv | 118 | `[]` | Next unique msg-id string |
| `etaoin-extras-code` | priv | 121 | — | Loaded etaoin_extras.clj content |
| `inject-etaoin-extras!` | priv | 128 | `[port session-id]` | Load etaoin helpers into JVM session |
| `clone-target-session!` | priv | 140 | `[target]` | Clone session, store, optionally inject etaoin |
| `ensure-session!` | priv | 151 | `[target]` | Get valid session; re-clone if dead |
| `check!` | pub | 173 | `[name test-fn]` | Run + log startup check |
| `bridge-build` | pub | 182 | — | Build version string |
| `run-startup-checks!` | pub | 184 | `[]` | Init logging, run all checks |
| `read-line-bytes` | priv | 259 | `[in]` | Read line from InputStream |
| `stdin-stream` | priv | 283 | — | Buffered stdin |
| `read-message` | pub | 285 | `[]` | Read JSON-RPC via Content-Length framing |
| `write-message` | pub | 302 | `[msg]` | Write JSON-RPC to stdout |
| `tool-definition` | pub | 312 | — | MCP schema for `nrepl_send` |
| `startup-report` | pub | 334 | `[]` | Startup summary for MCP responses |
| `err-limit` | priv | 345 | — | Max error chars in response |
| `summarize-err` | priv | 347 | `[err eval-id]` | Trim verbose error output |
| `ws-truncate` | priv | 363 | `[s n]` | Truncate for WS broadcast payloads |
| `dump-threshold` | priv | 371 | — | 32768 bytes |
| `dump-large-result!` | priv | 373 | `[eval-id value]` | Write large results to dump file |
| `execute-and-respond!` | priv | 386 | `[{:keys [eval-id ...]}]` | Run eval, broadcast, persist, return MCP response |
| `handle-nrepl-send` | pub | 480 | `[params]` | Main tool handler: preprocess → gate → execute |
| `control-tool-definition` | pub | 570 | — | MCP schema for `bridge_control` |
| `start-time` | priv | 581 | — | Server start epoch ms |
| `handle-bridge-control` | priv | 583 | `[params]` | Status/shutdown handler |
| `handle-request` | pub | 611 | `[msg]` | JSON-RPC method dispatcher |
