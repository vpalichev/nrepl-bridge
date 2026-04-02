;; Minimal stub — exists only so shadow-cljs has a :app build target.
;; Downstream projects replace this with their actual app code.
(ns app.core)

(defn ^:dev/after-load after-load [])

(defn init []
  (let [el (js/document.getElementById "app")]
    (set! (.-innerHTML el)
          "<h2 style='font-family:monospace;color:#3b82f6;padding:24px'>nrepl-bridge template<br><small style='color:#888'>shadow-cljs is running. Replace this stub with your app.</small></h2>")))
