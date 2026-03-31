(ns nrepl-bridge.web
  "Web dashboard for nrepl-bridge eval history.
   Runs inside the MCP server process on a dedicated port.
   Uses http-kit for HTTP/WebSocket and hiccup2 for HTML rendering."
  (:require [org.httpkit.server :as http]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [cheshire.core :as json]
            [clojure.string :as str]
            [nrepl-bridge.db :as db]
            [nrepl-bridge.logging :as log]))

;; --- WebSocket clients ---

(def !ws-clients (atom #{}))

(defn broadcast!
  "Send a JSON message to all connected WebSocket clients."
  [msg]
  (let [payload (json/generate-string msg)]
    (doseq [ch @!ws-clients]
      (try
        (http/send! ch payload)
        (catch Exception _
          (swap! !ws-clients disj ch))))))

;; --- HTML rendering ---

(defn- truncate [s n]
  (if (and s (> (count s) n))
    (str (subs s 0 n) "...")
    (or s "")))

(defn- status-badge [status]
  (let [color (case status
                "ok"           "#22c55e"
                "error"        "#ef4444"
                "timeout"      "#f59e0b"
                "syntax-error" "#f97316"
                "pending"      "#8b5cf6"
                "evaluating"   "#3b82f6"
                "#6b7280")]
    [:span {:style (str "background:" color ";color:#fff;padding:2px 8px;"
                        "border-radius:4px;font-size:12px;font-weight:bold")}
     (hu/raw-string status)]))

(defn- decision-badge [decision]
  (when (and decision (not= decision "auto"))
    (let [color (case decision
                  "pending"  "#8b5cf6"
                  "approved" "#22c55e"
                  "rejected" "#ef4444"
                  "#6b7280")]
      [:span {:style (str "background:" color ";color:#fff;padding:2px 8px;"
                          "border-radius:4px;font-size:11px;margin-left:4px")}
       (hu/raw-string decision)])))

(defn- eval-row-html [row]
  [:tr {:id (str "eval-" (:id row))
        :style "border-bottom:1px solid #333"}
   [:td {:style "padding:8px;color:#888"} (str "#" (:id row))]
   [:td {:style "padding:8px"} (status-badge (:status row))
    (decision-badge (:decision row))]
   [:td {:style "padding:8px;color:#aaa"} (:target row)]
   [:td {:style "padding:8px;color:#aaa"} (or (:ns row) "user")]
   [:td {:style "padding:8px;font-family:monospace;font-size:13px;max-width:400px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
         :title (:form row)}
    (truncate (:form row) 80)]
   [:td {:style "padding:8px;font-family:monospace;font-size:13px;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#8f8"
         :title (or (:value row) "")}
    (truncate (:value row) 60)]
   [:td {:style "padding:8px;color:#aaa;text-align:right"}
    (when (:eval_ms row) (str (:eval_ms row) "ms"))]
   [:td {:style "padding:8px;color:#666;font-size:12px"}
    (when (:created_at row)
      (let [ts (:created_at row)]
        (if (> (count ts) 19) (subs ts 11 19) ts)))]])

(defn- pending-section [pending-rows]
  (when (seq pending-rows)
    [:div {:id "pending-section"
           :style "background:#1a1020;border:2px solid #8b5cf6;border-radius:8px;padding:16px;margin-bottom:24px"}
     [:h2 {:style "color:#8b5cf6;margin:0 0 12px 0"} "Awaiting Approval"]
     (for [row pending-rows]
       [:div {:style "background:#0d0d0d;border:1px solid #444;border-radius:4px;padding:12px;margin-bottom:8px"}
        [:div {:style "display:flex;justify-content:space-between;align-items:center"}
         [:div
          [:strong {:style "color:#ccc"} (str "#" (:id row))]
          [:span {:style "color:#888;margin-left:8px"} (:target row)]
          [:span {:style "color:#888;margin-left:8px"} (:ns row)]]
         [:div
          [:button {:onclick (str "decide(" (:id row) ",'approved')")
                    :style "background:#22c55e;color:#fff;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;margin-right:8px;font-weight:bold"}
           "Approve"]
          [:button {:onclick (str "decide(" (:id row) ",'rejected')")
                    :style "background:#ef4444;color:#fff;border:none;padding:6px 16px;border-radius:4px;cursor:pointer;font-weight:bold"}
           "Reject"]]]
        [:pre {:style "color:#e0e0e0;margin:8px 0 0 0;font-size:13px;white-space:pre-wrap;word-break:break-all"}
         (:form row)]])]))

(defn- page-html
  "Render the full dashboard HTML page."
  [{:keys [evals stats duration pending status-filter]}]
  (str
   "<!DOCTYPE html>"
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title "nREPL Bridge Dashboard"]
      [:style (hu/raw-string "
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { background: #0d0d0d; color: #e0e0e0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', monospace; }
        .container { max-width: 1200px; margin: 0 auto; padding: 16px; }
        h1 { color: #3b82f6; margin-bottom: 16px; font-size: 24px; }
        .stats { display: flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
        .stat-card { background: #1a1a1a; border: 1px solid #333; border-radius: 8px; padding: 12px 16px; min-width: 120px; }
        .stat-value { font-size: 24px; font-weight: bold; }
        .stat-label { font-size: 12px; color: #888; }
        .filters { margin-bottom: 16px; }
        .filters a { color: #3b82f6; text-decoration: none; margin-right: 12px; padding: 4px 8px; border-radius: 4px; }
        .filters a:hover, .filters a.active { background: #1a2a4a; }
        table { width: 100%; border-collapse: collapse; }
        th { text-align: left; padding: 8px; color: #888; border-bottom: 2px solid #333; font-size: 13px; }
        #live-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #22c55e; margin-left: 8px; animation: pulse 2s infinite; }
        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
        .log-section { background: #1a1a1a; border: 1px solid #333; border-radius: 8px; padding: 16px; margin-top: 24px; }
        .log-section pre { color: #aaa; font-size: 12px; max-height: 300px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; }
      ")]]
     [:body
      [:div.container
       [:h1 "nREPL Bridge Dashboard" [:span#live-dot]]

       ;; Pending approvals (prominent, at top)
       (pending-section pending)

       ;; Stats cards
       [:div.stats
        [:div.stat-card [:div.stat-value {:style "color:#fff"} (str (:total stats))] [:div.stat-label "Total"]]
        [:div.stat-card [:div.stat-value {:style "color:#22c55e"} (str (:ok stats))] [:div.stat-label "OK"]]
        [:div.stat-card [:div.stat-value {:style "color:#ef4444"} (str (:errors stats))] [:div.stat-label "Errors"]]
        [:div.stat-card [:div.stat-value {:style "color:#f59e0b"} (str (:timeouts stats))] [:div.stat-label "Timeouts"]]
        [:div.stat-card [:div.stat-value {:style "color:#f97316"} (str (:syntax_errors stats))] [:div.stat-label "Syntax Err"]]
        [:div.stat-card [:div.stat-value {:style "color:#3b82f6"} (str (:avg_ms duration "0") "ms")] [:div.stat-label "Avg Duration"]]
        [:div.stat-card [:div.stat-value {:style "color:#888"} (str (:p50 duration "0") "ms")] [:div.stat-label "p50"]]
        [:div.stat-card [:div.stat-value {:style "color:#888"} (str (:p95 duration "0") "ms")] [:div.stat-label "p95"]]]

       ;; Status filters
       [:div.filters
        [:a {:href "/" :class (when-not status-filter "active")} "All"]
        [:a {:href "/?status=ok" :class (when (= status-filter "ok") "active")} "OK"]
        [:a {:href "/?status=error" :class (when (= status-filter "error") "active")} "Errors"]
        [:a {:href "/?status=timeout" :class (when (= status-filter "timeout") "active")} "Timeouts"]
        [:a {:href "/?status=syntax-error" :class (when (= status-filter "syntax-error") "active")} "Syntax"]]

       ;; Eval table
       [:table
        [:thead
         [:tr
          [:th "ID"] [:th "Status"] [:th "Target"] [:th "NS"]
          [:th "Form"] [:th "Value"] [:th "Duration"] [:th "Time"]]]
        [:tbody {:id "eval-tbody"}
         (for [row evals] (eval-row-html row))]]

       ;; Log viewer
       [:div.log-section
        [:h3 {:style "color:#888;margin-bottom:8px"} "Recent Log"]
        [:pre {:id "log-content"} "Loading..."]]

       ;; JavaScript
       [:script (hu/raw-string "
        // Fetch and display logs
        function loadLogs() {
          fetch('/api/logs').then(r => r.json()).then(data => {
            document.getElementById('log-content').textContent = data.lines.join('\\n');
          }).catch(() => {});
        }
        loadLogs();
        setInterval(loadLogs, 10000);

        // Approval gate
        function decide(id, decision) {
          var feedback = '';
          if (decision === 'rejected') {
            feedback = prompt('Reason for rejection (optional):') || '';
          }
          fetch('/api/evals/' + id + '/decide', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({decision: decision, feedback: feedback})
          }).then(() => location.reload());
        }

        // WebSocket for live updates
        var ws;
        function connectWs() {
          var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
          ws = new WebSocket(proto + '//' + location.host + '/ws');
          ws.onmessage = function(e) {
            var data = JSON.parse(e.data);
            if (data.type === 'eval-update') {
              // Reload page for simplicity -- could be smarter
              location.reload();
            }
          };
          ws.onclose = function() { setTimeout(connectWs, 3000); };
        }
        connectWs();

        // Auto-refresh every 30s as fallback
        setTimeout(function() { location.reload(); }, 30000);
      ")]]]])))

;; --- HTTP handlers ---

(defn- parse-query-params [query-string]
  (if (and query-string (not (str/blank? query-string)))
    (into {}
          (mapv (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    [(keyword k) (or v "")]))
                (str/split query-string #"&")))
    {}))

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json; charset=utf-8"
             "Access-Control-Allow-Origin" "*"}
   :body (json/generate-string body)})

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- read-log-tail
  "Read the last n lines of the nrepl-bridge log."
  [n]
  (let [f (java.io.File. ".workbench/logs/nrepl-bridge.log")]
    (if (.exists f)
      (let [lines (str/split-lines (slurp f :encoding "UTF-8"))]
        (vec (take-last n lines)))
      [])))

(defn- handle-ws [req]
  (http/as-channel req
                   {:on-open (fn [ch]
                               (swap! !ws-clients conj ch)
                               (log/log! :info "WebSocket client connected"))
                    :on-close (fn [ch _status]
                                (swap! !ws-clients disj ch)
                                (log/log! :info "WebSocket client disconnected"))}))

(defn- route-request [req]
  (let [uri    (:uri req)
        method (:request-method req)
        params (parse-query-params (:query-string req))
        path   uri]
    (cond
      ;; WebSocket upgrade
      (= path "/ws")
      (handle-ws req)

      ;; Dashboard main page
      (and (= method :get) (= path "/"))
      (let [status-filter (:status params)
            evals (db/filtered-evals {:status status-filter :limit 50})
            stats (db/eval-stats)
            duration (db/duration-stats)
            pending (db/pending-approvals)]
        (html-response (page-html {:evals evals :stats stats :duration duration
                                   :pending pending :status-filter status-filter})))

      ;; JSON API: evals list
      (and (= method :get) (= path "/api/evals"))
      (let [status (:status params)
            limit  (or (some-> (:limit params) parse-long) 50)
            offset (or (some-> (:offset params) parse-long) 0)]
        (json-response (db/filtered-evals {:status status :limit limit :offset offset})))

      ;; JSON API: single eval
      (and (= method :get) (re-find #"^/api/evals/(\d+)$" path))
      (let [id (parse-long (second (re-find #"^/api/evals/(\d+)$" path)))]
        (if-let [row (db/eval-by-id id)]
          (json-response row)
          {:status 404 :body "Not found"}))

      ;; JSON API: stats
      (and (= method :get) (= path "/api/stats"))
      (json-response {:stats (db/eval-stats)
                      :duration (db/duration-stats)})

      ;; JSON API: logs
      (and (= method :get) (= path "/api/logs"))
      (json-response {:lines (read-log-tail 200)})

      ;; JSON API: dump file
      (and (= method :get) (re-find #"^/api/dumps/(\d+)$" path))
      (let [id (parse-long (second (re-find #"^/api/dumps/(\d+)$" path)))
            row (db/eval-by-id id)]
        (if (and row (:dump_path row))
          (let [f (java.io.File. (:dump_path row))]
            (if (.exists f)
              {:status 200
               :headers {"Content-Type" "text/plain; charset=utf-8"}
               :body (slurp f :encoding "UTF-8")}
              {:status 404 :body "Dump file not found"}))
          {:status 404 :body "No dump for this eval"}))

      ;; JSON API: pending approvals
      (and (= method :get) (= path "/api/pending"))
      (json-response (db/pending-approvals))

      ;; JSON API: decide (approve/reject)
      (and (= method :post) (re-find #"^/api/evals/(\d+)/decide$" path))
      (let [id (parse-long (second (re-find #"^/api/evals/(\d+)/decide$" path)))
            body (json/parse-string (slurp (:body req) :encoding "UTF-8") true)
            decision (:decision body)
            feedback (:feedback body)]
        (if (#{"approved" "rejected"} decision)
          (do
            (db/update-decision! id decision feedback)
            (broadcast! {:type "eval-update" :id id :decision decision})
            (log/log! :info (str "Decision for #" id ": " decision
                                 (when feedback (str " -- " feedback))))
            (json-response {:ok true :id id :decision decision}))
          {:status 400 :body "Invalid decision"}))

      ;; 404
      :else
      {:status 404 :body "Not found"})))

;; --- Server lifecycle ---

(defonce !server (atom nil))

(defn start!
  "Start the dashboard HTTP server on the given port."
  [port]
  (when-let [s @!server]
    (s))  ;; stop previous
  (let [s (http/run-server route-request {:port port :legacy-return-value? false})]
    (reset! !server s)
    (log/log! :info (str "Dashboard started at http://localhost:" port))
    port))

(defn stop!
  "Stop the dashboard HTTP server."
  []
  (when-let [s @!server]
    (http/server-stop! s)
    (reset! !server nil)
    (log/log! :info "Dashboard stopped")))
