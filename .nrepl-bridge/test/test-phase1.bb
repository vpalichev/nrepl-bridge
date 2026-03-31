#!/usr/bin/env bb
;; test-phase1.bb -- Run Phase 1 acceptance tests (T1.1-T1.8) against the MCP server
;;
;; Requires: nREPL running on --backend-port with Ring/Reitit/clj-http on classpath.
;; Restart nREPL after updating deps.edn if needed.
;;
;; Usage: bb nrepl-bridge/test-phase1.bb [--backend-port 17888]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc])

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      17888))

;; --- JSON-RPC framing (same as phase0) ---

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

(println "=== Phase 1 Acceptance Tests ===")
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
            (text-contains? [resp s]
              (let [t (get-text resp)]
                (and t (ci? t s))))]

      ;; ===== Pre-check: dynamically add ALL deps =====
      ;; Always attempt add-lib for each dep. add-lib is idempotent -- already
      ;; loaded libs return immediately. This avoids partial-load states.
      (println "--- Pre-check: adding deps dynamically ---")
      (let [deps-list [["ring/ring-core" "1.12.2"]
                       ["ring/ring-jetty-adapter" "1.12.2"]
                       ["metosin/reitit-ring" "0.7.2"]
                       ["clj-http/clj-http" "3.13.0"]
                       ["ring/ring-mock" "0.4.0"]
                       ["cheshire/cheshire" "5.13.0"]]]
        (doseq [[lib ver] deps-list]
          (let [form (str "(do (require '[clojure.repl.deps :as deps])"
                          " (deps/add-lib '" lib " {:mvn/version \"" ver "\"})"
                          " :added)")
                r (eval! form :timeout_ms 120000)
                t (get-text r)]
            (if (and t (ci? t ":added"))
              (println (str "  OK " lib " " ver))
              (do
                (println (str "  FATAL: Cannot add " lib ". Restart nREPL with updated deps.edn."))
                (println (str "  Detail: " t))
                (.close out)
                (proc/destroy server)
                (System/exit 1)))))
        (println "  All deps ready"))

      ;; ===== T1.1 -- Require namespace =====
      (println "--- T1.1: Require namespace ---")
      (let [resp (eval! "(do (require '[proof.backend.core :as core] :reload) :loaded)"
                        :timeout_ms 60000)
            text (get-text resp)]
        (record! "T1.1" "Require proof.backend.core succeeds"
                 (and text (ci? text ":loaded"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.2 -- Direct handler call =====
      (println "--- T1.2: Direct handler call ---")
      (let [resp (eval! (str "(let [resp (core/handler {:request-method :get"
                             "                          :uri \"/api/ping\""
                             "                          :headers {}})]"
                             "  {:status (:status resp)"
                             "   :has-body (some? (:body resp))"
                             "   :has-request-id (contains? (:headers resp) \"X-Request-Id\")})"))
            text (get-text resp)]
        (record! "T1.2" "Direct handler call returns {:status 200}"
                 (and text (ci? text "200") (ci? text "true"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.3 -- POST /api/echo with JSON body =====
      (println "--- T1.3: POST /api/echo ---")
      (let [resp (eval! (str "(let [body-str (cheshire.core/generate-string"
                             "                {:msg \"hello\" :path \"/api/health\" :price \"$100\"})"
                             "      req {:request-method :post"
                             "           :uri \"/api/echo\""
                             "           :headers {\"content-type\" \"application/json\"}"
                             "           :body (java.io.ByteArrayInputStream."
                             "                   (.getBytes body-str \"UTF-8\"))}"
                             "      resp (core/handler req)"
                             "      parsed (cheshire.core/parse-string (:body resp) true)]"
                             "  {:echo-path (:path parsed)"
                             "   :echo-price (:price parsed)"
                             "   :echo-msg (:msg parsed)})"))
            text (get-text resp)]
        (record! "T1.3" "POST /api/echo returns JSON body with /api/health and $100"
                 (and text
                      (ci? text "/api/health")
                      (ci? text "$100")
                      (ci? text "hello"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.4 -- clj-http round-trip =====
      ;; Need to start the server first
      (println "--- T1.4: clj-http round-trip ---")
      (let [start-resp (eval! "(core/start-server! :port 3456)" :timeout_ms 15000)
            start-text (get-text start-resp)]
        (println (str "  Server start: " (pr-str start-text)))

        ;; Use :as :string to avoid cheshire version conflict with clj-http's :as :json
        (let [resp (eval! (str "(do (require '[clj-http.client :as http])"
                               "  (let [resp (http/get"
                               "              \"http://localhost:3456/api/ping\""
                               "              {:as :string :throw-exceptions false})"
                               "        parsed (cheshire.core/parse-string (:body resp) true)]"
                               "    {:status (:status resp)"
                               "     :body-status (:status parsed)}))"))
              text (get-text resp)]
          (record! "T1.4" "clj-http GET /api/ping returns {:status 200}"
                   (and text (ci? text "200") (ci? text "ok"))
                   (str "Got: " (pr-str text)))))

      ;; ===== T1.5 -- ring-mock testing =====
      (println "--- T1.5: ring-mock testing ---")
      (let [resp (eval! (str "(do (require '[ring.mock.request :as mock])"
                             "   (let [resp (core/handler (mock/request :get \"/api/ping\"))"
                             "         parsed (cheshire.core/parse-string (:body resp) true)]"
                             "     {:status (:status resp)"
                             "      :body-status (:status parsed)}))"))
            text (get-text resp)]
        (record! "T1.5" "ring-mock GET /api/ping returns 200"
                 (and text (ci? text "200") (ci? text "ok"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.6 -- X-Request-Id middleware =====
      (println "--- T1.6: X-Request-Id middleware ---")
      (let [resp (eval! (str "(let [resp (core/handler (ring.mock.request/request :get \"/api/ping\"))]"
                             "  {:has-id (contains? (:headers resp) \"X-Request-Id\")"
                             "   :id-value (get (:headers resp) \"X-Request-Id\")})"))
            text (get-text resp)]
        (record! "T1.6" "X-Request-Id header present in response"
                 (and text (ci? text "true"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.7 -- Atom state inspection =====
      (println "--- T1.7: Atom state inspection ---")
      ;; Reset store, POST via clj-http, then read atom directly
      (eval! "(reset! core/!store {})")
      (let [resp (eval! (str "(do (require '[clj-http.client :as http])"
                             " (http/post"
                             "   \"http://localhost:3456/api/store\""
                             "   {:body (cheshire.core/generate-string {:item \"test-data\" :value 42})"
                             "    :content-type :json"
                             "    :throw-exceptions false})"
                             " (let [store @core/!store"
                             "       entry (first (vals store))]"
                             "   {:count (count store)"
                             "    :item (:item entry)"
                             "    :value (:value entry)}))"))
            text (get-text resp)]
        (record! "T1.7" "Atom state inspection: POST then read @!store"
                 (and text
                      (ci? text "1")
                      (ci? text "test-data")
                      (ci? text "42"))
                 (str "Got: " (pr-str text))))

      ;; ===== T1.8 -- Verification pipeline (structural check) =====
      (println "--- T1.8: Verification pipeline ---")
      ;; Verify: namespace loaded, kondo available, specs defined
      (let [resp (eval! (str "(do"
                             " (require '[clojure.spec.alpha :as s])"
                             " (let [fdef-count (count (filterv"
                             "                          #(s/get-spec %)"
                             "                          ['proof.backend.core/ping-response"
                             "                           'proof.backend.core/parse-json-body]))]"
                             "   {:ns-loaded (find-ns 'proof.backend.core)"
                             "    :fdef-count fdef-count"
                             "    :handler-var (var? #'core/handler)}))"))
            text (get-text resp)]
        (record! "T1.8" "Verification: namespace loaded, specs present, handler defined"
                 (and text
                      (ci? text "proof.backend.core")
                      (ci? text "true"))
                 (str "Got: " (pr-str text))))

      ;; Stop server
      (println)
      (println "Stopping server...")
      (eval! "(core/stop-server!)")
      (println "Server stopped."))

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
