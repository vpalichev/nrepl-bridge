(ns nrepl-bridge.gate
  "Approval gate: classifies forms as auto-approved or requiring human approval.
   When gated, the MCP server pauses and polls SQLite for a decision."
  (:require [clojure.string :as str]
            [nrepl-bridge.db :as db]
            [nrepl-bridge.logging :as log]
            [nrepl-bridge.web :as web]))

;; --- Classification rules ---

;; Patterns that indicate potentially unsafe operations.
;; A form matching any pattern is gated (requires approval).
(def ^:private unsafe-patterns
  [;; Functions ending with ! (Clojure convention for side effects)
   ;; But exclude safe ones: dispatch-sync!, dispatch!, println, prn
   #"(?i)\b(?!dispatch-sync!|dispatch!|println|prn|pr-str|flush)(\w+!)\s*"
   ;; System-level operations
   #"(?i)System/exit"
   #"(?i)shutdown-agents"
   #"(?i)alter-var-root"
   ;; File/IO operations
   #"(?i)\bspit\b"
   #"(?i)\bdelete\b"
   ;; Database operations
   #"(?i)\bdrop\s+(table|index|view)"
   #"(?i)\btruncate\b"
   ;; Reload-all (can break running system)
   #":reload-all"])

;; Forms that are always auto-approved even if they match unsafe patterns.
;; These are common safe operations that happen to use ! names.
(def ^:private safe-allowlist
  [#"^\s*\(require\s"
   #"^\s*\(println\s"
   #"^\s*\(prn\s"
   #"^\s*\(pr-str\s"
   #"^\s*\(flush\)"
   #"^\s*\(re-frame\.core/dispatch-sync\s"
   #"^\s*\(re-frame\.core/dispatch\s"
   #"^\s*\(rf/dispatch-sync\s"
   #"^\s*\(rf/dispatch\s"])

(defn classify-form
  "Classify a form as :auto or :gated.
   Returns :auto for safe forms, :gated for forms requiring human approval."
  [form]
  ;; Check allowlist first -- safe patterns bypass the gate
  ;; Check unsafe patterns first for forms that are ALWAYS dangerous
  ;; (reload-all bypasses the require allowlist)
  (if (re-find #":reload-all" form)
    :gated
    ;; Then check allowlist -- safe patterns bypass the gate
    (if (some #(re-find % form) safe-allowlist)
      :auto
      ;; Then check unsafe patterns
      (if (some #(re-find % form) unsafe-patterns)
        :gated
        :auto))))

;; --- Poll-wait for decision ---

(defn wait-for-decision!
  "Poll SQLite every 500ms waiting for a decision on the given eval id.
   Returns the decision (:approved or :rejected) or :timeout.
   Broadcasts to WebSocket clients when the eval is pending."
  [eval-id timeout-ms]
  (web/broadcast! {:type "approval-request" :id eval-id})
  (log/log! :info (str "Waiting for approval on eval #" eval-id
                       " (timeout: " timeout-ms "ms)"))
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [elapsed (- (System/currentTimeMillis) start)]
        (if (> elapsed timeout-ms)
          (do
            (log/log! :warn (str "Approval timeout for eval #" eval-id
                                 " after " elapsed "ms"))
            :timeout)
          (let [decision (db/eval-decision eval-id)]
            (cond
              (= decision "approved")
              (do
                (log/log! :info (str "Eval #" eval-id " approved"))
                :approved)

              (= decision "rejected")
              (do
                (log/log! :info (str "Eval #" eval-id " rejected"))
                :rejected)

              :else
              (do
                (Thread/sleep 500)
                (recur)))))))))
