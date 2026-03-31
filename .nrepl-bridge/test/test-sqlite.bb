#!/usr/bin/env bb
;; test-sqlite.bb -- Thorough SQLite read/write tests for nrepl-bridge.db
;;
;; Tests the DB layer directly (no MCP server, no nREPL).
;; Uses a temporary database to avoid polluting the real one.
;;
;; Usage: bb nrepl-bridge/test-sqlite.bb

(require '[babashka.pods :as pods]
         '[babashka.classpath :as cp]
         '[clojure.string :as str])

;; Add src/ to classpath
(let [script-dir (str (.getParent (java.io.File. (System/getProperty "babashka.file" "."))))]
  (cp/add-classpath (str script-dir "/src")))

;; Load SQLite pod
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[pod.babashka.go-sqlite3 :as sqlite]
         '[nrepl-bridge.logging :as log])

;; --- Override db-path to use a temp database ---

(def test-db-dir ".workbench/db")
(def test-db-path (str test-db-dir "/test-sqlite.db"))

;; Delete any previous test db
(let [f (java.io.File. test-db-path)]
  (when (.exists f) (.delete f)))

;; We need to override the private db-path in nrepl-bridge.db.
;; Since it's a def, we alter the var root.
(require '[nrepl-bridge.db :as db])
(alter-var-root #'db/db-path (constantly test-db-path))

;; --- Test infrastructure ---

(def results (atom []))

(defn ci? [s substr]
  (and (some? s) (some? substr)
       (str/includes? (str/lower-case (str s)) (str/lower-case (str substr)))))

(defn record! [test-id description pass? detail]
  (swap! results conj {:test test-id :desc description :pass pass? :detail detail})
  (let [status (if pass? "PASS" "FAIL")]
    (println (str "  " status " " test-id " -- " description
                  (when-not pass? (str "\n         " detail))))))

;; --- Tests ---

(println "=== SQLite Database Tests ===")
(println (str "Test DB: " test-db-path))
(println)

;; S1 -- Init creates DB and schema
(println "--- S1: Init DB ---")
(let [path (db/init-db!)]
  (record! "S1" "init-db! creates database and returns path"
           (and (= path test-db-path)
                (.exists (java.io.File. test-db-path)))
           (str "path=" path)))

;; S2 -- Init is idempotent (run twice)
(println "--- S2: Init idempotent ---")
(let [path (db/init-db!)]
  (record! "S2" "init-db! runs twice without error"
           (= path test-db-path)
           (str "path=" path)))

;; S3 -- Schema has decision and feedback columns
(println "--- S3: Schema columns ---")
(let [info (sqlite/query test-db-path "PRAGMA table_info(evals)")
      col-names (set (mapv :name info))]
  (record! "S3" "Schema includes decision and feedback columns"
           (and (contains? col-names "decision")
                (contains? col-names "feedback")
                (contains? col-names "form")
                (contains? col-names "status")
                (contains? col-names "created_at"))
           (str "columns=" (pr-str col-names))))

;; S4 -- Insert and read back
(println "--- S4: Insert + read ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(+ 1 2)" :form-original nil})]
  (let [row (db/eval-by-id id)]
    (record! "S4" "Insert eval and read by id"
             (and (= id (:id row))
                  (= "backend" (:target row))
                  (= 17888 (:nrepl_port row))
                  (= "user" (:ns row))
                  (= "(+ 1 2)" (:form row))
                  (nil? (:form_original row))
                  (= "evaluating" (:status row))
                  (= "auto" (:decision row))
                  (some? (:created_at row)))
             (str "row=" (pr-str row)))))

;; S5 -- Update eval with results
(println "--- S5: Update eval ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(+ 10 20)" :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value "30" :out "stdout-here"
                    :err nil :ex nil :eval-ms 42 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S5" "Update eval sets status, value, eval_ms, resolved_at"
             (and (= "ok" (:status row))
                  (= "30" (:value row))
                  (= "stdout-here" (:out row))
                  (= 42 (:eval_ms row))
                  (some? (:resolved_at row)))
             (str "row=" (pr-str (select-keys row [:status :value :out :eval_ms :resolved_at]))))))

;; S6 -- Insert with form_original (paren repair)
(println "--- S6: form_original ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(+ 1 2)" :form-original "(+ 1 2"})]
  (db/update-eval! {:id id :status "ok" :value "3" :out nil :err nil :ex nil :eval-ms 5 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S6" "form_original stored when paren repair applied"
             (and (= "(+ 1 2)" (:form row))
                  (= "(+ 1 2" (:form_original row)))
             (str "form=" (:form row) " original=" (:form_original row)))))

