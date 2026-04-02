(ns nrepl-bridge.db
  "SQLite operations via pod-babashka-go-sqlite3.
   All timestamps generated in Babashka via Instant/now (not SQLite DEFAULT)."
  (:require [pod.babashka.go-sqlite3 :as sqlite]
            [nrepl-bridge.logging :as log])
  (:import [java.time Instant]))

(def ^:private db-dir ".workbench/db")
(def ^:private db-path (str db-dir "/toolchain.db"))

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
      (sqlite/execute! db-path (str sql ";"))
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
    (sqlite/execute! db-path (str stmt ";")))
  (apply-migrations!)
  ;; WAL mode + busy timeout reduce Windows file-locking contention
  (sqlite/execute! db-path "PRAGMA journal_mode=WAL;")
  (sqlite/execute! db-path "PRAGMA busy_timeout=5000;")
  (log/log! :info (str "SQLite initialized at " db-path " (WAL mode, busy_timeout=5000)"))
  db-path)

(defn resolve-orphaned-evals!
  "Mark any 'evaluating' rows as orphaned. Called on startup to clean up
   rows left behind by a previous server crash or restart."
  []
  (let [ts (now-utc)
        rows (sqlite/query db-path
                           ["SELECT id FROM evals WHERE status = 'evaluating'"])]
    (when (seq rows)
      (sqlite/execute! db-path
                       ["UPDATE evals SET status = 'error', err = 'Orphaned by server restart', resolved_at = ? WHERE status = 'evaluating'"
                        ts])
      (log/log! :info (str "Resolved " (count rows) " orphaned evaluating rows")))))

(def ^:private db-write-timeout-ms 3000)
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

(defn- with-db-timeout
  "Execute f with a hard deadline. On timeout, dumps data to fallback file.
   Returns f's result on success, or {:db-timeout true} if the pod hangs."
  [label f fallback-data]
  (let [sentinel (Object.)
        fut (future (try (f) (catch Exception e {:db-error (.getMessage e)})))
        result (deref fut db-write-timeout-ms sentinel)]
    (if (identical? result sentinel)
      (do
        (log/log! :error (str "DB WRITE TIMEOUT (" db-write-timeout-ms "ms) on " label
                              " — go-sqlite3 pod is stuck. MCP response will proceed without persistence."))
        (future-cancel fut)
        (dump-to-fallback! label fallback-data)
        {:db-timeout true})
      (if (and (map? result) (:db-error result))
        (do (log/log! :error (str "DB WRITE ERROR on " label ": " (:db-error result)))
            (dump-to-fallback! label fallback-data)
            result)
        result))))

(defn insert-eval!
  "Insert a new eval row with status 'evaluating'. Returns the row id.
   Returns a negative temp ID if the pod times out."
  [{:keys [target port ns form form-original session-id intent] :as params}]
  (let [result (with-db-timeout "insert-eval!"
                 (fn []
                   (let [ts (now-utc)
                         rows (sqlite/query db-path
                                            ["INSERT INTO evals (created_at, target, nrepl_port, ns, form, form_original, status, session_id, intent)
       VALUES (?, ?, ?, ?, ?, ?, 'evaluating', ?, ?) RETURNING id"
                                             ts target port ns form form-original session-id intent])]
                     (:id (first rows))))
                 (assoc params :op "insert" :status "evaluating"))]
    (if (and (map? result) (:db-timeout result))
      (- (System/currentTimeMillis))  ;; negative temp ID
      result)))

(defn update-eval!
  "Update an eval row with results. No-op for negative (temp) IDs from timed-out inserts."
  [{:keys [id status value out err ex eval-ms dump-path] :as params}]
  (when (and id (pos? id))
    (with-db-timeout "update-eval!"
      (fn []
        (let [ts (now-utc)]
          (sqlite/execute! db-path
                           ["UPDATE evals SET resolved_at=?, status=?, value=?, out=?, err=?, ex=?, eval_ms=?, dump_path=?
       WHERE id=?"
                            ts status value out err ex eval-ms dump-path id])))
      (assoc params :op "update"))))

(defn recent-evals
  "Return last n evals, most recent first."
  ([] (recent-evals 20))
  ([n]
   (sqlite/query db-path
                 (str "SELECT * FROM evals ORDER BY id DESC LIMIT " n))))

(defn eval-by-id
  "Return a single eval by id."
  [id]
  (first (sqlite/query db-path
                       ["SELECT * FROM evals WHERE id=?" id])))

(defn error-evals
  "Return recent evals with non-ok status."
  ([] (error-evals 20))
  ([n]
   (sqlite/query db-path
                 (str "SELECT * FROM evals WHERE status NOT IN ('ok', 'evaluating')
           ORDER BY id DESC LIMIT " n))))

(defn eval-stats
  "Return aggregate statistics."
  []
  (first (sqlite/query db-path
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
    (sqlite/query db-path
                  [(str "SELECT * FROM evals WHERE status=? ORDER BY id DESC LIMIT " limit " OFFSET " offset)
                   status])
    (sqlite/query db-path
                  (str "SELECT * FROM evals ORDER BY id DESC LIMIT " limit " OFFSET " offset))))

(defn pending-approvals
  "Return evals awaiting human approval."
  []
  (sqlite/query db-path
                "SELECT * FROM evals WHERE decision='pending' ORDER BY id ASC"))

(defn update-decision!
  "Set the decision and optional feedback for an eval."
  [id decision feedback]
  (sqlite/execute! db-path
                   ["UPDATE evals SET decision=?, feedback=? WHERE id=?"
                    decision feedback id]))

(defn insert-gated-eval!
  "Insert an eval row with decision='pending' (awaiting approval). Returns the row id."
  [{:keys [target port ns form form-original session-id]}]
  (let [ts (now-utc)
        rows (sqlite/query db-path
                           ["INSERT INTO evals (created_at, target, nrepl_port, ns, form, form_original, status, decision, session_id)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', 'pending', ?) RETURNING id"
                            ts target port ns form form-original session-id])]
    (:id (first rows))))

(defn eval-decision
  "Read the current decision for an eval."
  [id]
  (:decision (first (sqlite/query db-path
                                  ["SELECT decision FROM evals WHERE id=?" id]))))

(defn duration-stats
  "Return duration percentiles (p50, p95, max) for successful evals."
  []
  (let [rows (sqlite/query db-path
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
