#!/usr/bin/env bb
;; test-phase2.bb -- Run Phase 2 acceptance tests (T2.0-T2.8) against the MCP server
;;
;; Prerequisites:
;;   1. npm install  (installs shadow-cljs, react, react-dom)
;;   2. npx shadow-cljs watch app  (starts nREPL on 17888 + CLJS compilation)
;;   3. Open http://localhost:8280 in ONE browser tab
;;   4. Wait for shadow-cljs to finish compiling (watch console for "Build completed")
;;
;; This replaces `clj -M:nrepl` -- shadow-cljs provides the nREPL.
;;
;; Usage: bb nrepl-bridge/test-phase2.bb [--backend-port 17888]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc])

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      17888))

;; --- JSON-RPC framing (same as phase0/phase1) ---

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

(println "=== Phase 2 Acceptance Tests ===")
(println (str "Backend port: " backend-port))
(println)

;; Start the MCP server
(println "Starting MCP server...")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"]
               :in :pipe :out :pipe :err :pipe
               :dir "."})
      out    (:in server)  ;; our output -> server's stdin
      in     (java.io.BufferedInputStream. (:out server) 65536)]

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
            (cljs-eval-form [cljs-form]
              ;; Wrap a CLJS form for evaluation via shadow-cljs cljs-eval
              ;; through the backend nREPL
              (str "(shadow.cljs.devtools.api/cljs-eval :app "
                   (pr-str cljs-form)
                   " {})"))]

      ;; ===== T2.0 -- Pre-check: browser connected =====
      (println "--- T2.0: Pre-check (browser connected) ---")
      (let [resp (eval! "(count (shadow.cljs.devtools.api/repl-runtimes :app))"
                        :timeout_ms 15000)
            text (get-text resp)]
        (if (and text (ci? text "1"))
          (record! "T2.0" "Exactly 1 browser connected" true text)
          (do
            (record! "T2.0" "Exactly 1 browser connected" false
                     (str "Expected 1, got: " (pr-str text)
                          ". Open http://localhost:8280 in ONE tab."))
            (println)
            (println "FATAL: Cannot proceed without exactly 1 browser connected.")
            (println "  Open http://localhost:8280 in a browser, then re-run.")
            (.close out)
            (proc/destroy server)
            (System/exit 1))))

      ;; ===== T2.1 -- Read page title =====
      (println "--- T2.1: Read page title ---")
      (let [resp (eval! (cljs-eval-form "(.-title js/document)")
                        :timeout_ms 15000)
            text (get-text resp)]
        (record! "T2.1" "Read page title via cljs-eval"
                 (and text (ci? text "Proof App"))
                 (str "Got: " (pr-str text))))

      ;; ===== T2.2 -- Read app-db =====
      (println "--- T2.2: Read app-db ---")
      (let [resp (eval! (cljs-eval-form "@re-frame.db/app-db")
                        :timeout_ms 15000)
            text (get-text resp)]
        (record! "T2.2" "Read app-db returns initial state"
                 (and text (ci? text "count"))
                 (str "Got: " (pr-str text))))

      ;; ===== T2.3 -- Dispatch event =====
      (println "--- T2.3: Dispatch [:increment] ---")
      ;; Reset first, then increment
      (eval! (cljs-eval-form "(re-frame.core/dispatch-sync [:reset])"))
      (let [resp-before (eval! (cljs-eval-form "(:count @re-frame.db/app-db)"))
            text-before (get-text resp-before)]
        ;; Increment
        (eval! (cljs-eval-form "(re-frame.core/dispatch-sync [:increment])"))
        (let [resp-after (eval! (cljs-eval-form "(:count @re-frame.db/app-db)"))
              text-after (get-text resp-after)]
          (record! "T2.3" "Dispatch [:increment] changes count 0 -> 1"
                   (and text-before (ci? text-before "0")
                        text-after (ci? text-after "1"))
                   (str "Before: " (pr-str text-before)
                        " After: " (pr-str text-after)))))

      ;; ===== T2.4 -- Edit source + hot reload =====
      (println "--- T2.4: Hot reload ---")
      ;; Read current heading
      (let [resp-before (eval! (cljs-eval-form
                                "(.-innerText (.querySelector js/document \"h1\"))"))
            text-before (get-text resp-before)
            views-path "src/proof/frontend/views.cljs"
            original (slurp views-path :encoding "UTF-8")]
        ;; Modify heading text
        (spit views-path
              (str/replace original "Proof Counter" "Proof Counter v2")
              :encoding "UTF-8")
        ;; Wait for shadow-cljs recompile + hot reload
        (Thread/sleep 8000)
        ;; Read heading again
        (let [resp-after (eval! (cljs-eval-form
                                 "(.-innerText (.querySelector js/document \"h1\"))"))
              text-after (get-text resp-after)]
          ;; Restore original
          (spit views-path original :encoding "UTF-8")
          (Thread/sleep 5000)
          (record! "T2.4" "Hot reload updates DOM after source edit"
                   (and text-before (ci? text-before "Proof Counter")
                        (not (ci? (or text-before "") "v2"))
                        text-after (ci? text-after "Proof Counter v2"))
                   (str "Before: " (pr-str text-before)
                        " After: " (pr-str text-after)))))

      ;; ===== T2.5 -- DOM element read =====
      (println "--- T2.5: DOM element read ---")
      (let [resp (eval! (cljs-eval-form
                         "(.-innerText (.querySelector js/document \"h1\"))"))
            text (get-text resp)]
        (record! "T2.5" "Read h1 innerText via cljs-eval"
                 (and text (ci? text "Proof Counter"))
                 (str "Got: " (pr-str text))))

      ;; ===== T2.6 -- Direct frontend target =====
      (println "--- T2.6: Direct frontend target ---")
      (let [resp (eval! "(+ 40 2)" :target "frontend" :timeout_ms 15000)
            text (get-text resp)]
        (record! "T2.6" "target: frontend returns 42"
                 (and text (ci? text "42"))
                 (str "Got: " (pr-str text))))

      ;; ===== T2.7 -- Cyrillic through frontend =====
      (println "--- T2.7: Cyrillic through frontend ---")
      (let [expected "\u041f\u0440\u0438\u0432\u0435\u0442"
            resp (eval! (cljs-eval-form "(str \"\u041f\u0440\u0438\u0432\u0435\u0442\")")
                        :timeout_ms 15000)
            text (get-text resp)
            ;; Compare hex to avoid console encoding issues
            to-hex (fn [s] (when s (apply str (mapv #(format "%04x" (int %)) s))))
            expected-hex (to-hex expected)]
        ;; The result from cljs-eval is wrapped in a map like {:results [...]}
        ;; Check that the Cyrillic text appears somewhere in the response
        (record! "T2.7" "Cyrillic round-trips through frontend"
                 (and text (ci? text "\u041f\u0440\u0438\u0432\u0435\u0442"))
                 (str "Got: " (pr-str (when text (subs text 0 (min 200 (count text))))))))

      ;; ===== T2.8 -- Console interceptor =====
      (println "--- T2.8: Console interceptor ---")
      ;; Log a unique message, then retrieve captured messages
      (eval! (cljs-eval-form "(js/console.log \"phase2-test-marker-42\")"))
      (Thread/sleep 500)
      (let [resp (eval! (cljs-eval-form "(proof.frontend.console/get-messages)"))
            text (get-text resp)]
        (record! "T2.8" "Console interceptor captures messages"
                 (and text (ci? text "phase2-test-marker-42"))
                 (str "Got: " (pr-str (when text (subs text 0 (min 300 (count text)))))))))

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
