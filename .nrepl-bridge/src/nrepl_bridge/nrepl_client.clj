(ns nrepl-bridge.nrepl-client
  "TCP/bencode nREPL client (direct socket connection to nREPL server).
   No shell, no encoding corruption."
  (:require [bencode.core :as bencode]
            [clojure.string :as str]
            [nrepl-bridge.logging :as log])
  (:import [java.net Socket ConnectException SocketTimeoutException]
           [java.io PushbackInputStream BufferedOutputStream]))

(defn- bytes->str
  "Decode a bencode value: byte-array -> String (UTF-8), nil -> nil, else str."
  [v]
  (cond
    (bytes? v) (String. ^bytes v "UTF-8")
    (nil? v)   nil
    :else      (str v)))

(defn- status-done?
  "Check if an nREPL status vector contains 'done'."
  [status]
  (and (sequential? status)
       (some #(= "done" (bytes->str %)) status)))

(defn escape-non-ascii
  "Replace non-ASCII chars with \\uXXXX escapes so the form is pure ASCII
   on the bencode wire. The Clojure reader on the nREPL server decodes them."
  [^String s]
  (let [sb (StringBuilder.)]
    (dotimes [i (.length s)]
      (let [c (.charAt s i)]
        (if (> (int c) 127)
          (.append sb (format "\\u%04X" (int c)))
          (.append sb c))))
    (str sb)))

(defn strip-markdown-fences
  "Remove ```lang ... ``` wrappers from code strings."
  [code]
  (let [fenced (re-pattern "(?s)```\\w*\\r?\\n(.*?)```")]
    (if (re-find fenced code)
      (str/join "\n" (map second (re-seq fenced code)))
      code)))

(defn nrepl-eval
  "Send code to nREPL over TCP/bencode. Returns result map.
   Options:
     :port       - nREPL port (required)
     :code       - Clojure form string (required)
     :ns         - namespace (default \"user\")
     :timeout-ms - socket timeout in ms (default 30000)
     :session    - nREPL session ID (optional, omitted if nil)
     :id         - message ID (optional, omitted if nil)"
  [{:keys [port code ns timeout-ms session id]
    :or   {ns "user" timeout-ms 30000}}]
  (let [sock (doto (Socket. "127.0.0.1" (int port))
               (.setSoTimeout (int timeout-ms)))
        ;; Hard deadline: force-close socket if eval exceeds timeout.
        ;; Belt-and-suspenders for cases where setSoTimeout alone doesn't fire.
        deadline (future
                   (Thread/sleep (long (+ timeout-ms 5000)))
                   (log/log! :warn (str "Hard deadline reached (" timeout-ms "+5000ms), closing socket"))
                   (try (.close sock) (catch Exception _)))]
    (try
      (log/log! :info (str "TCP connect 127.0.0.1:" port))
      (with-open [_ sock
                  out  (BufferedOutputStream. (.getOutputStream sock))
                  in   (PushbackInputStream. (.getInputStream sock))]
        (let [escaped (escape-non-ascii code)
              msg (cond-> {"op" "eval" "code" escaped "ns" ns}
                    session (assoc "session" session)
                    id (assoc "id" id))]
          (log/log! :info "TCP connected, sending eval")
          (bencode/write-bencode out msg)
          (.flush out)
          (loop [result {:value nil :out nil :err nil :ex nil :ns nil}]
            (let [resp (bencode/read-bencode in)
                  gs #(bytes->str (get resp %))
                  result (cond-> result
                           (gs "value") (assoc :value (gs "value"))
                           (gs "out") (update :out str (gs "out"))
                           (gs "err") (update :err str (gs "err"))
                           (gs "ex") (assoc :ex (gs "ex"))
                           (gs "ns") (assoc :ns (gs "ns")))]
              (if (status-done? (get resp "status"))
                (do
                  (log/log! :info "TCP disconnecting (done)")
                  (assoc result :status (if (:ex result) "error" "ok")))
                (recur result))))))
      (catch ConnectException e
        (log/log! :error (str "ConnectException port=" port ": " (.getMessage e)))
        {:status "error" :ex "ConnectException"
         :err (str "Connection refused on port " port ". Is nREPL running?")})
      (catch SocketTimeoutException e
        (log/log! :error (str "SocketTimeoutException port=" port " timeout=" timeout-ms))
        {:status "timeout" :ex "SocketTimeoutException"
         :err (str "Eval timed out after " timeout-ms "ms")})
      (catch Exception e
        (log/log! :error (str "Exception during eval: " (.getMessage e)))
        {:status "error" :ex (str (class e))
         :err (.getMessage e)})
      (finally
        (future-cancel deadline)))))

(defn wrap-frontend-form
  "Wrap a form for ClojureScript evaluation via shadow-cljs backend nREPL.
   Uses shadow.cljs.devtools.api/cljs-eval."
  [form shadow-build]
  (str "(shadow.cljs.devtools.api/cljs-eval " shadow-build " "
       (pr-str form) " {})"))

(defn test-connection
  "Try a TCP connect to the given port. Returns true/false."
  [port timeout-ms]
  (try
    (with-open [sock (doto (Socket.)
                       (.connect
                        (java.net.InetSocketAddress. "127.0.0.1" (int port))
                        (int timeout-ms)))]
      true)
    (catch Exception _
      false)))

(defn clone-session
  "Clone a new nREPL session. Returns the session-id string, or nil on failure."
  [port timeout-ms]
  (let [sock (doto (Socket. "127.0.0.1" (int port))
               (.setSoTimeout (int timeout-ms)))
        deadline (future
                   (Thread/sleep (long (+ timeout-ms 5000)))
                   (log/log! :warn (str "Hard deadline on clone-session, closing socket"))
                   (try (.close sock) (catch Exception _)))]
    (try
      (log/log! :info (str "Cloning nREPL session on port " port))
      (with-open [_ sock
                  out  (BufferedOutputStream. (.getOutputStream sock))
                  in   (PushbackInputStream. (.getInputStream sock))]
        (bencode/write-bencode out {"op" "clone"})
        (.flush out)
        (loop []
          (let [resp        (bencode/read-bencode in)
                new-session (bytes->str (get resp "new-session"))
                status      (get resp "status")]
            (if (status-done? status)
              (do
                (log/log! :info (str "Cloned session: " new-session))
                new-session)
              (recur)))))
      (catch Exception e
        (log/log! :error (str "Failed to clone session on port " port ": " (.getMessage e)))
        nil)
      (finally
        (future-cancel deadline)))))

(defn interrupt-session!
  "Send an nREPL interrupt op to cancel any running eval on a session.
   Best-effort: returns true if the interrupt was acknowledged, false on error."
  [port session timeout-ms]
  (let [sock (doto (Socket. "127.0.0.1" (int port))
               (.setSoTimeout (int timeout-ms)))
        deadline (future
                   (Thread/sleep (long (+ timeout-ms 5000)))
                   (log/log! :warn (str "Hard deadline on interrupt-session!, closing socket"))
                   (try (.close sock) (catch Exception _)))]
    (try
      (log/log! :info (str "Interrupting session " session " on port " port))
      (with-open [_ sock
                  out  (BufferedOutputStream. (.getOutputStream sock))
                  in   (PushbackInputStream. (.getInputStream sock))]
        (bencode/write-bencode out {"op" "interrupt" "session" session})
        (.flush out)
        (loop []
          (let [resp   (bencode/read-bencode in)
                status (get resp "status")]
            (if (status-done? status)
              (do (log/log! :info (str "Interrupt acknowledged for session " session))
                  true)
              (recur)))))
      (catch Exception e
        (log/log! :warn (str "Interrupt failed for session " session ": " (.getMessage e)))
        false)
      (finally
        (future-cancel deadline)))))

(defn session-alive?
  "Check if a session is still valid by evaluating a known form.
   Returns true only if the session responds with the expected value.
   Detects both dead sessions and value-swallowing corruption."
  [port session timeout-ms]
  (try
    (let [result (nrepl-eval {:port port :code "(+ 41 1)" :ns "user"
                              :timeout-ms timeout-ms :session session})]
      (and (= "ok" (:status result))
           (= "42" (:value result))))
    (catch Exception _ false)))
