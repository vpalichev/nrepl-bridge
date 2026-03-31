# Activation -- run on every new session

Template version: **template/v3**

You are an LLM operating the nrepl-bridge eval pipeline.

## Preflight

Run all steps below without asking the user. Report only the final table.

### Step 0: Backend connectivity

```clojure
nrepl_send {form: "(+ 1 2 3)"}
```

Pass if result is `6`. If it fails with "Cannot connect to port 17888", tell the user: **"nREPL is not running. Start it with `npx shadow-cljs watch app`."** Stop here.

### Step 1: Shell bypass -- paths

```clojure
nrepl_send {form: "(str \"/api/health\")"}
```

Pass if result is `"/api/health"`, not `"C:/Program Files/Git/api/health"`.

### Step 2: Shell bypass -- specials

```clojure
nrepl_send {form: "{:dollar \"$100\" :bang \"hello!\" :tick \"`let`\" :braces \"${HOME}\"}"}
```

Pass if all values are literal strings, nothing expanded.

### Step 3: Unicode -- Cyrillic

```clojure
nrepl_send {form: "(let [s \"Привет мир\"] {:len (count s) :val s})"}
```

Pass if `:len` is `10` and `:val` is `"Привет мир"`.

### Step 4: Unicode -- emoji and CJK

```clojure
nrepl_send {form: "{:fire \"🔥\" :cjk \"日本語\" :family \"👨‍👩‍👧‍👦\"}"}
```

Pass if all three values are intact.

### Step 5: State persistence

Two evals in sequence:

```clojure
nrepl_send {form: "(defn pf-add [a b] (+ a b))"}
```

Then:

```clojure
nrepl_send {form: "(pf-add 17 25)"}
```

Pass if second eval returns `42`.

### Step 6: Session recorded in SQLite

```clojure
nrepl_send {form: "(let [conn (java.sql.DriverManager/getConnection \"jdbc:sqlite:.workbench/db/toolchain.db\") stmt (.createStatement conn) rs (.executeQuery stmt \"SELECT session_id FROM evals WHERE session_id IS NOT NULL ORDER BY id DESC LIMIT 1\") result (when (.next rs) (.getString rs 1))] (.close conn) (some? result))"}
```

Pass if result is `true` (confirms session ID is being recorded in the audit trail).

### Step 7: Frontend connectivity (skip if no browser open)

```clojure
nrepl_send {form: "(count (shadow.cljs.devtools.api/repl-runtimes :app))"}
```

If result is `1`, test frontend eval:

```clojure
nrepl_send {form: "(shadow.cljs.devtools.api/cljs-eval :app \"(+ 40 2)\" {})"}
```

Pass if result contains `"42"`. If runtime count is `0`, mark as SKIP and tell user to open http://localhost:8280.

### Reporting

Print this table filled in. No explanations, just the table:

```
nrepl-bridge template/v3

| # | Check | Result |
|---|-------|--------|
| 0 | Backend connectivity | |
| 1 | Path bypass | |
| 2 | Shell specials | |
| 3 | Cyrillic | |
| 4 | Emoji + CJK | |
| 5 | State persistence | |
| 6 | Session isolation | |
| 7 | Frontend | |
```

If all pass (or 7 is SKIP), say **"Pipeline active."** and proceed with whatever the user asked for.

If any of 0-6 fail, report the failure and stop.

### Cleanup

```clojure
nrepl_send {form: "(ns-unmap 'user 'pf-add)"}
```
