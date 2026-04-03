(ns nrepl-bridge.db
  "SQLite operations via pod-babashka-go-sqlite3.
   All timestamps generated in Babashka via Instant/now (not SQLite DEFAULT).
   Writes go through an async queue so the MCP response is never blocked."
  (:require [pod.babashka.go-sqlite3 :as sqlite]
            [nrepl-bridge.logging :as log])
  (:import [java.time Instant]))

(def ^:private db-dir ".workbench/db")
(def ^:private db-path (str db-dir "/toolchain.db"))

;; All pod calls go through this lock so reads and writes never
;; interleave on the single stdin/stdout transit channel.
(def ^:private pod-lock (Object.))

(defn- sq-query [& args]
  (locking pod-lock (apply sqlite/query args)))

(defn- sq-execute! [& args]
  (locking pod-lock (apply sqlite/execute! args)))

(def ^:private schema
  "PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS evals (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at     TEXT NOT NULL,
  resolved_at    TEXT,
  target         TEXT NOT NULL CHECK (target IN ('backend', 'frontend')),
  nrepl_port     INTEGER NOT NULL,
  ns             TEXT DEFAULT 'user',
  form           TEXT NOT NULL,
  form_original  TEXT,
  status         TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending', 'evaluating', 'ok',
                                   'error', 'timeout', 'syntax-error')),
  value          TEXT,
  out            TEXT,
  err            TEXT,
  ex             TEXT,
  eval_ms        INTEGER,
  dump_path      TEXT,
  session_id     TEXT,
  decision       TEXT DEFAULT 'auto'
                 CHECK (decision IN ('auto', 'pending', 'approved', 'rejected')),
  feedback       TEXT
);")

(defn- now-utc []
  (str (Instant/now)))

(def ^:private migrations
  ["ALTER TABLE evals ADD COLUMN decision TEXT DEFAULT 'auto' CHECK (decision IN ('auto', 'pending', 'approved', 'rejected'))"
   "ALTER TABLE evals ADD COLUMN feedback TEXT"
   "ALTER TABLE evals ADD COLUMN intent TEXT"])

(defn- apply-migrations!
  "Apply schema migrations. Each is idempotent (catches 'duplicate column' errors)."
  []
  (doseq [sql migrations]
    (try
      (sq-execute! db-path (str sql ";"))
      (catch Exception e
        ;; Ignore "duplicate column" errors -- migration already applied
        (when-not (clojure.string/includes? (.getMessage e) "duplicate column")
          (log/log! :warn (str "Migration warning: " (.getMessage e))))))))

