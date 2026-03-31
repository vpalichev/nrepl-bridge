#!/usr/bin/env bb
;; test-gate.bb -- Test the approval gate feature
;;
;; Tests:
;; G1: Gate classifier identifies safe vs unsafe forms
;; G2: Safe forms pass through with --gate enabled
;; G3: Unsafe form is gated, approved via dashboard API, then executes
;; G4: Unsafe form is gated, rejected via dashboard API, returns error
;; G5: Unsafe form is gated, times out (short timeout)
;; G6: Gate off by default (forms pass without gating)
;;
;; Prerequisites: nREPL running on port 17888
;;
;; Usage: bb nrepl-bridge/test-gate.bb [--backend-port 17888]

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc]
         '[babashka.http-client :as http]
         '[babashka.classpath :as cp]
         '[babashka.pods :as pods])

;; Load gate classifier for direct testing
(let [script-dir (str (.getParent (java.io.File. (System/getProperty "babashka.file" "."))))]
  (cp/add-classpath (str script-dir "/src")))

(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[nrepl-bridge.gate :as gate])

;; --- Config ---

(def backend-port
  (or (some-> (second (drop-while #(not= % "--backend-port") *command-line-args*))
              parse-long)
      17888))

(def dashboard-port 9501) ;; Use different port to avoid conflicts

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

;; --- Tests ---

(println "=== Approval Gate Tests ===")
(println (str "Backend port: " backend-port))
(println)

;; ===== G1 -- Classifier unit tests =====
(println "--- G1: Gate classifier ---")
(let [safe-forms ["(+ 1 2)"
                  "(str \"hello\")"
                  "(require '[clojure.string :as str])"
                  "(println \"test\")"
                  "(re-frame.core/dispatch-sync [:increment])"
                  "(def x 42)"
                  "(let [x 1] (+ x 2))"]
      unsafe-forms ["(swap! state inc)"
                    "(reset! counter 0)"
                    "(spit \"file.txt\" \"data\")"
                    "(System/exit 1)"
                    "(alter-var-root #'x (constantly 5))"
                    "(require 'foo :reload-all)"]
      safe-results (mapv #(gate/classify-form %) safe-forms)
      unsafe-results (mapv #(gate/classify-form %) unsafe-forms)]
  (record! "G1a" "Safe forms classified as :auto"
           (every? #(= :auto %) safe-results)
           (str "results=" (mapv vector safe-forms safe-results)))
  (record! "G1b" "Unsafe forms classified as :gated"
           (every? #(= :gated %) unsafe-results)
           (str "results=" (mapv vector unsafe-forms unsafe-results))))

;; ===== G2 -- Safe forms pass through with gate enabled =====
(println "--- G2: Safe forms with gate on ---")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str dashboard-port)
                     "--gate"]
               :in :pipe :out :pipe :err :pipe :dir "."})
      out (:in server)
      in  (java.io.BufferedInputStream. (:out server) 65536)]
  (try
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05" :capabilities {}
                                 :clientInfo {:name "test" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})
    (Thread/sleep 1500)

    (let [msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
               :params {:name "nrepl_send" :arguments {:form "(+ 100 200)"}}}]
      (send-message! out msg)
      (let [resp (read-message in)
            text (-> resp :result :content first :text)]
        (record! "G2" "Safe form executes immediately with gate on"
                 (and text (ci? text "300"))
                 (str "Got: " (pr-str text)))))
    (finally
      (.close out)
      (proc/destroy server))))

;; ===== G3 -- Unsafe form gated, approved, then executes =====
(println "--- G3: Gated form -> approve -> execute ---")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str dashboard-port)
                     "--gate"
                     "--gate-timeout-ms" "15000"]
               :in :pipe :out :pipe :err :pipe :dir "."})
      out (:in server)
      in  (java.io.BufferedInputStream. (:out server) 65536)]
  (try
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05" :capabilities {}
                                 :clientInfo {:name "test" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})
    (Thread/sleep 1500)

    ;; Snapshot pending count before sending gated form
    (let [pre-pending (json/parse-string
                       (:body (http/get (str "http://localhost:" dashboard-port "/api/pending")))
                       true)
          pre-count (count pre-pending)]
      ;; Background thread: wait for a NEW pending eval, then approve it
      (future
        (try
          (loop [attempts 0]
            (when (< attempts 20)
              (Thread/sleep 500)
              (let [pending-resp (http/get (str "http://localhost:" dashboard-port "/api/pending"))
                    pending (json/parse-string (:body pending-resp) true)]
                ;; Only act when we see MORE pending evals than before
                (if (> (count pending) pre-count)
                  (let [newest (last pending)] ;; pending sorted by id ASC, last = newest
                    (http/post (str "http://localhost:" dashboard-port "/api/evals/" (:id newest) "/decide")
                               {:body (json/generate-string {:decision "approved" :feedback "Test approval"})
                                :headers {"Content-Type" "application/json"}}))
                  (recur (inc attempts))))))
          (catch Exception _e nil))))

    ;; Main thread: send gated form and block on response
    (let [msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
               :params {:name "nrepl_send"
                        :arguments {:form "(do (swap! (atom 0) inc))"}}}]
      (send-message! out msg)
      (let [resp (read-message in)
            text (-> resp :result :content first :text)]
        (record! "G3" "Gated form approved and executed"
                 (and text (ci? text "1"))
                 (str "Got: " (pr-str text)))))
    (finally
      (.close out)
      (proc/destroy server))))

