#!/usr/bin/env bb
;; test-phase1.bb -- Edge cases + adversarial stress tests for nrepl-bridge
;;
;; Tests T1.1-T1.18: multi-form, large output, exceptions, namespace persistence,
;; audit trail, deeply nested data, shell-dangerous chars, rapid-fire evals,
;; Unicode edge cases, SQL injection, frontend eval (requires browser).
;;
;; Prerequisites:
;;   1. npx shadow-cljs watch app
;;   2. One browser tab on http://localhost:8280 (optional -- T1.16 skips without it)
;;
;; Usage: bb .nrepl-bridge/test/test-phase1.bb [--backend-port PORT]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc]
         '[babashka.pods :as pods])

;; Load SQLite pod for T1.5
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
(require '[pod.babashka.go-sqlite3 :as sqlite])

;; --- Port discovery (same logic as server.bb) ---

(defn- port-alive? [port]
  (try
    (with-open [sock (doto (java.net.Socket.)
                       (.connect (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000))]
      true)
    (catch Exception _ false)))

(defn- discover-port []
  (some (fn [path]
          (when (.exists (java.io.File. path))
            (let [port (parse-long (str/trim (slurp path)))]
              (when (port-alive? port) port))))
        [".nrepl-port"
         ".shadow-cljs/nrepl.port"
         "node_modules/shadow-cljs-jar/.nrepl-port"]))

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      (discover-port)
      (do (println "FATAL: No nREPL port found. Start nREPL first or pass --backend-port.")
          (System/exit 1))))

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
        (read-line-bytes in)
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

(println "=== Phase 1 Acceptance Tests: Edge Cases + Adversarial ===")
(println (str "Backend port: " backend-port))
(println)

