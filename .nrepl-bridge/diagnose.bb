#!/usr/bin/env bb
;; diagnose.bb -- Diagnose why the MCP server isn't registering in Claude Code
;;
;; Checks every layer that could fail silently on Windows.
;;
;; Usage: bb .nrepl-bridge/diagnose.bb

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.process :as proc])

(def checks (atom []))

(defn check! [name pass? detail]
  (swap! checks conj {:name name :pass pass? :detail detail})
  (println (str "  " (if pass? "OK" "FAIL") "  " name))
  (when (not pass?)
    (println (str "       " detail))))

(println "=== MCP Server Diagnostics ===")
(println)

;; 1. bb on PATH
(println "--- Layer 1: Babashka ---")
(let [bb-path (try (str/trim (:out (proc/sh ["which" "bb"]))) (catch Exception _ nil))
      bb-ver  (try (str/trim (:out (proc/sh ["bb" "--version"]))) (catch Exception _ nil))]
  (check! "bb found on PATH" (some? bb-path) (str "which bb -> " (pr-str bb-path)))
  (check! "bb version" (some? bb-ver) (str "bb --version -> " (pr-str bb-ver))))

;; 2. MCP config exists and is valid JSON
;; The canonical path is .mcp.json at project root (Claude Code reads this).
;; Older versions of Claude Code used .claude/settings.json, which is no
;; longer where bridge servers are registered.
(println)
(println "--- Layer 2: MCP config ---")
(def ^:dynamic *bridge-args* nil)   ;; populated below; used by Layer 5
(let [mcp-path ".mcp.json"
      exists? (.exists (java.io.File. mcp-path))]
  (check! ".mcp.json exists" exists? (str "at " mcp-path))
  (when exists?
    (let [content (slurp mcp-path :encoding "UTF-8")]
      (try
        (let [parsed (json/parse-string content true)
              mcp (:mcpServers parsed)
              bridge (:nrepl-bridge mcp)]
          (check! "JSON parses" true "valid JSON")
          (check! "mcpServers key exists" (some? mcp)
                  (str "keys=" (pr-str (keys parsed))))
          (check! "nrepl-bridge server defined" (some? bridge)
                  (str "servers=" (pr-str (keys mcp))))
          (when bridge
            (check! "type is stdio" (= "stdio" (:type bridge))
                    (str "type=" (:type bridge)))
            (check! "command is bb" (= "bb" (:command bridge))
                    (str "command=" (:command bridge)))
            (check! "args present" (seq (:args bridge))
                    (str "args=" (pr-str (:args bridge))))
            (alter-var-root #'*bridge-args* (constantly (vec (:args bridge))))))
        (catch Exception e
          (check! "JSON parses" false (.getMessage e)))))))

;; Helper: extract a flag value from a bridge args vector.
;; e.g. (arg-value ["server.bb" "--backend-port" "22400"] "--backend-port") => "22400"
(defn arg-value [args flag]
  (when args
    (->> (partition 2 1 args)
         (some (fn [[a b]] (when (= a flag) b))))))

;; Resolve the actual nREPL backend port from .mcp.json (fallback: 17888 only
;; if config is missing entirely -- previously this was hardcoded).
(def backend-port
  (or (some-> (arg-value *bridge-args* "--backend-port") parse-long)
      17888))
(def dashboard-port
  (or (some-> (arg-value *bridge-args* "--dashboard-port") parse-long)
      9599))

(println)
(println (str "  (using backend-port=" backend-port
              ", dashboard-port=" dashboard-port
              " from .mcp.json)"))

;; 3. Script file exists
(println)
(println "--- Layer 3: Script file ---")
(let [script ".nrepl-bridge/server.bb"
      exists? (.exists (java.io.File. script))]
  (check! "mcp-server.bb exists" exists? (str "at " script))
  (when exists?
    (check! "script is readable" (.canRead (java.io.File. script)) "file permissions")))

;; 4. SQLite pod can load
(println)
(println "--- Layer 4: SQLite pod ---")
(require '[babashka.pods :as pods])
(let [pod-ok? (try
                (pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")
                true
                (catch Exception e
                  (check! "SQLite pod loads" false (.getMessage e))
                  false))]
  (check! "SQLite pod loads" pod-ok? "pod-babashka-go-sqlite3 0.3.13"))

;; 5. MCP server starts and responds to initialize
(println)
(println "--- Layer 5: MCP server startup ---")
(let [server (try
               (proc/process
                {:cmd ["bb" ".nrepl-bridge/server.bb"
                       "--backend-port" (str backend-port)
                       "--shadow-build" ":app"
                       "--dashboard-port" (str dashboard-port)]
                 :in :pipe :out :pipe :err :pipe
                 :dir "."})
               (catch Exception e
                 (check! "Process spawns" false (.getMessage e))
                 nil))]
  (when server
    (check! "Process spawns" true "bb process started")
    (let [out    (:in server)
          in     (java.io.BufferedInputStream. (:out server) 65536)
          err-in (:err server)]
      (try
        ;; Send initialize
        (let [init-msg {:jsonrpc "2.0" :id 1 :method "initialize"
                        :params {:protocolVersion "2024-11-05"
                                 :capabilities {}
                                 :clientInfo {:name "diagnose" :version "0.1.0"}}}
              body (json/generate-string init-msg)
              bytes (.getBytes body "UTF-8")
              header (str "Content-Length: " (count bytes) "\r\n\r\n")]
          (.write out (.getBytes header "UTF-8"))
          (.write out bytes)
          (.flush out))

        ;; Read response with timeout
        (let [start (System/currentTimeMillis)
              response-text (atom nil)]
          ;; Wait up to 15 seconds for a response
          (loop [elapsed 0]
            (when (and (< elapsed 15000) (nil? @response-text))
              (if (pos? (.available in))
                ;; Read Content-Length header
                (let [sb (StringBuilder.)]
                  (loop []
                    (let [b (.read in)]
                      (when (and (not= b -1) (not= b (int \newline)))
                        (.append sb (char b))
                        (recur))))
                  (let [header-line (str sb)]
                    (when (str/starts-with? (str/trim header-line) "Content-Length:")
                      ;; Read blank line
                      (loop []
                        (let [b (.read in)]
                          (when (and (not= b -1) (not= b (int \newline)))
                            (recur))))
                      ;; Read body
                      (let [length (parse-long (str/trim (subs (str/trim header-line) (count "Content-Length:"))))
                            buf (byte-array length)]
                        (loop [off 0]
                          (when (< off length)
                            (let [n (.read in buf off (- length off))]
                              (when (pos? n) (recur (+ off n))))))
                        (reset! response-text (String. buf "UTF-8"))))))
                (do
                  (Thread/sleep 200)
                  (recur (- (System/currentTimeMillis) start))))))

          (if @response-text
            (do
              (check! "initialize response received" true
                      (str "in " (- (System/currentTimeMillis) start) "ms"))
              (let [parsed (json/parse-string @response-text true)
                    server-info (-> parsed :result :serverInfo)
                    caps (-> parsed :result :capabilities)]
                (check! "response is valid JSON-RPC" (= "2.0" (:jsonrpc parsed))
                        (str "jsonrpc=" (:jsonrpc parsed)))
                (check! "serverInfo present" (some? server-info)
                        (str "serverInfo=" (pr-str server-info)))
                (check! "capabilities present" (some? caps)
                        (str "capabilities=" (pr-str caps)))

                ;; Now send tools/list
                (let [list-msg {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
                      body (json/generate-string list-msg)
                      bytes (.getBytes body "UTF-8")
                      header (str "Content-Length: " (count bytes) "\r\n\r\n")]
                  (.write out (.getBytes header "UTF-8"))
                  (.write out bytes)
                  (.flush out))

                ;; Read tools/list response
                (Thread/sleep 1000)
                (let [sb2 (StringBuilder.)]
                  (when (pos? (.available in))
                    (loop []
                      (let [b (.read in)]
                        (when (and (not= b -1) (not= b (int \newline)))
                          (.append sb2 (char b))
                          (recur))))
                    ;; Read blank line
                    (loop []
                      (let [b (.read in)]
                        (when (and (not= b -1) (not= b (int \newline)))
                          (recur))))
                    (let [header-line (str sb2)]
                      (when (str/starts-with? (str/trim header-line) "Content-Length:")
                        (let [length (parse-long (str/trim (subs (str/trim header-line) (count "Content-Length:"))))
                              buf (byte-array length)]
                          (loop [off 0]
                            (when (< off length)
                              (let [n (.read in buf off (- length off))]
                                (when (pos? n) (recur (+ off n))))))
                          (let [tools-text (String. buf "UTF-8")
                                tools-parsed (json/parse-string tools-text true)
                                tools (-> tools-parsed :result :tools)]
                            (check! "tools/list responds" true
                                    (str "tools count=" (count tools)))
                            (check! "nrepl_send tool listed" (some #(= "nrepl_send" (:name %)) tools)
                                    (str "tool names=" (mapv :name tools)))))))))))
            (do
              (check! "initialize response received" false
                      "No response within 15 seconds")
              ;; Check stderr for clues
              (Thread/sleep 500)
              (let [err-bytes (byte-array (.available err-in))]
                (when (pos? (count err-bytes))
                  (.read err-in err-bytes)
                  (let [err-text (String. err-bytes "UTF-8")]
                    (println "  STDERR output:")
                    (println (str "    " (str/replace err-text "\n" "\n    ")))))))))

        (finally
          (.close out)
          (proc/destroy server))))))

;; 6. Check nREPL connectivity (uses backend-port resolved from .mcp.json)
(println)
(println "--- Layer 6: nREPL connectivity ---")
(let [nrepl-ok? (try
                  (let [sock (java.net.Socket.)]
                    (.connect sock (java.net.InetSocketAddress. "127.0.0.1" backend-port) 2000)
                    (.close sock)
                    true)
                  (catch Exception _ false))]
  (check! (str "nREPL on port " backend-port " reachable") nrepl-ok?
          (if nrepl-ok? "TCP connect succeeded" "Cannot connect -- is shadow-cljs running?")))

;; 7. Check log file for recent errors
(println)
(println "--- Layer 7: Recent logs ---")
(let [log-file (java.io.File. ".workbench/logs/nrepl-bridge.log")]
  (if (.exists log-file)
    (let [lines (str/split-lines (slurp log-file :encoding "UTF-8"))
          recent (take-last 10 lines)
          has-errors (some #(str/includes? % "ERROR") recent)]
      (check! "Log file exists" true (str (count lines) " lines"))
      (check! "No recent errors" (not has-errors)
              (if has-errors
                (str "Recent errors:\n       " (str/join "\n       " (filter #(str/includes? % "ERROR") recent)))
                "clean"))
      (println "  Last 5 log lines:")
      (doseq [line (take-last 5 lines)]
        (println (str "    " line))))
    (check! "Log file exists" false ".workbench/logs/nrepl-bridge.log not found")))

;; Summary
(println)
(println "=== Summary ===")
(let [passed (count (filter :pass @checks))
      total (count @checks)
      failed (filterv #(not (:pass %)) @checks)]
  (println (str passed "/" total " checks passed"))
  (when (seq failed)
    (println)
    (println "FAILED checks:")
    (doseq [f failed]
      (println (str "  " (:name f) ": " (:detail f))))))