;; S7 -- Error eval
(println "--- S7: Error eval ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(/ 1 0)" :form-original nil})]
  (db/update-eval! {:id id :status "error" :value nil :out nil
                    :err "ArithmeticException" :ex "class java.lang.ArithmeticException"
                    :eval-ms 2 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S7" "Error eval stores err and ex"
             (and (= "error" (:status row))
                  (ci? (:err row) "Arithmetic")
                  (ci? (:ex row) "ArithmeticException"))
             (str "status=" (:status row) " err=" (:err row)))))

;; S8 -- Timeout eval
(println "--- S8: Timeout eval ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(Thread/sleep 999999)" :form-original nil})]
  (db/update-eval! {:id id :status "timeout" :value nil :out nil :err "Timeout after 5000ms"
                    :ex nil :eval-ms 5000 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S8" "Timeout eval stores correctly"
             (and (= "timeout" (:status row))
                  (= 5000 (:eval_ms row)))
             (str "status=" (:status row)))))

;; S9 -- Syntax error eval
(println "--- S9: Syntax error eval ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(str \"unclosed" :form-original nil})]
  (db/update-eval! {:id id :status "syntax-error" :value nil :out nil
                    :err "Unclosed string" :ex nil :eval-ms 0 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S9" "Syntax error eval"
             (= "syntax-error" (:status row))
             (str "status=" (:status row)))))

