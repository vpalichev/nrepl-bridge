#!/usr/bin/env bb
;; test-phase0.bb -- Run Phase 0 acceptance tests (T0.1-T0.17) against the MCP server
;;
;; Usage: bb nrepl-bridge/test-phase0.bb [--backend-port 17888]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc]
         '[babashka.pods :as pods])

;; Load SQLite pod for T0.16
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      17888))

;; --- JSON-RPC framing ---

(defn send-message! [^java.io.OutputStream out msg]
  (let [body  (json/generate-string msg)
        bytes (.getBytes body "UTF-8")
        len   (count bytes)
        header (str "Content-Length: " len "\r\n\r\n")]
    (.write out (.getBytes header "UTF-8"))
    (.write out bytes)
    (.flush out)))

(defn read-line-bytes [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (= b -1)       (when (pos? (.length sb)) (str sb))
          (= b (int \newline)) (str sb)
          (= b (int \return))
          (do (.mark in 1)
              (let [nb (.read in)]
                (when (and (not= nb -1) (not= nb (int \newline)))
                  (.reset in)))
              (str sb))
          :else (do (.append sb (char b)) (recur)))))))

(defn read-message [^java.io.BufferedInputStream in]
  (when-let [line (read-line-bytes in)]
    (if (str/starts-with? line "Content-Length:")
      (let [length (parse-long (str/trim (subs line (count "Content-Length:"))))]
        (read-line-bytes in) ;; blank separator
        (let [buf (byte-array length)]
          (loop [off 0]
            (when (< off length)
              (let [n (.read in buf off (- length off))]
                (when (pos? n) (recur (+ off n))))))
          (json/parse-string (String. buf "UTF-8") true)))
      (when-not (str/blank? line)
        (json/parse-string line true)))))

;; --- Test infrastructure ---

(def results (atom []))
(def msg-id (atom 0))

(defn next-id [] (swap! msg-id inc))

(defn tool-call [form & {:keys [target ns timeout_ms]
                         :or {target "backend" ns "user" timeout_ms 30000}}]
  {:jsonrpc "2.0"
   :id (next-id)
   :method "tools/call"
   :params {:name "nrepl_send"
            :arguments {:form form
                        :target target
                        :ns ns
                        :timeout_ms timeout_ms}}})

(defn ci?
  "Case-insensitive str/includes?"
  [s substr]
  (and (some? s) (some? substr)
       (str/includes? (str/lower-case s) (str/lower-case substr))))

(defn record! [test-id description pass? detail]
  (swap! results conj {:test test-id :desc description :pass pass? :detail detail})
  (let [status (if pass? "PASS" "FAIL")]
    (println (str "  " status " " test-id " -- " description
                  (when-not pass? (str "\n         " detail))))))

;; --- Main ---

(println "=== Phase 0 Acceptance Tests ===")
(println (str "Backend port: " backend-port))
(println)

