#!/usr/bin/env bb
;; test-dashboard.bb -- Test the web dashboard HTTP server and API endpoints
;;
;; Spawns the MCP server (which starts the dashboard on port 9500),
;; runs a few evals to populate data, then tests dashboard endpoints.
;;
;; Prerequisites: nREPL running
;;
;; Usage: bb .nrepl-bridge/test/test-dashboard.bb [--backend-port PORT]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc]
         '[babashka.http-client :as http])

;; --- Port discovery (same logic as server.bb) ---

(defn- discover-port []
  (some (fn [path]
          (when (.exists (java.io.File. path))
            (parse-long (str/trim (slurp path)))))
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

(def dashboard-port 9500)
(def base-url (str "http://localhost:" dashboard-port))

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

(defn ci? [s substr]
  (and (some? s) (some? substr)
       (str/includes? (str/lower-case (str s)) (str/lower-case (str substr)))))

(defn record! [test-id description pass? detail]
  (swap! results conj {:test test-id :desc description :pass pass? :detail detail})
  (let [status (if pass? "PASS" "FAIL")]
    (println (str "  " status " " test-id " -- " description
                  (when-not pass? (str "\n         " detail))))))

;; --- Main ---

(println "=== Dashboard Tests ===")
(println (str "Backend port: " backend-port))
(println (str "Dashboard port: " dashboard-port))
(println)

(println "Starting MCP server...")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str dashboard-port)]
               :in :pipe :out :pipe :err :pipe
               :dir "."})
      out    (:in server)
      in     (java.io.BufferedInputStream. (:out server) 65536)]

  (try
    ;; Initialize MCP
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05"
                                 :capabilities {}
                                 :clientInfo {:name "test-harness" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})

    ;; Wait for dashboard to start
    (Thread/sleep 2000)

    ;; Run a few evals to populate data
    (println "--- Populating eval data ---")
    (letfn [(eval! [form & opts]
              (let [msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
                         :params {:name "nrepl_send"
                                  :arguments (merge {:form form} (apply hash-map opts))}}]
                (send-message! out msg)
                (read-message in)))]
      ;; Success eval
      (eval! "(+ 1 2 3)")
      ;; Another success
      (eval! "(str \"hello world\")")
      ;; Error eval
      (eval! "(/ 1 0)")
      (println "  3 evals sent"))

    ;; Give the server a moment to process
    (Thread/sleep 500)

    ;; ===== D1 -- Dashboard HTML page =====
    (println "--- D1: Dashboard HTML page ---")
    (let [resp (http/get base-url)]
      (record! "D1" "GET / returns HTML with dashboard title"
               (and (= 200 (:status resp))
                    (ci? (:body resp) "nREPL Bridge Dashboard")
                    (ci? (:body resp) "<table"))
               (str "status=" (:status resp)
                    " has-title=" (ci? (:body resp) "nREPL Bridge Dashboard"))))

    ;; ===== D2 -- Stats cards in HTML =====
    (println "--- D2: Stats cards ---")
    (let [resp (http/get base-url)]
      (record! "D2" "Dashboard HTML contains stats cards"
               (and (ci? (:body resp) "stat-card")
                    (ci? (:body resp) "Total"))
               (str "has-stat-card=" (ci? (:body resp) "stat-card"))))

    ;; ===== D3 -- API: evals list =====
    (println "--- D3: API evals list ---")
    (let [resp (http/get (str base-url "/api/evals"))
          body (json/parse-string (:body resp) true)]
      (record! "D3" "GET /api/evals returns JSON array"
               (and (= 200 (:status resp))
                    (sequential? body)
                    (pos? (count body)))
               (str "status=" (:status resp) " count=" (count body))))

    ;; ===== D4 -- API: evals filtered by status =====
    (println "--- D4: API evals filtered ---")
    (let [resp (http/get (str base-url "/api/evals?status=ok"))
          body (json/parse-string (:body resp) true)]
      (record! "D4" "GET /api/evals?status=ok returns only ok evals"
               (and (= 200 (:status resp))
                    (every? #(= "ok" (:status %)) body))
               (str "statuses=" (mapv :status body))))

    ;; ===== D5 -- API: single eval =====
    (println "--- D5: API single eval ---")
    (let [list-resp (http/get (str base-url "/api/evals?limit=1"))
          evals (json/parse-string (:body list-resp) true)
          eval-id (:id (first evals))]
      (if eval-id
        (let [resp (http/get (str base-url "/api/evals/" eval-id))
              body (json/parse-string (:body resp) true)]
          (record! "D5" "GET /api/evals/:id returns single eval"
                   (and (= 200 (:status resp))
                        (= eval-id (:id body))
                        (some? (:form body)))
                   (str "id=" (:id body) " form=" (subs (or (:form body) "") 0 (min 30 (count (or (:form body) "")))))))
        (record! "D5" "GET /api/evals/:id returns single eval" false "No evals to test")))

    ;; ===== D6 -- API: stats =====
    (println "--- D6: API stats ---")
    (let [resp (http/get (str base-url "/api/stats"))
          body (json/parse-string (:body resp) true)]
      (record! "D6" "GET /api/stats returns stats + duration"
               (and (= 200 (:status resp))
                    (some? (:stats body))
                    (some? (:duration body))
                    (pos? (:total (:stats body))))
               (str "total=" (:total (:stats body))
                    " duration=" (:duration body))))

    ;; ===== D7 -- API: logs =====
    (println "--- D7: API logs ---")
    (let [resp (http/get (str base-url "/api/logs"))
          body (json/parse-string (:body resp) true)]
      (record! "D7" "GET /api/logs returns log lines"
               (and (= 200 (:status resp))
                    (vector? (:lines body))
                    (pos? (count (:lines body))))
               (str "lines=" (count (:lines body)))))

    ;; ===== D8 -- API: pending endpoint works =====
    (println "--- D8: API pending ---")
    (let [resp (http/get (str base-url "/api/pending"))
          body (json/parse-string (:body resp) true)]
      (record! "D8" "GET /api/pending returns valid response"
               (and (= 200 (:status resp))
                    (sequential? body))
               (str "status=" (:status resp) " count=" (count body))))

    ;; ===== D9 -- Status filter in HTML =====
    (println "--- D9: Status filter ---")
    (let [resp (http/get (str base-url "/?status=error"))]
      (record! "D9" "GET /?status=error renders filtered page"
               (and (= 200 (:status resp))
                    (ci? (:body resp) "nREPL Bridge Dashboard"))
               (str "status=" (:status resp))))

    ;; ===== D10 -- 404 for unknown route =====
    (println "--- D10: 404 handling ---")
    (let [resp (http/get (str base-url "/nonexistent") {:throw false})]
      (record! "D10" "GET /nonexistent returns 404"
               (= 404 (:status resp))
               (str "status=" (:status resp))))

    ;; ===== D11 -- API: evals with pagination =====
    (println "--- D11: API pagination ---")
    (let [resp1 (http/get (str base-url "/api/evals?limit=2&offset=0"))
          resp2 (http/get (str base-url "/api/evals?limit=2&offset=2"))
          body1 (json/parse-string (:body resp1) true)
          body2 (json/parse-string (:body resp2) true)
          ids1  (set (mapv :id body1))
          ids2  (set (mapv :id body2))]
      (record! "D11" "Pagination returns non-overlapping results"
               (and (<= (count body1) 2)
                    (empty? (clojure.set/intersection ids1 ids2)))
               (str "page1=" (mapv :id body1) " page2=" (mapv :id body2))))

    ;; ===== D12 -- Eval data integrity in API =====
    (println "--- D12: Eval data integrity ---")
    (let [resp (http/get (str base-url "/api/evals?limit=5"))
          body (json/parse-string (:body resp) true)]
      (record! "D12" "All evals have required fields"
               (every? (fn [e]
                         (and (some? (:id e))
                              (some? (:form e))
                              (some? (:status e))
                              (some? (:target e))
                              (some? (:created_at e))))
                       body)
               (str "checked " (count body) " evals")))

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