;; S10 -- Frontend target
(println "--- S10: Frontend target ---")
(let [id (db/insert-eval! {:target "frontend" :port 17888 :ns "cljs.user"
                           :form "(+ 40 2)" :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value "42" :out nil :err nil :ex nil :eval-ms 100 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S10" "Frontend target stored correctly"
             (and (= "frontend" (:target row))
                  (= "cljs.user" (:ns row)))
             (str "target=" (:target row) " ns=" (:ns row)))))

;; S11 -- recent-evals returns most recent first
(println "--- S11: recent-evals ordering ---")
(let [rows (db/recent-evals 5)]
  (record! "S11" "recent-evals returns newest first"
           (and (seq rows)
                (> (:id (first rows)) (:id (last rows))))
           (str "ids=" (mapv :id rows))))

;; S12 -- error-evals filters correctly
(println "--- S12: error-evals filter ---")
(let [rows (db/error-evals 10)]
  (record! "S12" "error-evals excludes ok and evaluating"
           (every? #(not (#{"ok" "evaluating"} (:status %))) rows)
           (str "statuses=" (mapv :status rows))))

;; S13 -- eval-stats aggregation
(println "--- S13: eval-stats ---")
(let [s (db/eval-stats)]
  (record! "S13" "eval-stats returns correct aggregates"
           (and (pos? (:total s))
                (some? (:ok s))
                (some? (:errors s))
                (some? (:timeouts s))
                (some? (:syntax_errors s))
                (some? (:pending_approvals s))
                (some? (:rejected s)))
           (str "stats=" (pr-str s))))

;; S14 -- filtered-evals by status
(println "--- S14: filtered-evals ---")
(let [ok-rows (db/filtered-evals {:status "ok"})
      err-rows (db/filtered-evals {:status "error"})
      all-rows (db/filtered-evals {})]
  (record! "S14" "filtered-evals filters by status"
           (and (every? #(= "ok" (:status %)) ok-rows)
                (every? #(= "error" (:status %)) err-rows)
                (>= (count all-rows) (+ (count ok-rows) (count err-rows))))
           (str "ok=" (count ok-rows) " err=" (count err-rows) " all=" (count all-rows))))

;; S15 -- filtered-evals pagination
(println "--- S15: filtered-evals pagination ---")
(let [page1 (db/filtered-evals {:limit 3 :offset 0})
      page2 (db/filtered-evals {:limit 3 :offset 3})]
  (record! "S15" "Pagination returns different rows"
           (and (<= (count page1) 3)
                (let [ids1 (set (mapv :id page1))
                      ids2 (set (mapv :id page2))]
                  (empty? (clojure.set/intersection ids1 ids2))))
           (str "page1-ids=" (mapv :id page1) " page2-ids=" (mapv :id page2))))

;; S16 -- Gated eval insert
(println "--- S16: Gated eval insert ---")
(let [id (db/insert-gated-eval! {:target "backend" :port 17888 :ns "user"
                                 :form "(swap! state inc)" :form-original nil})]
  (let [row (db/eval-by-id id)]
    (record! "S16" "Gated eval inserts with decision=pending, status=pending"
             (and (= "pending" (:decision row))
                  (= "pending" (:status row)))
             (str "decision=" (:decision row) " status=" (:status row)))))

;; S17 -- Update decision (approve)
(println "--- S17: Approve decision ---")
(let [id (db/insert-gated-eval! {:target "backend" :port 17888 :ns "user"
                                 :form "(reset! x 0)" :form-original nil})]
  (db/update-decision! id "approved" "Looks safe")
  (let [row (db/eval-by-id id)]
    (record! "S17" "update-decision! sets approved + feedback"
             (and (= "approved" (:decision row))
                  (= "Looks safe" (:feedback row)))
             (str "decision=" (:decision row) " feedback=" (:feedback row)))))

;; S18 -- Update decision (reject)
(println "--- S18: Reject decision ---")
(let [id (db/insert-gated-eval! {:target "backend" :port 17888 :ns "user"
                                 :form "(System/exit 1)" :form-original nil})]
  (db/update-decision! id "rejected" "Too dangerous")
  (let [row (db/eval-by-id id)]
    (record! "S18" "update-decision! sets rejected + feedback"
             (and (= "rejected" (:decision row))
                  (= "Too dangerous" (:feedback row)))
             (str "decision=" (:decision row) " feedback=" (:feedback row)))))

;; S19 -- pending-approvals query
(println "--- S19: pending-approvals ---")
(let [pending (db/pending-approvals)]
  ;; S16 inserted one pending that was NOT approved/rejected
  (record! "S19" "pending-approvals returns only pending rows"
           (and (seq pending)
                (every? #(= "pending" (:decision %)) pending))
           (str "count=" (count pending) " decisions=" (mapv :decision pending))))

;; S20 -- eval-decision read
(println "--- S20: eval-decision ---")
(let [id (db/insert-gated-eval! {:target "backend" :port 17888 :ns "user"
                                 :form "(spit \"x\" \"y\")" :form-original nil})]
  (let [d1 (db/eval-decision id)]
    (db/update-decision! id "approved" nil)
    (let [d2 (db/eval-decision id)]
      (record! "S20" "eval-decision reads current decision"
               (and (= "pending" d1) (= "approved" d2))
               (str "before=" d1 " after=" d2)))))

;; S21 -- duration-stats
(println "--- S21: duration-stats ---")
(let [s (db/duration-stats)]
  (record! "S21" "duration-stats returns p50, p95, avg"
           (and (pos? (:count s))
                (some? (:p50 s))
                (some? (:p95 s))
                (some? (:avg s))
                (<= (:min s) (:p50 s))
                (<= (:p50 s) (:p95 s))
                (<= (:p95 s) (:max s)))
           (str "stats=" (pr-str s))))

;; S22 -- Unicode in form and value
(println "--- S22: Unicode round-trip ---")
(let [form "(str \"\u041f\u0440\u0438\u0432\u0435\u0442\" \" \" \"\ud83c\udf89\")"
      value "\"\u041f\u0440\u0438\u0432\u0435\u0442 \ud83c\udf89\""
      id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form form :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value value :out nil :err nil :ex nil :eval-ms 1 :dump-path nil})
  (let [row (db/eval-by-id id)
        to-hex (fn [s] (when s (apply str (mapv #(format "%04x" (int %)) s))))
        form-match (= (to-hex form) (to-hex (:form row)))
        value-match (= (to-hex value) (to-hex (:value row)))]
    (record! "S22" "Unicode (Cyrillic + emoji) round-trips through SQLite"
             (and form-match value-match)
             (str "form-match=" form-match " value-match=" value-match))))

;; S23 -- Large value in form field
(println "--- S23: Large form ---")
(let [big-form (str "(+ " (str/join " " (repeat 2000 "1")) ")")
      id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form big-form :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value "2000" :out nil :err nil :ex nil :eval-ms 50 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S23" "Large form (4KB+) stored and read back"
             (and (= big-form (:form row))
                  (> (count (:form row)) 3000))
             (str "form-length=" (count (:form row))))))

;; S24 -- Special chars in form (SQL injection attempt)
(println "--- S24: SQL injection in data ---")
(let [evil-form "'; DROP TABLE evals; --"
      id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form evil-form :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value evil-form :out nil :err nil :ex nil :eval-ms 1 :dump-path nil})
  (let [row (db/eval-by-id id)
        ;; Verify table still exists
        cnt (:cnt (first (sqlite/query test-db-path ["SELECT count(*) as cnt FROM evals"])))]
    (record! "S24" "SQL injection in data: stored safely, table intact"
             (and (= evil-form (:form row))
                  (= evil-form (:value row))
                  (pos? cnt))
             (str "form=" (:form row) " table-rows=" cnt))))

;; S25 -- Null and empty string handling
(println "--- S25: Null vs empty string ---")
(let [id (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                           :form "(identity nil)" :form-original nil})]
  (db/update-eval! {:id id :status "ok" :value "" :out "" :err nil :ex nil :eval-ms 1 :dump-path nil})
  (let [row (db/eval-by-id id)]
    (record! "S25" "Empty string vs nil distinguished"
             (and (= "" (:value row))
                  (= "" (:out row))
                  (nil? (:err row)))
             (str "value=" (pr-str (:value row))
                  " out=" (pr-str (:out row))
                  " err=" (pr-str (:err row))))))

;; S26 -- CHECK constraint: invalid status rejected
(println "--- S26: CHECK constraint on status ---")
(let [error? (try
               (sqlite/execute! test-db-path
                                ["INSERT INTO evals (created_at, target, nrepl_port, form, status)
                   VALUES ('2026-01-01', 'backend', 9999, 'test', 'INVALID')"])
               false
               (catch Exception _e true))]
  (record! "S26" "CHECK constraint rejects invalid status"
           error?
           (str "error-thrown=" error?)))

;; S27 -- CHECK constraint: invalid decision rejected
(println "--- S27: CHECK constraint on decision ---")
(let [error? (try
               (sqlite/execute! test-db-path
                                ["INSERT INTO evals (created_at, target, nrepl_port, form, status, decision)
                   VALUES ('2026-01-01', 'backend', 9999, 'test', 'ok', 'BOGUS')"])
               false
               (catch Exception _e true))]
  (record! "S27" "CHECK constraint rejects invalid decision"
           error?
           (str "error-thrown=" error?)))

;; S28 -- CHECK constraint: invalid target rejected
(println "--- S28: CHECK constraint on target ---")
(let [error? (try
               (sqlite/execute! test-db-path
                                ["INSERT INTO evals (created_at, target, nrepl_port, form, status)
                   VALUES ('2026-01-01', 'neither', 9999, 'test', 'ok')"])
               false
               (catch Exception _e true))]
  (record! "S28" "CHECK constraint rejects invalid target"
           error?
           (str "error-thrown=" error?)))

;; S29 -- Concurrent-ish writes (rapid sequential inserts)
(println "--- S29: Rapid sequential writes ---")
(let [ids (mapv (fn [i]
                  (db/insert-eval! {:target "backend" :port 17888 :ns "user"
                                    :form (str "(+ " i " 1)") :form-original nil}))
                (range 50))
      all-unique (= (count ids) (count (set ids)))]
  (doseq [id ids]
    (db/update-eval! {:id id :status "ok" :value "done" :out nil :err nil :ex nil :eval-ms 1 :dump-path nil}))
  (let [rows (mapv db/eval-by-id ids)
        all-ok (every? #(= "ok" (:status %)) rows)]
    (record! "S29" "50 rapid inserts + updates, all unique IDs, all ok"
             (and all-unique all-ok)
             (str "unique=" all-unique " all-ok=" all-ok " count=" (count ids)))))

;; S30 -- WAL mode is active
(println "--- S30: WAL mode ---")
(let [mode (first (sqlite/query test-db-path "PRAGMA journal_mode"))]
  (record! "S30" "WAL journal mode is active"
           (ci? (str (:journal_mode mode)) "wal")
           (str "mode=" (pr-str mode))))

;; --- Cleanup ---
(let [f (java.io.File. test-db-path)]
  (when (.exists f) (.delete f)))
(println)

;; --- Summary ---
(println "=== Summary ===")
(let [passed (count (filter :pass @results))
      total  (count @results)]
  (doseq [r @results]
    (println (str "  " (if (:pass r) "PASS" "FAIL") " " (:test r) " -- " (:desc r))))
  (println)
  (println (str passed "/" total " tests passed"))
  (when (< passed total)
    (println "FAILED tests:")
    (doseq [r (remove :pass @results)]
      (println (str "  " (:test r) ": " (:detail r)))))
  (System/exit (if (= passed total) 0 1)))