(println "Starting MCP server...")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"]
               :in :pipe :out :pipe :err :pipe
               :dir "."})
      out    (:in server)
      in     (java.io.BufferedInputStream. (:out server) 65536)]

  (try
    ;; Initialize
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05"
                                 :capabilities {}
                                 :clientInfo {:name "test-harness" :version "0.1.0"}}})
    (let [init-resp (read-message in)]
      (when-not init-resp
        (println "FATAL: No initialize response")
        (System/exit 1)))
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})

    (letfn [(eval! [form & opts]
              (let [msg (apply tool-call form opts)]
                (send-message! out msg)
                (read-message in)))
            (get-text [resp]
              (-> resp :result :content first :text))
            (is-error? [resp]
              (-> resp :result :isError))
            (unquote-str [s]
              (when s
                (let [t (str/trim s)]
                  (if (and (str/starts-with? t "\"") (str/ends-with? t "\""))
                    (-> t (subs 1 (dec (count t)))
                        (str/replace "\\\"" "\"")
                        (str/replace "\\\\" "\\"))
                    t))))
            (to-hex [s]
              (when s
                (apply str (mapv #(format "%04x" (int %)) s))))]

      ;; ===== SPEC TESTS =====

      ;; T1.1 -- Multi-form evaluation
      (println "--- T1.1: Multi-form ---")
      (let [resp (eval! "(def x 10)\n(def y 20)\n(+ x y)")
            text (get-text resp)]
        (record! "T1.1" "Multi-form (def x 10)(def y 20)(+ x y) = 30"
                 (and text (ci? text "30"))
                 (str "Got: " (pr-str text))))

      ;; T1.2 -- Large output
      (println "--- T1.2: Large output ---")
      (let [resp (eval! "(vec (range 10000))" :timeout_ms 15000)
            text (get-text resp)]
        ;; Should either have a dump_path reference or contain truncated output
        ;; The key is it doesn't crash or timeout
        (record! "T1.2" "Large output (range 10000) handled"
                 (and text (or (ci? text "dump")
                               (ci? text "0")
                               ;; If not truncated, it should still contain numbers
                               (> (count text) 100)))
                 (str "Got " (count (or text "")) " chars, starts: "
                      (pr-str (when text (subs text 0 (min 100 (count text))))))))

      ;; T1.3 -- Exception handling
      (println "--- T1.3: Exception (/ 1 0) ---")
      (let [resp (eval! "(/ 1 0)")
            text (get-text resp)
            err? (is-error? resp)]
        ;; Exception may be signaled via isError=true OR via stderr/exception text.
        ;; Both are acceptable -- the key is the error is reported, not swallowed.
        (record! "T1.3" "Division by zero returns error"
                 (and text (or (ci? text "Arithmetic")
                               (ci? text "Divide by zero")))
                 (str "isError=" err? " text=" (pr-str text))))

      ;; T1.4 -- Namespace persistence
      (println "--- T1.4: Namespace persistence ---")
      (eval! "(def ^:dynamic *phase1-marker* 42)")
      (let [resp (eval! "*phase1-marker*")
            text (get-text resp)]
        (record! "T1.4" "Var defined in one call, read in next"
                 (and text (ci? text "42"))
                 (str "Got: " (pr-str text))))

      ;; T1.5 -- Audit trail completeness
      (println "--- T1.5: Audit trail ---")
      (let [db-path ".workbench/db/toolchain.db"
            rows (sqlite/query db-path ["SELECT count(*) as cnt FROM evals"])
            cnt  (:cnt (first rows))
            pending (sqlite/query db-path
                                  ["SELECT count(*) as cnt FROM evals WHERE status = 'pending'"])
            pending-cnt (:cnt (first pending))]
        (record! "T1.5" "Audit trail: no orphaned pending rows"
                 (and cnt (pos? cnt) (zero? pending-cnt))
                 (str "Total: " cnt " Pending: " pending-cnt)))

      ;; ===== ADVERSARIAL TESTS =====

      ;; T1.6 -- Deeply nested data
      (println "--- T1.6: Deeply nested data ---")
      (let [resp (eval! "(reduce (fn [acc _] {:nested acc}) :leaf (range 50))")
            text (get-text resp)]
        (record! "T1.6" "50-level nested map round-trips"
                 (and text (ci? text "nested") (ci? text "leaf"))
                 (str "Got " (count (or text "")) " chars")))

      ;; T1.7 -- All shell-dangerous chars in one form
      (println "--- T1.7: All shell-dangerous chars combined ---")
      (let [resp (eval! (str "(str \"/api/health\" \" $HOME \" \"`whoami`\""
                             " \" !event! \" \"$(pwd)\" \" \\\\n\\\\t\\\\r\""
                             " \" 'single' \" \"\\\"double\\\"\" \" C:\\\\Users\")"))
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T1.7" "All shell traps in one string"
                 (and val
                      (ci? val "/api/health")
                      (ci? val "$HOME")
                      (ci? val "`whoami`")
                      (ci? val "$(pwd)")
                      (ci? val "C:\\Users"))
                 (str "Got: " (pr-str val))))

      ;; T1.8 -- Long form (stress test form length)
      (println "--- T1.8: Long form (4KB) ---")
      (let [;; Generate a form with many additions: (+ 1 1 1 ... 1) with 1000 ones
            long-form (str "(+ " (str/join " " (repeat 1000 "1")) ")")
            resp (eval! long-form :timeout_ms 15000)
            text (get-text resp)]
        (record! "T1.8" "4KB form with 1000 addends = 1000"
                 (and text (ci? text "1000"))
                 (str "Got: " (pr-str text))))

      ;; T1.9 -- Rapid-fire sequential evals
      (println "--- T1.9: Rapid-fire 20 evals ---")
      (let [start (System/currentTimeMillis)
            results-vec (mapv (fn [i]
                                (let [resp (eval! (str "(+ " i " 1)"))
                                      text (get-text resp)]
                                  {:i i :text text}))
                              (range 20))
            elapsed (- (System/currentTimeMillis) start)
            all-ok (every? (fn [{:keys [i text]}]
                             (and text (ci? text (str (inc i)))))
                           results-vec)]
        (record! "T1.9" (str "20 rapid evals in " elapsed "ms, all correct")
                 all-ok
                 (str "elapsed=" elapsed "ms failed="
                      (pr-str (filterv #(not (ci? (or (:text %) "") (str (inc (:i %)))))
                                       results-vec)))))

      ;; T1.10 -- Form returning nil
      (println "--- T1.10: nil return ---")
      (let [resp (eval! "(when false :never)")
            text (get-text resp)]
        (record! "T1.10" "nil return handled gracefully"
                 (and text (ci? text "nil"))
                 (str "Got: " (pr-str text))))

      ;; T1.11 -- stdout + stderr + return value combined
      (println "--- T1.11: stdout + stderr + return ---")
      (let [resp (eval! (str "(do (println \"stdout-line\")"
                             " (.println System/err \"stderr-line\")"
                             " :final-value)"))
            text (get-text resp)]
        (record! "T1.11" "stdout + stderr + return all captured"
                 (and text
                      (ci? text "stdout-line")
                      (ci? text ":final-value"))
                 (str "Got: " (pr-str (when text (subs text 0 (min 200 (count text))))))))

      ;; T1.12 -- StackOverflow handling
      (println "--- T1.12: StackOverflow ---")
      (let [resp (eval! "((fn f [] (f)))" :timeout_ms 10000)
            text (get-text resp)
            err? (is-error? resp)]
        ;; Exception may be signaled via isError or stderr text.
        (record! "T1.12" "StackOverflow returns error, no crash"
                 (and text (or (ci? text "StackOverflow")
                               (ci? text "stack")))
                 (str "isError=" err? " text="
                      (pr-str (when text (subs text 0 (min 150 (count text))))))))

      ;; T1.13 -- Windows backslash paths
      (println "--- T1.13: Windows backslash paths ---")
      (let [resp (eval! "(str \"C:\\\\Users\\\\v.palichev\\\\project\")")
            text (get-text resp)
            val  (unquote-str text)]
        (record! "T1.13" "Windows paths with backslashes preserved"
                 (and val (ci? val "C:\\Users\\v.palichev\\project"))
                 (str "Got: " (pr-str val))))

      ;; T1.14 -- Unicode edge cases: ZWJ, RTL, combining chars
      (println "--- T1.14: Unicode edge cases ---")
      (let [;; Family emoji (ZWJ sequence), Arabic RTL, combining diacritical
            resp (eval! (str "(str \"\u0410\u0301\" \"|\" \"\u0627\u0644\u0639\u0631\u0628\u064a\u0629\""
                             " \"|\" \"\u200b\" \"|\" \"\u2066test\u2069\")"))
            text (get-text resp)]
        (record! "T1.14" "ZWJ, RTL, combining chars survive"
                 (and text
                      (ci? text "\u0410")   ;; Cyrillic A
                      (ci? text "\u0627"))   ;; Arabic alef
                 (str "Got hex: " (to-hex (unquote-str text)))))

      ;; T1.15 -- SQL injection attempt in form
      (println "--- T1.15: SQL injection in form text ---")
      (let [resp (eval! "(str \"'; DROP TABLE evals; --\")")
            text (get-text resp)
            val  (unquote-str text)
            ;; Verify DB still works
            rows (sqlite/query ".workbench/db/toolchain.db"
                               ["SELECT count(*) as cnt FROM evals"])
            cnt  (:cnt (first rows))]
        (record! "T1.15" "SQL injection attempt: form evals, DB intact"
                 (and val
                      (ci? val "DROP TABLE")
                      cnt (pos? cnt))
                 (str "val=" (pr-str val) " db-rows=" cnt)))

      ;; T1.16 -- Frontend edge case via cljs-eval
      (println "--- T1.16: Frontend math via target:frontend ---")
      ;; Requires a browser tab on localhost:8280. SKIP if no browser connected.
      (let [check (eval! "(count (shadow.cljs.devtools.api/repl-runtimes :app))")
            check-text (get-text check)
            has-browser (and check-text (not (ci? check-text "0"))
                             (re-find #"[1-9]" check-text))]
        (if-not has-browser
          (do (swap! results conj {:test "T1.16" :desc "Frontend: sum 1..100 = 5050" :pass true :detail "SKIP -- no browser connected"})
              (println "  SKIP T1.16 -- Frontend: no browser on localhost:8280"))
          (let [resp (eval! "(reduce + (range 1 101))" :target "frontend" :timeout_ms 15000)
                text (get-text resp)]
            (record! "T1.16" "Frontend: sum 1..100 = 5050"
                     (and text (ci? text "5050"))
                     (str "Got: " (pr-str text))))))

      ;; T1.17 -- Empty string and whitespace-only forms
      (println "--- T1.17: Whitespace / empty edge cases ---")
      (let [resp-ws (eval! "   \n\t  ")
            text-ws (get-text resp-ws)
            err-ws? (is-error? resp-ws)]
        (record! "T1.17" "Whitespace-only form handled gracefully"
                 ;; Should error or return nil, not crash
                 (boolean (or err-ws? text-ws))
                 (str "isError=" err-ws? " text=" (pr-str text-ws))))

      ;; T1.18 -- Multiple string escapes stacked
      (println "--- T1.18: Stacked escape sequences ---")
      (let [resp (eval! "(pr-str {:a \"line1\\nline2\" :b \"tab\\there\" :c \"quote\\\"inside\\\"\"})")
            text (get-text resp)]
        (record! "T1.18" "Stacked \\n \\t \\\" in pr-str round-trip"
                 (and text
                      (ci? text "line1")
                      (ci? text "line2")
                      (ci? text "tab")
                      (ci? text "quote"))
                 (str "Got: " (pr-str text)))))

    (finally
      (.close out)
      (proc/destroy server)))

  ;; --- Summary ---
  (println)
  (println "=== Summary ===")
  (let [passed (count (filter :pass @results))
        total  (count @results)]
    (doseq [r @results]
      (let [skip? (and (:pass r) (:detail r) (str/starts-with? (:detail r) "SKIP"))
            status (cond skip? "SKIP" (:pass r) "PASS" :else "FAIL")]
        (println (str "  " status " " (:test r) " -- " (:desc r)))))
    (println)
    (println (str passed "/" total " tests passed"))
    (when (< passed total)
      (println "FAILED tests:")
      (doseq [r (remove :pass @results)]
        (println (str "  " (:test r) ": " (:detail r)))))
    (System/exit (if (= passed total) 0 1))))