(defn init-db!
  "Create DB directory and apply schema + migrations. Returns db-path."
  []
  (.mkdirs (java.io.File. db-dir))
  (doseq [stmt (remove empty?
                       (map clojure.string/trim
                            (clojure.string/split schema #";")))]
    (sq-execute! db-path (str stmt ";")))
  (apply-migrations!)
  ;; WAL mode + busy timeout reduce Windows file-locking contention
  (sq-execute! db-path "PRAGMA journal_mode=WAL;")
  (sq-execute! db-path "PRAGMA busy_timeout=5000;")
  (log/log! :info (str "SQLite initialized at " db-path " (WAL mode, busy_timeout=5000)"))
  db-path)

(defn resolve-orphaned-evals!
  "Mark any 'evaluating' rows as orphaned. Called on startup to clean up
   rows left behind by a previous server crash or restart."
  []
  (let [ts (now-utc)
        rows (sq-query db-path
                       ["SELECT id FROM evals WHERE status = 'evaluating'"])]
    (when (seq rows)
      (sq-execute! db-path
                   ["UPDATE evals SET status = 'error', err = 'Orphaned by server restart', resolved_at = ? WHERE status = 'evaluating'"
                    ts])
      (log/log! :info (str "Resolved " (count rows) " orphaned evaluating rows")))))

;; --- Fallback for failed writes ---

(def ^:private fallback-path ".workbench/db/missed-writes.edn")
(def missed-write-count (atom 0))

(defn- dump-to-fallback!
  "Append a missed DB write to the fallback EDN file so nothing is lost."
  [label data]
  (try
    (.mkdirs (java.io.File. ".workbench/db"))
    (spit fallback-path
          (str (pr-str (assoc data :_label label :_ts (str (Instant/now)))) "\n")
          :append true)
    (swap! missed-write-count inc)
    (log/log! :warn (str "Missed write saved to " fallback-path " (total: " @missed-write-count ")"))
    (catch Exception e
      (log/log! :error (str "Failed to write fallback file: " (.getMessage e))))))

;; --- Async write queue via Clojure agent ---
;; update-eval! sends to the agent and returns immediately.
;; The agent processes writes sequentially on its own thread (send-off).

(def write-queue-depth (atom 0))

(defn- do-queued-write!
  "Agent action: persist one eval update. Never throws (would halt the agent)."
  [agent-state {:keys [id status value out err ex eval-ms dump-path] :as params}]
  (try
    (let [ts (now-utc)]
      (sq-execute! db-path
                   ["UPDATE evals SET resolved_at=?, status=?, value=?, out=?, err=?, ex=?, eval_ms=?, dump_path=?
       WHERE id=?"
                    ts status value out err ex eval-ms dump-path id]))
    (log/log! :info (str "Queued write #" id " persisted"))
    (catch Exception e
      (log/log! :error (str "Queued write #" id " failed: " (.getMessage e)))
      (dump-to-fallback! (str "queued-update #" id) params))
    (finally
      (swap! write-queue-depth dec)))
  agent-state)

(def ^:private write-agent (agent nil))

(set-error-handler! write-agent
                    (fn [_ag ex]
                      (log/log! :error (str "Write agent error (should not happen): " (.getMessage ex)))))

;; --- Synchronous insert (needs the id back) ---

(def ^:private insert-timeout-ms 5000)

(defn insert-eval!
  "Insert a new eval row with status 'evaluating'. Returns the row id.
   Returns a negative temp ID if the pod times out."
  [{:keys [target port ns form form-original session-id intent] :as params}]
  (let [sentinel (Object.)
        fut (future
              (try
                (let [ts (now-utc)
                      rows (sq-query db-path
                                     ["INSERT INTO evals (created_at, target, nrepl_port, ns, form, form_original, status, session_id, intent)
       VALUES (?, ?, ?, ?, ?, ?, 'evaluating', ?, ?) RETURNING id"
                                      ts target port ns form form-original session-id intent])]
                  (:id (first rows)))
                (catch Exception e {:db-error (.getMessage e)})))
        result (deref fut insert-timeout-ms sentinel)]
    (cond
      (identical? result sentinel)
      (do (log/log! :error (str "INSERT timeout (" insert-timeout-ms "ms) — pod stuck"))
          (future-cancel fut)
          (dump-to-fallback! "insert-eval!" (assoc params :op "insert" :status "evaluating"))
          (- (System/currentTimeMillis)))

      (and (map? result) (:db-error result))
      (do (log/log! :error (str "INSERT error: " (:db-error result)))
          (dump-to-fallback! "insert-eval!" (assoc params :op "insert" :status "evaluating"))
          (- (System/currentTimeMillis)))

      :else result)))

;; --- Async update (fire-and-forget via queue) ---

(defn update-eval!
  "Queue an eval result for async persistence. Returns immediately.
   No-op for negative (temp) IDs from timed-out inserts."
  [{:keys [id] :as params}]
  (when (and id (pos? id))
    (swap! write-queue-depth inc)
    (send-off write-agent do-queued-write! params)
    nil))

;; --- Read operations (synchronous, for dashboard/API) ---

(defn recent-evals
  "Return last n evals, most recent first."
  ([] (recent-evals 20))
  ([n]
   (sq-query db-path
             (str "SELECT * FROM evals ORDER BY id DESC LIMIT " n))))

(defn eval-by-id
  "Return a single eval by id."
  [id]
  (first (sq-query db-path
                   ["SELECT * FROM evals WHERE id=?" id])))

(defn error-evals
  "Return recent evals with non-ok status."
  ([] (error-evals 20))
  ([n]
   (sq-query db-path
             (str "SELECT * FROM evals WHERE status NOT IN ('ok', 'evaluating')
           ORDER BY id DESC LIMIT " n))))

(defn eval-stats
  "Return aggregate statistics."
  []
  (first (sq-query db-path
                   "SELECT
       COUNT(*) as total,
       SUM(CASE WHEN status='ok' THEN 1 ELSE 0 END) as ok,
       SUM(CASE WHEN status='error' THEN 1 ELSE 0 END) as errors,
       SUM(CASE WHEN status='timeout' THEN 1 ELSE 0 END) as timeouts,
       SUM(CASE WHEN status='syntax-error' THEN 1 ELSE 0 END) as syntax_errors,
       SUM(CASE WHEN form_original IS NOT NULL THEN 1 ELSE 0 END) as repaired,
       SUM(CASE WHEN decision='pending' THEN 1 ELSE 0 END) as pending_approvals,
       SUM(CASE WHEN decision='rejected' THEN 1 ELSE 0 END) as rejected,
       ROUND(AVG(eval_ms), 0) as avg_ms
     FROM evals")))

(defn filtered-evals
  "Return evals filtered by status with pagination."
  [{:keys [status limit offset]
    :or {limit 50 offset 0}}]
  (if status
    (sq-query db-path
              [(str "SELECT * FROM evals WHERE status=? ORDER BY id DESC LIMIT " limit " OFFSET " offset)
               status])
    (sq-query db-path
              (str "SELECT * FROM evals ORDER BY id DESC LIMIT " limit " OFFSET " offset))))

(defn pending-approvals
  "Return evals awaiting human approval."
  []
  (sq-query db-path
            "SELECT * FROM evals WHERE decision='pending' ORDER BY id ASC"))

(defn update-decision!
  "Set the decision and optional feedback for an eval."
  [id decision feedback]
  (sq-execute! db-path
               ["UPDATE evals SET decision=?, feedback=? WHERE id=?"
                decision feedback id]))

(defn insert-gated-eval!
  "Insert an eval row with decision='pending' (awaiting approval). Returns the row id."
  [{:keys [target port ns form form-original session-id]}]
  (let [ts (now-utc)
        rows (sq-query db-path
                       ["INSERT INTO evals (created_at, target, nrepl_port, ns, form, form_original, status, decision, session_id)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', 'pending', ?) RETURNING id"
                        ts target port ns form form-original session-id])]
    (:id (first rows))))

(defn eval-decision
  "Read the current decision for an eval."
  [id]
  (:decision (first (sq-query db-path
                              ["SELECT decision FROM evals WHERE id=?" id]))))

(defn duration-stats
  "Return duration percentiles (p50, p95, max) for successful evals."
  []
  (let [rows (sq-query db-path
                       "SELECT eval_ms FROM evals WHERE status='ok' AND eval_ms IS NOT NULL ORDER BY eval_ms ASC")
        durations (mapv :eval_ms rows)
        n (count durations)]
    (if (pos? n)
      {:count n
       :min (first durations)
       :max (last durations)
       :p50 (nth durations (int (* n 0.5)) 0)
       :p95 (nth durations (min (dec n) (int (* n 0.95))) 0)
       :avg (quot (reduce + durations) n)}
      {:count 0 :min 0 :max 0 :p50 0 :p95 0 :avg 0})))
