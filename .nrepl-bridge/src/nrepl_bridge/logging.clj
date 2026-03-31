(ns nrepl-bridge.logging
  "File-based diagnostic logging with UTC timestamps.
   All entries go to .workbench/logs/nrepl-bridge.log."
  (:require [clojure.string :as str])
  (:import [java.time Instant]))

(def ^:private log-dir ".workbench/logs")
(def ^:private log-file (str log-dir "/nrepl-bridge.log"))
(def ^:private dir-ensured? (atom false))

(defn init!
  "Ensure log directory exists."
  []
  (.mkdirs (java.io.File. log-dir))
  (reset! dir-ensured? true))

(defn- ensure-dir! []
  (when-not @dir-ensured?
    (init!)))

(defn- now-utc []
  (str (Instant/now)))

(defn log!
  "Append a log line: [timestamp] [level] message"
  [level msg]
  (ensure-dir!)
  (let [line (str "[" (now-utc) "] [" (str/upper-case (name level)) "] " msg "\n")]
    (spit log-file line :append true)))

(defn log-startup!
  "Log a startup self-check result."
  [check-name passed? detail]
  (log! (if passed? :info :warn)
        (str "STARTUP " (if passed? "PASS" "FAIL") " " check-name
             (when detail (str " -- " detail)))))

(defn log-eval!
  "Log an eval event with structured fields."
  [{:keys [id target port ns form-length form-preview status eval-ms
           repaired? dump-path]}]
  (log! :info
        (str "EVAL #" id
             " target=" target
             " port=" port
             " ns=" ns
             " form-len=" form-length
             " status=" status
             (when eval-ms (str " eval-ms=" eval-ms))
             (when repaired? " REPAIRED")
             (when dump-path (str " dump=" dump-path))
             " form=" (subs (str form-preview)
                            0 (min 120 (count (str form-preview)))))))