;; ===== G4 -- Unsafe form gated, rejected =====
(println "--- G4: Gated form -> reject ---")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str (inc dashboard-port))
                     "--gate"
                     "--gate-timeout-ms" "15000"]
               :in :pipe :out :pipe :err :pipe :dir "."})
      out (:in server)
      in  (java.io.BufferedInputStream. (:out server) 65536)]
  (try
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05" :capabilities {}
                                 :clientInfo {:name "test" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})
    (Thread/sleep 1500)

    ;; Snapshot pending count before sending gated form
    (let [pre-pending (json/parse-string
                       (:body (http/get (str "http://localhost:" (inc dashboard-port) "/api/pending")))
                       true)
          pre-count (count pre-pending)]
      ;; Background thread: wait for a NEW pending eval, then reject it
      (future
        (try
          (loop [attempts 0]
            (when (< attempts 20)
              (Thread/sleep 500)
              (let [pending-resp (http/get (str "http://localhost:" (inc dashboard-port) "/api/pending"))
                    pending (json/parse-string (:body pending-resp) true)]
                (if (> (count pending) pre-count)
                  (let [newest (last pending)]
                    (http/post (str "http://localhost:" (inc dashboard-port) "/api/evals/" (:id newest) "/decide")
                               {:body (json/generate-string {:decision "rejected" :feedback "Too dangerous"})
                                :headers {"Content-Type" "application/json"}}))
                  (recur (inc attempts))))))
          (catch Exception _e nil))))

    ;; Main thread: send gated form and block on response
    (let [msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
               :params {:name "nrepl_send"
                        :arguments {:form "(reset! (atom nil) :bad)"}}}]
      (send-message! out msg)
      (let [resp (read-message in)
            text (-> resp :result :content first :text)
            err? (-> resp :result :isError)]
        (record! "G4" "Gated form rejected returns error"
                 (and err? text (ci? text "rejected"))
                 (str "isError=" err? " text=" (pr-str text)))))
    (finally
      (.close out)
      (proc/destroy server))))

;; ===== G5 -- Approval timeout =====
(println "--- G5: Approval timeout ---")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str (+ dashboard-port 2))
                     "--gate"
                     "--gate-timeout-ms" "3000"]
               :in :pipe :out :pipe :err :pipe :dir "."})
      out (:in server)
      in  (java.io.BufferedInputStream. (:out server) 65536)]
  (try
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05" :capabilities {}
                                 :clientInfo {:name "test" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})
    (Thread/sleep 1500)

    ;; Send a gated form and don't approve -- let it timeout
    (let [t0 (System/currentTimeMillis)
          msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
               :params {:name "nrepl_send"
                        :arguments {:form "(swap! (atom 0) inc)"}}}]
      (send-message! out msg)
      (let [resp (read-message in)
            elapsed (- (System/currentTimeMillis) t0)
            text (-> resp :result :content first :text)
            err? (-> resp :result :isError)]
        (record! "G5" "Gated form times out after 3s"
                 (and err? (< elapsed 8000)
                      (or (ci? text "timeout") (ci? text "timed out")))
                 (str "elapsed=" elapsed "ms isError=" err?
                      " text=" (pr-str (when text (subs text 0 (min 100 (count text)))))))))
    (finally
      (.close out)
      (proc/destroy server))))

;; ===== G6 -- Gate off by default =====
(println "--- G6: Gate off by default ---")
(let [server (proc/process
              {:cmd ["bb" ".nrepl-bridge/server.bb"
                     "--backend-port" (str backend-port)
                     "--shadow-build" ":app"
                     "--dashboard-port" (str (+ dashboard-port 3))]
               :in :pipe :out :pipe :err :pipe :dir "."})
      out (:in server)
      in  (java.io.BufferedInputStream. (:out server) 65536)]
  (try
    (send-message! out {:jsonrpc "2.0" :id (next-id) :method "initialize"
                        :params {:protocolVersion "2024-11-05" :capabilities {}
                                 :clientInfo {:name "test" :version "0.1.0"}}})
    (read-message in)
    (send-message! out {:jsonrpc "2.0" :method "notifications/initialized"})
    (Thread/sleep 1500)

    ;; Even an "unsafe" form should execute immediately without --gate
    (let [msg {:jsonrpc "2.0" :id (next-id) :method "tools/call"
               :params {:name "nrepl_send"
                        :arguments {:form "(do (swap! (atom 0) inc))"}}}]
      (send-message! out msg)
      (let [resp (read-message in)
            text (-> resp :result :content first :text)]
        (record! "G6" "Unsafe form executes without --gate flag"
                 (and text (ci? text "1"))
                 (str "Got: " (pr-str text)))))
    (finally
      (.close out)
      (proc/destroy server))))

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
  (System/exit (if (= passed total) 0 1)))