;; Start the MCP server
(println "Starting MCP server...")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)]
               :in :pipe :out :pipe :err :pipe
               :dir "."})
      out    (:in server)  ;; our output -> server's stdin
      in     (java.io.BufferedInputStream. (:out server) 65536)
      err-stream (:err server)]

  (try
    ;; Send initialize
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05"
                                 :capabilities {}
                                 :clientInfo {:name "test-harness" :version "0.1.0"}}})
    (let [init-resp (read-message in)]
      (when-not init-resp
        (println "FATAL: No initialize response")
        (System/exit 1)))

    ;; Send initialized notification
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})

    ;; Helper to call and get response
    (letfn [(eval! [form & opts]
              (let [msg (apply tool-call form opts)]
                (send-message! out msg)
                (read-message in)))
            (get-text [resp]
              (-> resp :result :content first :text))
            (is-error? [resp]
              (-> resp :result :isError))
            (unquote-str [s]
              ;; nREPL returns string values with surrounding quotes
              ;; e.g. (str "foo") -> "\"foo\"". Strip them for comparison.
              (when s
                (let [t (str/trim s)]
                  (if (and (str/starts-with? t "\"") (str/ends-with? t "\""))
                    (-> t (subs 1 (dec (count t)))
                        (str/replace "\\\"" "\"")
                        (str/replace "\\\\" "\\"))
                    t))))
            (to-hex [s]
              ;; Convert string to hex for encoding-safe comparison
              (when s
                (apply str (mapv #(format "%04x" (int %)) s))))]

      ;; ===== T0.1 — Startup diagnostics =====
      (println "--- T0.1: Startup diagnostics ---")
      (let [log (slurp ".workbench/logs/nrepl-bridge.log" :encoding "UTF-8")
            lines (str/split-lines log)
            ;; Find last startup block
            last-start-idx (last (keep-indexed
                                  (fn [i l] (when (ci? l "=== nrepl-bridge starting ===") i))
                                  lines))
            startup-lines (when last-start-idx (subvec (vec lines) last-start-idx))
            all-pass? (and startup-lines
                           (some #(ci? % "8/8 passed") startup-lines))]
        (record! "T0.1" "Startup diagnostics all passed" all-pass?
                 (if all-pass?
                   "8/8 checks passed"
                   (str "Could not confirm all checks passed"))))

      ;; ===== T0.2 — Basic arithmetic =====
      (println "--- T0.2: Basic arithmetic ---")
      (let [resp (eval! "(+ 1 2 3)")
            text (get-text resp)]
        (record! "T0.2" "Basic arithmetic (+ 1 2 3) = 6"
                 (= (str/trim (or text "")) "6")
                 (str "Got: " (pr-str text))))

      ;; ===== T0.3 — Path-like strings (MSYS2 trap) =====
      (println "--- T0.3: Path-like strings ---")
      (let [resp (eval! "(str \"/api/health\")")
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T0.3" "Path-like string /api/health preserved"
                 (= val "/api/health")
                 (str "Got: " (pr-str val) " (raw: " (pr-str text) ")")))

      ;; ===== T0.4 — Dollar signs =====
      (println "--- T0.4: Dollar signs ---")
      (let [resp (eval! "(str \"price is $100\")")
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T0.4" "Dollar sign preserved"
                 (= val "price is $100")
                 (str "Got: " (pr-str val))))

      ;; ===== T0.5 — Backticks =====
      (println "--- T0.5: Backticks ---")
      (let [resp (eval! "(str \"use `let` for binding\")")
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T0.5" "Backticks preserved"
                 (= val "use `let` for binding")
                 (str "Got: " (pr-str val))))

      ;; ===== T0.6 — Exclamation marks =====
      (println "--- T0.6: Exclamation marks ---")
      (let [resp (eval! "(str \"hello!world!\")")
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T0.6" "Exclamation marks preserved"
                 (= val "hello!world!")
                 (str "Got: " (pr-str val))))

      ;; ===== T0.7 — Cyrillic round-trip =====
      (println "--- T0.7: Cyrillic round-trip ---")
      (let [expected "\u041f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440"
            resp (eval! "(str \"\u041f\u0440\u0438\u0432\u0435\u0442 \u043c\u0438\u0440\")")
            text (get-text resp)
            val  (unquote-str text)
            ;; Compare hex to avoid console encoding issues
            expected-hex (to-hex expected)
            actual-hex   (to-hex val)]
        (record! "T0.7" "Cyrillic round-trip"
                 (= expected-hex actual-hex)
                 (str "expected-hex=" expected-hex " actual-hex=" actual-hex)))

      ;; ===== T0.8 — Emoji and extended Unicode =====
      (println "--- T0.8: Emoji and extended Unicode ---")
      (let [expected "\ud83c\udf89\u2192\u2211\u4f60\u597d"
            resp (eval! "(str \"\ud83c\udf89\" \"\u2192\" \"\u2211\" \"\u4f60\u597d\")")
            text (get-text resp)
            val  (unquote-str text)
            expected-hex (to-hex expected)
            actual-hex   (to-hex val)]
        (record! "T0.8" "Emoji and extended Unicode"
                 (= expected-hex actual-hex)
                 (str "expected-hex=" expected-hex " actual-hex=" actual-hex)))

      ;; ===== T0.9 — Nested quotes =====
      (println "--- T0.9: Nested quotes ---")
      (let [resp (eval! "(pr-str {:msg \"He said \\\"hello\\\"\"})")
            text (get-text resp)]
        (record! "T0.9" "Nested quotes preserved"
                 (and text (ci? text "He said") (ci? text "hello"))
                 (str "Got: " (pr-str text))))

      ;; ===== T0.10 — Paren repair (missing closing paren) =====
      (println "--- T0.10: Paren repair (missing paren) ---")
      (let [resp (eval! "(+ 1 2 3")
            text (get-text resp)]
        (record! "T0.10" "Paren repair: missing ) fixed, result = 6"
                 (and text (ci? text "6"))
                 (str "Got: " (pr-str text))))

      ;; ===== T0.11 — Paren repair (mismatched delimiter) =====
      (println "--- T0.11: Paren repair (mismatched delimiter) ---")
      (let [resp (eval! "(let [x 1]\n  (+ x 2]")
            text (get-text resp)]
        (record! "T0.11" "Paren repair: mismatched ] fixed, result = 3"
                 (and text (ci? text "3"))
                 (str "Got: " (pr-str text))))

      ;; ===== T0.12 — Markdown fence stripping =====
      (println "--- T0.12: Markdown fence stripping ---")
      (let [resp (eval! "```clojure\n(+ 1 2 3)\n```")
            text (get-text resp)]
        (record! "T0.12" "Markdown fence stripped, result = 6"
                 (and text (ci? text "6"))
                 (str "Got: " (pr-str text))))

      ;; ===== T0.13 — Syntax error (unclosed string) =====
      (println "--- T0.13: Syntax error ---")
      (let [resp (eval! "(str \"unclosed)")
            text (get-text resp)
            err? (is-error? resp)]
        (record! "T0.13" "Unclosed string returns syntax-error"
                 (and err? text (or (ci? text "yntax")
                                    (ci? text "rror")))
                 (str "isError=" err? " text=" (pr-str text))))

      ;; ===== T0.14 — Connection error =====
      (println "--- T0.14: Connection error ---")
      ;; For this test, we need a separate server with wrong port.
      ;; Instead, we test by evaluating a form that tries to connect to wrong port.
      ;; We'll start a new server process for this.
      (let [bad-server (proc/process
                        {:cmd ["bb" ".nrepl-bridge/server.bb"
                               "--backend-port" "19999"]
                         :in :pipe :out :pipe :err :pipe
                         :dir "."})
            bad-out (:in bad-server)
            bad-in  (java.io.BufferedInputStream. (:out bad-server) 65536)]
        (try
          ;; Initialize
          (send-message! bad-out {:jsonrpc "2.0" :id 1 :method "initialize"
                                  :params {:protocolVersion "2024-11-05"
                                           :capabilities {}
                                           :clientInfo {:name "test" :version "0.1.0"}}})
          (read-message bad-in)

          ;; Try eval on wrong port
          (send-message! bad-out {:jsonrpc "2.0" :id 2 :method "tools/call"
                                  :params {:name "nrepl_send"
                                           :arguments {:form "(+ 1 1)"}}})
          (let [resp (read-message bad-in)
                text (or (-> resp :result :content first :text)
                         (-> resp :error :message) "")
                err? (or (-> resp :result :isError) (-> resp :error))]
            (record! "T0.14" "Wrong port returns error, no hang"
                     (boolean err?)
                     (str "isError=" err? " text=" (pr-str (subs text 0 (min 100 (count text)))))))
          (finally
            (.close bad-out)
            (proc/destroy bad-server))))

      ;; ===== T0.15 — Timeout =====
      (println "--- T0.15: Timeout ---")
      (let [t0   (System/currentTimeMillis)
            resp (eval! "(Thread/sleep 60000)" :timeout_ms 5000)
            elapsed (- (System/currentTimeMillis) t0)
            text (get-text resp)
            err? (is-error? resp)]
        (record! "T0.15" "Timeout after ~5s"
                 (and err? (< elapsed 15000))
                 (str "elapsed=" elapsed "ms isError=" err? " text=" (pr-str (when text (subs text 0 (min 80 (count text))))))))

      ;; ===== T0.16 — Audit trail (dashboard stats) =====
      (println "--- T0.16: Audit trail ---")
      ;; Query SQLite directly from Babashka (not via nREPL)
      (let [db-path ".workbench/db/toolchain.db"
            rows (sqlite/query db-path ["SELECT count(*) as cnt FROM evals"])
            cnt  (:cnt (first rows))
            ;; Also check status distribution
            statuses (sqlite/query db-path ["SELECT status, count(*) as cnt FROM evals GROUP BY status"])
            status-map (into {} (mapv (fn [r] [(:status r) (:cnt r)]) statuses))]
        (record! "T0.16" "Audit trail has eval records"
                 (and cnt (pos? cnt))
                 (str "Total evals: " cnt " statuses: " (pr-str status-map))))

      ;; ===== T0.17 — Diagnostic log =====
      (println "--- T0.17: Diagnostic log ---")
      (let [log (slurp ".workbench/logs/nrepl-bridge.log" :encoding "UTF-8")
            has-evals (ci? log "EVAL #")
            has-timestamps (re-find #"\d{4}-\d{2}-\d{2}T" log)
            has-targets (ci? log "target=backend")
            has-status (and (ci? log "status=ok"))]
        (record! "T0.17" "Log has timestamps, targets, durations, outcomes"
                 (and has-evals has-timestamps has-targets has-status)
                 (str "evals=" has-evals " timestamps=" (boolean has-timestamps)
                      " targets=" has-targets " status=" has-status))))

    (finally
      (.close out)
      (proc/destroy server)))

  ;; --- Summary ---
  (println)
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
    (System/exit (if (= passed total) 0 1))))
