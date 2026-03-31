(ns nrepl-bridge.dashboard
  "CLI query tool for eval history in SQLite."
  (:require [nrepl-bridge.db :as db]
            [clojure.string :as str]))

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    s))

(defn- format-row
  "Format a single eval row for display."
  [row]
  (str "#" (:id row)
       " [" (:status row) "]"
       " " (:target row) ":" (:nrepl_port row)
       " ns=" (:ns row)
       (when (:eval_ms row) (str " " (:eval_ms row) "ms"))
       (when (:form_original row) " REPAIRED")
       "\n  form: " (truncate (:form row) 100)
       (when (:value row) (str "\n  => " (truncate (:value row) 120)))
       (when (:err row) (str "\n  err: " (truncate (:err row) 120)))
       (when (:ex row) (str "\n  ex: " (:ex row)))
       "\n  at: " (:created_at row)
       (when (:dump_path row) (str "\n  dump: " (:dump_path row)))))

(defn cmd-recent
  "Show last 20 evals."
  []
  (let [rows (db/recent-evals)]
    (if (empty? rows)
      (println "No evals recorded yet.")
      (doseq [row rows]
        (println (format-row row))
        (println "---")))))

(defn cmd-errors
  "Show recent failures."
  []
  (let [rows (db/error-evals)]
    (if (empty? rows)
      (println "No errors recorded.")
      (doseq [row rows]
        (println (format-row row))
        (println "---")))))

(defn cmd-stats
  "Show aggregate statistics."
  []
  (let [s (db/eval-stats)]
    (println (str "Total evals: " (:total s)))
    (println (str "  OK:           " (:ok s)))
    (println (str "  Errors:       " (:errors s)))
    (println (str "  Timeouts:     " (:timeouts s)))
    (println (str "  Syntax errs:  " (:syntax_errors s)))
    (println (str "  Repaired:     " (:repaired s)))
    (println (str "  Avg eval ms:  " (:avg_ms s)))))

(defn cmd-detail
  "Show full detail for one eval."
  [id]
  (let [row (db/eval-by-id (parse-long id))]
    (if row
      (do
        (println (str "Eval #" (:id row)))
        (println (str "  Status:    " (:status row)))
        (println (str "  Target:    " (:target row) ":" (:nrepl_port row)))
        (println (str "  NS:        " (:ns row)))
        (println (str "  Created:   " (:created_at row)))
        (println (str "  Resolved:  " (:resolved_at row)))
        (println (str "  Eval ms:   " (:eval_ms row)))
        (println (str "  Form:      " (:form row)))
        (when (:form_original row)
          (println (str "  Original:  " (:form_original row))))
        (println (str "  Value:     " (:value row)))
        (when (:out row)
          (println (str "  Stdout:    " (:out row))))
        (when (:err row)
          (println (str "  Stderr:    " (:err row))))
        (when (:ex row)
          (println (str "  Exception: " (:ex row))))
        (when (:dump_path row)
          (println (str "  Dump:      " (:dump_path row)))))
      (println (str "Eval #" id " not found.")))))

(defn cmd-tail
  "Poll for new evals (Ctrl+C to stop)."
  []
  (println "Tailing evals (Ctrl+C to stop)...")
  (let [last-id (atom (or (:id (first (db/recent-evals 1))) 0))]
    (loop []
      (let [rows (db/recent-evals 5)
            new  (filterv #(> (:id %) @last-id) rows)]
        (when (seq new)
          (doseq [row (reverse new)]
            (println (format-row row))
            (println "---"))
          (reset! last-id (:id (first new)))))
      (Thread/sleep 1000)
      (recur))))

(defn -main [& args]
  (let [cmd (first args)]
    (case cmd
      "recent" (cmd-recent)
      "errors" (cmd-errors)
      "stats"  (cmd-stats)
      "detail" (cmd-detail (second args))
      "tail"   (cmd-tail)
      (do
        (println "Usage: bb -m nrepl-bridge.dashboard <command>")
        (println "Commands: recent, errors, stats, detail <id>, tail")))))
