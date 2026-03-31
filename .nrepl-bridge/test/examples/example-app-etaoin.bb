#!/usr/bin/env bb
;; test-phase3.bb -- Run Phase 3 acceptance tests (T3.1-T3.6) against the MCP server
;;
;; Prerequisites:
;;   1. npx shadow-cljs watch app  (nREPL on 17888, dev-http on 8280)
;;   2. Close any manually opened browser tabs on localhost:8280
;;      (etaoin opens its own Chrome instance)
;;   3. ChromeDriver installed and on PATH
;;
;; Usage: bb nrepl-bridge/test-phase3.bb [--backend-port 17888]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc])

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      17888))

;; --- JSON-RPC framing (same as phase0/phase1/phase2) ---

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

(println "=== Phase 3 Acceptance Tests ===")
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
              (-> resp :result :isError))]

      ;; ===== Pre-check: add etaoin dynamically =====
      (println "--- Pre-check: adding etaoin ---")
      (let [resp (eval! (str "(do (require '[clojure.repl.deps :as deps])"
                             " (deps/add-lib 'etaoin/etaoin {:mvn/version \"1.0.40\"})"
                             " :added)")
                        :timeout_ms 120000)
            text (get-text resp)]
        (if (and text (ci? text ":added"))
          (println "  OK etaoin loaded")
          (do
            (println (str "  FATAL: Cannot add etaoin: " text))
            (.close out)
            (proc/destroy server)
            (System/exit 1))))

      ;; Require etaoin
      (let [resp (eval! "(do (require '[etaoin.api :as e]) :ok)" :timeout_ms 30000)
            text (get-text resp)]
        (when-not (and text (ci? text ":ok"))
          (println (str "  FATAL: Cannot require etaoin: " text))
          (.close out)
          (proc/destroy server)
          (System/exit 1))
        (println "  OK etaoin.api required"))

      ;; ===== T3.1 -- Launch etaoin =====
      (println "--- T3.1: Launch Chrome via etaoin ---")
      (let [resp (eval! (str "(do"
                             " (def driver (e/chrome {:args [\"--disable-search-engine-choice-screen\"]}))"
                             " (e/go driver \"http://localhost:8280\")"
                             " (Thread/sleep 3000)"
                             " {:title (e/get-title driver)"
                             "  :url (e/get-url driver)})")
                        :timeout_ms 30000)
            text (get-text resp)]
        (record! "T3.1" "Launch Chrome, navigate to app"
                 (and text
                      (ci? text "Proof App")
                      (ci? text "8280"))
                 (str "Got: " (pr-str text))))

      ;; ===== T3.2 -- Read DOM via etaoin =====
      (println "--- T3.2: Read DOM via etaoin ---")
      (let [resp (eval! "(e/get-element-text driver {:css \"h1\"})")
            text (get-text resp)]
        (record! "T3.2" "Read h1 text via etaoin"
                 (and text (ci? text "Proof Counter"))
                 (str "Got: " (pr-str text))))

      ;; ===== T3.3 -- Click and verify =====
      (println "--- T3.3: Click increment and verify ---")
      ;; Reset first via cljs-eval, then click via etaoin
      (eval! (str "(shadow.cljs.devtools.api/cljs-eval :app"
                  " \"(re-frame.core/dispatch-sync [:reset])\" {})"))
      (Thread/sleep 500)
      ;; Click increment 3 times
      (eval! "(do (e/click driver {:id \"increment-btn\"}) (Thread/sleep 300))")
      (eval! "(do (e/click driver {:id \"increment-btn\"}) (Thread/sleep 300))")
      (eval! "(do (e/click driver {:id \"increment-btn\"}) (Thread/sleep 300))")
      (let [resp (eval! "(e/get-element-text driver {:id \"counter-value\"})")
            text (get-text resp)]
        (record! "T3.3" "Click increment 3x, counter shows 3"
                 (and text (ci? text "3"))
                 (str "Got: " (pr-str text))))

      ;; ===== T3.4 -- Backend-frontend state correlation =====
      (println "--- T3.4: Backend-frontend state correlation ---")
      ;; DOM says 3 (verified above). Now read app-db via cljs-eval and verify match.
      ;; Also hit backend /api/ping to prove both stacks are live.
      (let [;; Read app-db count
            resp-db (eval! (str "(shadow.cljs.devtools.api/cljs-eval :app"
                                " \"(:count @re-frame.db/app-db)\" {})"))
            text-db (get-text resp-db)
            ;; Hit backend API
            resp-api (eval! (str "(do (require '[proof.backend.core :as core])"
                                 "  (let [resp (core/handler"
                                 "              {:request-method :get"
                                 "               :uri \"/api/ping\""
                                 "               :headers {}})]"
                                 "    {:status (:status resp)}))")
                            :timeout_ms 10000)
            text-api (get-text resp-api)]
        (record! "T3.4" "app-db count=3, backend /api/ping 200"
                 (and text-db (ci? text-db "3")
                      text-api (ci? text-api "200"))
                 (str "app-db: " (pr-str text-db)
                      " api: " (pr-str text-api))))

      ;; ===== T3.5 -- Ultimate shell-bypass test =====
      (println "--- T3.5: Ultimate shell-bypass test ---")
      ;; POST /api/echo with body containing /api/health, Cyrillic, $100!
      (let [resp (eval! (str "(do (require '[proof.backend.core :as core]"
                             "              '[cheshire.core :as json])"
                             " (let [body-str (json/generate-string"
                             "                 {:path \"/api/health\""
                             "                  :greeting \"\u041f\u0440\u0438\u0432\u0435\u0442\""
                             "                  :price \"$100!\"})"
                             "       req {:request-method :post"
                             "            :uri \"/api/echo\""
                             "            :headers {\"content-type\" \"application/json\"}"
                             "            :body (java.io.ByteArrayInputStream."
                             "                    (.getBytes body-str \"UTF-8\"))}"
                             "       resp (core/handler req)"
                             "       parsed (json/parse-string (:body resp) true)]"
                             "   {:path (:path parsed)"
                             "    :greeting (:greeting parsed)"
                             "    :price (:price parsed)}))"))
            text (get-text resp)]
        (record! "T3.5" "Shell-bypass: /api/health + Cyrillic + $100! intact"
                 (and text
                      (ci? text "/api/health")
                      (ci? text "\u041f\u0440\u0438\u0432\u0435\u0442")
                      (ci? text "$100!"))
                 (str "Got: " (pr-str text))))

      ;; ===== T3.6 -- Clean up =====
      (println "--- T3.6: Clean up ---")
      (let [resp (eval! "(do (e/quit driver) :closed)" :timeout_ms 10000)
            text (get-text resp)]
        (record! "T3.6" "Browser closed via etaoin"
                 (and text (ci? text ":closed"))
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
      (println (str "  " (if (:pass r) "PASS" "FAIL") " " (:test r) " -- " (:desc r))))
    (println)
    (println (str passed "/" total " tests passed"))
    (when (< passed total)
      (println "FAILED tests:")
      (doseq [r (remove :pass @results)]
        (println (str "  " (:test r) ": " (:detail r)))))
    (System/exit (if (= passed total) 0 1))))
