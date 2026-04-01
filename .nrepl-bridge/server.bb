#!/usr/bin/env bb
;; server.bb -- MCP server for nREPL bridge
;;
;; Launched by Claude Code as a stdio MCP server.
;; Exposes one tool: nrepl_send
;;
;; Usage: bb .nrepl-bridge/server.bb [--backend-port PORT] [--frontend-port PORT] [--shadow-build :app]

(require '[babashka.pods :as pods]
         '[babashka.classpath :as cp])

;; Add src/ to classpath before requiring our namespaces
(cp/add-classpath (str (System/getProperty "babashka.file" ".")
                       "/../src"
                       java.io.File/pathSeparator
                       (str (System/getProperty "babashka.file" ".")
                            "/src")))

;; Resolve script directory for reliable classpath
(def script-dir
  (let [f (System/getProperty "babashka.file")]
    (if f
      (str (.getParent (java.io.File. f)))
      ".")))

(cp/add-classpath (str script-dir "/src"))

;; Load SQLite pod
(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[nrepl-bridge.logging :as log]
         '[nrepl-bridge.db :as db]
         '[nrepl-bridge.nrepl-client :as nrepl]
         '[nrepl-bridge.paren-repair :as repair]
         '[nrepl-bridge.web :as web]
         '[nrepl-bridge.gate :as gate])

(import '[java.time Instant])

;; --- CLI args ---

(defn parse-args [args]
  (loop [args args
         opts {:backend-port nil :frontend-port nil :shadow-build ":app"
               :dashboard-port 9500
               :gate false
               :gate-timeout-ms 120000}]
    (if (empty? args)
      opts
      (let [[flag & remaining] args]
        (case flag
          "--backend-port"    (recur (rest remaining) (assoc opts :backend-port (parse-long (first remaining))))
          "--frontend-port"   (recur (rest remaining) (assoc opts :frontend-port (parse-long (first remaining))))
          "--shadow-build"    (recur (rest remaining) (assoc opts :shadow-build (first remaining)))
          "--dashboard-port"  (recur (rest remaining) (assoc opts :dashboard-port (parse-long (first remaining))))
          "--gate"            (recur remaining (assoc opts :gate true))
          "--gate-timeout-ms" (recur (rest remaining) (assoc opts :gate-timeout-ms (parse-long (first remaining))))
          (recur remaining opts))))))

(defn- discover-nrepl-port
  "Find a live nREPL port automatically.
   Priority: .nrepl-port (clj), .shadow-cljs/nrepl.port (shadow-cljs),
   node_modules fallback. Skips stale port files where nothing is listening."
  []
  (let [candidates [".nrepl-port"
                    ".shadow-cljs/nrepl.port"
                    "node_modules/shadow-cljs-jar/.nrepl-port"]]
    (some (fn [path]
            (when (.exists (java.io.File. path))
              (let [port (parse-long (str/trim (slurp path)))]
                (if (nrepl/test-connection port 2000)
                  (do (log/log! :info (str "Discovered live nREPL port " port " from " path))
                      port)
                  ;; DESTRUCTIVE: deletes stale port files so they don't mislead
                  ;; future discovery attempts. Only deletes known nREPL port
                  ;; file names — never arbitrary paths.
                  (do (log/log! :warn (str "Stale port file " path " (port " port " not listening), deleting"))
                      (let [filename (.getName (java.io.File. path))]
                        (when (#{"nrepl.port" ".nrepl-port"} filename) ;; redundant safety check
                          (.delete (java.io.File. path))))
                      nil)))))
          candidates)))

(def config (atom (parse-args *command-line-args*)))

;; Remember whether the port was explicitly provided via CLI args.
;; CLI ports are never invalidated; only auto-discovered ports are.
(def ^:private cli-backend-port (:backend-port @config))

(defn- get-backend-port
  "Return the backend port, discovering lazily if not set.
   Retries discovery on each call until a port is found."
  []
  (or (:backend-port @config)
      (when-let [port (discover-nrepl-port)]
        (swap! config assoc :backend-port port)
        port)))

(defn- invalidate-backend-port!
  "Clear discovered port so next call re-reads port files.
   No-op if port was set via --backend-port CLI arg."
  []
  (when-not cli-backend-port
    (swap! config dissoc :backend-port)
    (log/log! :info "Cleared cached backend port; will re-discover on next eval")))

;; --- Startup self-check ---

(def startup-checks (atom []))

;; --- Session management ---

(def !sessions (atom {:backend nil :frontend nil}))
(def !msg-counter (atom 0))

(defn- next-msg-id []
  (str "nrepl-bridge-" (swap! !msg-counter inc)))

(defn- clone-target-session!
  "Clone a session for the given target. Updates !sessions atom. Returns session-id or nil."
  [target]
  (let [port (get-backend-port)
        sid  (nrepl/clone-session port 5000)]
    (swap! !sessions assoc (keyword target) sid)
    (log/log! :info (str "Session for " target ": " (or sid "FAILED")))
    sid))

(defn- ensure-session!
  "Return a valid session for the target, re-cloning if necessary.
   Interrupts a stuck session before re-cloning to avoid queue buildup."
  [target]
  (let [kw   (keyword target)
        sid  (get @!sessions kw)
        port (get-backend-port)]
    (if sid
      (do
        (log/log! :info (str "Checking session " sid " for " target "..."))
        (let [t0    (System/currentTimeMillis)
              alive (nrepl/session-alive? port sid 3000)
              ms    (- (System/currentTimeMillis) t0)]
          (log/log! :info (str "Session check for " target " took " ms "ms, alive=" alive))
          (if alive
            sid
            (do
              (log/log! :warn (str "Session " sid " for " target " is dead/stuck, interrupting"))
              (nrepl/interrupt-session! port sid 2000)
              (clone-target-session! target)))))
      (clone-target-session! target))))

(defn check! [name test-fn]
  (let [result (try
                 {:passed? true :detail (test-fn)}
                 (catch Exception e
                   {:passed? false :detail (.getMessage e)}))]
    (log/log-startup! name (:passed? result) (:detail result))
    (swap! startup-checks conj (assoc result :name name))
    result))

(def bridge-build "2026-04-01a")

(defn run-startup-checks! []
  (log/init!)
  (reset! web/!build bridge-build)
  (log/log! :info (str "=== nrepl-bridge starting === build " bridge-build))
  (log/log! :info (str "Config: " (pr-str @config)))

  (check! "bb-version"
          (fn []
            (let [v (System/getProperty "babashka.version")]
              (log/log! :info (str "Babashka version: " v))
              (when (and v (neg? (compare v "1.12.212")))
                (log/log! :warn "Babashka < 1.12.212, soft warning"))
              v)))

  (check! "cli-args"
          (fn []
            (when-not (get-backend-port)
              (throw (ex-info "nREPL port not yet available (will retry on first eval)" {})))
            (str "backend=" (get-backend-port)
                 (when (:frontend-port @config) (str " frontend=" (:frontend-port @config))))))

  (check! "sqlite-db"
          (fn [] (db/init-db!)))

  (check! "schema-applied"
          (fn [] "schema applied by init-db!"))

  (check! "backend-nrepl"
          (fn []
            (if (nrepl/test-connection (get-backend-port) 2000)
              (str "port " (get-backend-port) " reachable")
              (throw (ex-info (str "Cannot connect to port " (get-backend-port)) {})))))

  (check! "backend-session"
          (fn []
            (if-let [sid (clone-target-session! "backend")]
              (str "session " sid)
              (throw (ex-info "Failed to clone backend nREPL session" {})))))

  (when (:frontend-port @config)
    (check! "frontend-nrepl"
            (fn []
              (if (nrepl/test-connection (:frontend-port @config) 2000)
                (str "port " (:frontend-port @config) " reachable")
                (throw (ex-info (str "Cannot connect to port " (:frontend-port @config)) {}))))))

  (when (:frontend-port @config)
    (check! "frontend-session"
            (fn []
              (if-let [sid (clone-target-session! "frontend")]
                (str "session " sid)
                (throw (ex-info "Failed to clone frontend nREPL session" {}))))))

  (check! "logs-writable"
          (fn []
            (let [f (java.io.File. ".workbench/logs")]
              (.mkdirs f)
              (when-not (.canWrite f)
                (throw (ex-info "Cannot write to .workbench/logs/" {})))
              "writable")))

  (check! "dumps-writable"
          (fn []
            (let [f (java.io.File. ".workbench/dumps")]
              (.mkdirs f)
              (when-not (.canWrite f)
                (throw (ex-info "Cannot write to .workbench/dumps/" {})))
              "writable")))

  (log/log! :info (str "Startup checks complete: "
                       (count (filter :passed? @startup-checks)) "/"
                       (count @startup-checks) " passed")))

;; --- Content-Length framing (LSP-style) ---

(defn- read-line-bytes
  "Read a line from an InputStream (up to \\r\\n or \\n). Returns UTF-8 String or nil on EOF."
  [^java.io.InputStream in]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (= b -1)
          (when (pos? (.size baos)) (String. (.toByteArray baos) "UTF-8"))

          (= b (int \newline))
          (String. (.toByteArray baos) "UTF-8")

          (= b (int \return))
          (do
            (.mark in 1)
            (let [next-b (.read in)]
              (when (and (not= next-b -1) (not= next-b (int \newline)))
                (.reset in)))
            (String. (.toByteArray baos) "UTF-8"))

          :else
          (do (.write baos b) (recur)))))))

(def ^:private stdin-stream (java.io.BufferedInputStream. System/in 65536))

(defn read-message
  "Read a JSON-RPC message using Content-Length framing from stdin (byte-level)."
  []
  (when-let [line (read-line-bytes stdin-stream)]
    (if (str/starts-with? line "Content-Length:")
      (let [length (parse-long (str/trim (subs line (count "Content-Length:"))))]
        (read-line-bytes stdin-stream)
        (let [buf (byte-array length)]
          (loop [off 0]
            (when (< off length)
              (let [n (.read stdin-stream buf off (- length off))]
                (when (pos? n)
                  (recur (+ off n))))))
          (json/parse-string (String. buf "UTF-8") true)))
      (when (not (str/blank? line))
        (json/parse-string line true)))))

(defn write-message
  "Write a JSON-RPC message as newline-delimited JSON to stdout."
  [msg]
  (let [body  (json/generate-string msg {:escape-non-ascii true})
        bytes (.getBytes (str body "\n") "UTF-8")]
    (.write System/out bytes)
    (.flush System/out)))

;; --- MCP Handlers ---

(def tool-definition
  {:name "nrepl_send"
   :description "Send a Clojure or ClojureScript form to a running nREPL server for evaluation. Forms are delivered directly over TCP, bypassing the shell entirely. Use this instead of the Bash tool for all Clojure evaluation."
   :inputSchema
   {:type "object"
    :properties
    {:form {:type "string"
            :description "The Clojure/ClojureScript form to evaluate. Can include special characters, paths, Unicode -- nothing is shell-escaped."}
     :target {:type "string"
              :enum ["backend" "frontend"]
              :default "backend"
              :description "Which nREPL to send to. 'backend' for JVM Clojure, 'frontend' for shadow-cljs."}
     :ns {:type "string"
          :default "user"
          :description "The namespace to evaluate in."}
     :timeout_ms {:type "integer"
                  :default 30000
                  :description "Eval timeout in milliseconds. Increase for known long-running operations."}}
    :required ["form"]}})

(defn startup-report []
  (let [failed (filterv #(not (:passed? %)) @startup-checks)
        parts  (cond-> [(str "[nrepl-bridge build " bridge-build "]")]
                 (seq failed)
                 (conj (str "WARNING: Some startup checks failed:\n"
                            (str/join "\n" (mapv #(str "  FAIL: " (:name %) " -- " (:detail %)) failed))
                            "\nEval may not work correctly.")))]
    (str/join "\n" parts)))

(def ^:private err-limit 500)

(defn- summarize-err
  "Trim verbose error output for the MCP response.
   Full text is already persisted in the database."
  [err eval-id]
  (when err
    (let [cleaned (-> err
                      ;; #object[java.lang.ProcessImpl 0x1ee95ce7 "..."] → #<object>
                      (str/replace #"#object\[[^\]]*\]" "#<object>")
                      ;; hex stacktrace lines: \t0x7ff689... (Chrome/WebDriver dumps)
                      (str/replace #"\t0x[0-9a-fA-F]+\n?" "")
                      ;; collapse runs of blank lines
                      (str/replace #"\n{3,}" "\n\n"))]
      (if (> (count cleaned) err-limit)
        (str (subs cleaned 0 err-limit) "\n... [full error in dashboard #" eval-id "]")
        cleaned))))

(def ^:private dump-threshold 32768)

(defn- dump-large-result!
  "If value exceeds threshold, write to dump file."
  [eval-id value]
  (if (and value (> (count value) dump-threshold))
    (let [dump-dir  ".workbench/dumps"
          dump-file (str dump-dir "/eval-" (format "%04d" eval-id) ".edn")]
      (.mkdirs (java.io.File. dump-dir))
      (spit dump-file value :encoding "UTF-8")
      (let [summary (str "[LARGE RESULT " (count value) " chars, written to " dump-file "]"
                         "\n" (subs value 0 (min 500 (count value))) "...")]
        {:value summary :dump-path dump-file}))
    {:value value :dump-path nil}))

(defn- execute-and-respond!
  "Execute a form against nREPL and return the MCP tool response map."
  [{:keys [eval-id actual-port actual-form eval-ns timeout-ms
           target form original gated? session-id]}]
  (when gated?
    (db/update-eval! {:id eval-id :status "evaluating"
                      :value nil :out nil :err nil :ex nil
                      :eval-ms nil :dump-path nil}))
  (let [msg-id  (next-msg-id)
        t0      (System/currentTimeMillis)
        result  (nrepl/nrepl-eval {:port actual-port :code actual-form
                                   :ns eval-ns :timeout-ms timeout-ms
                                   :session session-id :id msg-id})
        ;; On connection error or value-swallowing corruption, re-clone and retry once
        [result session-id actual-port]
        (let [conn-error? (and (= "error" (:status result))
                               (= "ConnectException" (:ex result)))
              empty-result? (and (= "ok" (:status result))
                                 (nil? (:value result))
                                 (nil? (:err result))
                                 (nil? (:out result)))]
          (if (or conn-error? empty-result?)
            (do
              (log/log! :warn (str "Session " session-id " for " target
                                   (if conn-error?
                                     (str " connection refused on port " actual-port ", re-discovering")
                                     " returned empty result, re-cloning")))
              (when conn-error?
                (invalidate-backend-port!))
              (let [retry-port (get-backend-port)
                    new-sid    (clone-target-session! target)
                    new-mid    (next-msg-id)
                    retry      (nrepl/nrepl-eval {:port retry-port :code actual-form
                                                  :ns eval-ns :timeout-ms timeout-ms
                                                  :session new-sid :id new-mid})]
                [retry new-sid retry-port]))
            [result session-id actual-port]))
        elapsed (- (System/currentTimeMillis) t0)
        dumped  (dump-large-result! eval-id (:value result))
        status  (:status result)]
    (db/update-eval! {:id eval-id :status status
                      :value (:value dumped) :out (:out result)
                      :err (:err result) :ex (:ex result)
                      :eval-ms elapsed :dump-path (:dump-path dumped)})
    (log/log-eval! {:id eval-id :target target :port actual-port :ns eval-ns
                    :form-length (count form) :form-preview form
                    :status status :eval-ms elapsed
                    :repaired? (boolean original)
                    :dump-path (:dump-path dumped)})
    (web/broadcast! {:type "eval-update" :id eval-id :status status
                     :target target :eval-ms elapsed})
    (let [err-summary (summarize-err (:err result) eval-id)
          parts (cond-> []
                  original
                  (conj (str "[Paren repair applied]\nOriginal: " original "\nRepaired: " form))
                  (:out result)
                  (conj (str "stdout:\n" (:out result)))
                  (:value dumped)
                  (conj (:value dumped))
                  (and (not (:value dumped)) (not err-summary))
                  (conj "nil")
                  err-summary
                  (conj (str "stderr:\n" err-summary))
                  (:ex result)
                  (conj (str "exception: " (:ex result))))
          text  (str/join "\n" parts)]
      {:content [{:type "text" :text text}]
       :isError (not= status "ok")})))

(defn handle-nrepl-send
  "Handle the nrepl_send tool call."
  [params]
  (let [startup-warn (startup-report)
        raw-form   (:form params)
        target     (or (:target params) "backend")
        eval-ns    (or (:ns params) "user")
        timeout-ms (or (:timeout_ms params) 30000)
        port       (if (= target "frontend")
                     (or (:frontend-port @config) (get-backend-port))
                     (get-backend-port))
        processed  (repair/process-form raw-form)
        form       (:form processed)
        original   (:original processed)
        error      (:error processed)
        actual-form (if (= target "frontend")
                      (nrepl/wrap-frontend-form form (:shadow-build @config))
                      form)
        actual-port (if (= target "frontend") (get-backend-port) port)
        session-id  (ensure-session! target)]
    (cond
      ;; Syntax error
      error
      (let [eval-id (db/insert-eval! {:target target :port port :ns eval-ns
                                      :form (or form raw-form)
                                      :form-original original
                                      :session-id session-id})]
        (db/update-eval! {:id eval-id :status "syntax-error" :err error :eval-ms 0})
        (log/log-eval! {:id eval-id :target target :port port :ns eval-ns
                        :form-length (count raw-form) :form-preview raw-form
                        :status "syntax-error" :repaired? false})
        {:content [{:type "text"
                    :text (str (when startup-warn (str startup-warn "\n\n"))
                               "Syntax error: " error "\nForm was not sent to nREPL.")}]
         :isError true})

      ;; Gated form -- wait for approval
      (and (:gate @config) (= :gated (gate/classify-form form)))
      (let [eval-id  (db/insert-gated-eval! {:target target :port actual-port :ns eval-ns
                                             :form form :form-original original
                                             :session-id session-id})
            decision (gate/wait-for-decision! eval-id (:gate-timeout-ms @config))]
        (case decision
          :approved
          (execute-and-respond! {:eval-id eval-id :actual-port actual-port
                                 :actual-form actual-form :eval-ns eval-ns
                                 :timeout-ms timeout-ms :target target
                                 :form form :original original :gated? true
                                 :session-id session-id})
          :rejected
          (do
            (db/update-eval! {:id eval-id :status "error" :value nil :out nil
                              :err "Rejected by human reviewer" :ex nil
                              :eval-ms 0 :dump-path nil})
            (web/broadcast! {:type "eval-update" :id eval-id :status "rejected"})
            (let [row (db/eval-by-id eval-id)]
              {:content [{:type "text"
                          :text (str "Eval #" eval-id " rejected"
                                     ": " (or (:err row) "Rejected")
                                     (when (:feedback row)
                                       (str "\nFeedback: " (:feedback row))))}]
               :isError true}))
          :timeout
          (do
            (db/update-eval! {:id eval-id :status "timeout" :value nil :out nil
                              :err "Approval timeout" :ex nil
                              :eval-ms 0 :dump-path nil})
            (web/broadcast! {:type "eval-update" :id eval-id :status "timeout"})
            {:content [{:type "text"
                        :text (str "Eval #" eval-id " timed out waiting for approval")}]
             :isError true})))

      ;; Normal eval
      :else
      (let [eval-id (db/insert-eval! {:target target :port actual-port :ns eval-ns
                                      :form form :form-original original
                                      :session-id session-id})]
        (web/broadcast! {:type "eval-update" :id eval-id :status "evaluating"
                         :target target})
        (execute-and-respond! {:eval-id eval-id :actual-port actual-port
                               :actual-form actual-form :eval-ns eval-ns
                               :timeout-ms timeout-ms :target target
                               :form form :original original :gated? false
                               :session-id session-id})))))

(defn handle-request [msg]
  (let [method (:method msg)
        id     (:id msg)
        params (:params msg)]
    (case method
      "initialize"
      {:jsonrpc "2.0" :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities {:tools {:listChanged false}}
                :serverInfo {:name "nrepl-bridge" :version "0.1.0"}}}

      "notifications/initialized"
      nil

      "tools/list"
      {:jsonrpc "2.0" :id id
       :result {:tools [tool-definition]}}

      "tools/call"
      (let [tool-name (:name params)
            arguments (:arguments params)]
        (if (= tool-name "nrepl_send")
          {:jsonrpc "2.0" :id id
           :result (handle-nrepl-send arguments)}
          {:jsonrpc "2.0" :id id
           :error {:code -32601 :message (str "Unknown tool: " tool-name)}}))

      (when id
        {:jsonrpc "2.0" :id id
         :error {:code -32601 :message (str "Unknown method: " method)}}))))

;; --- Main loop ---

(run-startup-checks!)

(future
  (try
    (web/start! (:dashboard-port @config))
    (catch Exception e
      (log/log! :warn (str "Dashboard failed to start: " (.getMessage e))))))

(log/log! :info (str "MCP server ready, dashboard at http://localhost:" (:dashboard-port @config)))

(loop []
  (when-let [msg (try (read-message) (catch Exception e
                                       (log/log! :error (str "read-message error: " (.getMessage e)))
                                       nil))]
    (let [response (try (handle-request msg)
                        (catch Exception e
                          (log/log! :error (str "handle-request error: " (.getMessage e)
                                                " for method=" (:method msg)))
                          (when (:id msg)
                            {:jsonrpc "2.0" :id (:id msg)
                             :error {:code -32603 :message (.getMessage e)}})))]
      (when response
        (write-message response)))
    (recur)))

(log/log! :info "MCP server shutting down (stdin closed)")
