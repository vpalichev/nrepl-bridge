;; Etaoin helper functions — auto-injected into JVM nREPL by the bridge.
;; These wrap common gaps in etaoin's API so LLMs and users stay in Clojure
;; instead of dropping to js-execute for routine DOM inspection.
;;
;; This file is NOT loaded by Babashka. Its contents are read as a string
;; and sent to the JVM nREPL via eval.

(ns etaoin-extras
  "Etaoin convenience wrappers for safe queries, DOM traversal, and bulk inspection."
  (:require [etaoin.api :as e]))

;; --- Safe queries (nil on miss, no exceptions) ---

(defn q1
  "Like e/query but returns nil instead of throwing when element is not found."
  ([driver selector]
   (first (e/query-all driver selector)))
  ([driver parent-selector child-selector]
   (first (e/query-all driver parent-selector child-selector))))

(defn exists?
  "Returns true if at least one element matches the selector."
  [driver selector]
  (boolean (seq (e/query-all driver selector))))

(defn absent?
  "Returns true if no elements match the selector."
  [driver selector]
  (empty? (e/query-all driver selector)))

;; --- Text extraction ---

(defn text
  "Get text of an element, or nil if not found."
  [driver selector]
  (when-let [el (q1 driver selector)]
    (e/get-element-text-el driver el)))

(defn texts
  "Get text of all matching elements as a vector."
  [driver selector]
  (mapv #(e/get-element-text-el driver %) (e/query-all driver selector)))

;; --- DOM traversal via XPath ---

(defn next-sibling
  "Find the next sibling element of the matched element. Returns nil if none."
  [driver selector]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (first (e/query-all driver el {:xpath "following-sibling::*[1]"}))))

(defn prev-sibling
  "Find the previous sibling element of the matched element. Returns nil if none."
  [driver selector]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (first (e/query-all driver el {:xpath "preceding-sibling::*[1]"}))))

(defn parent
  "Find the parent element of the matched element. Returns nil if none."
  [driver selector]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (first (e/query-all driver el {:xpath ".."}))))

;; --- Computed styles (JS unavoidable, but wrapped cleanly) ---

(defn computed-style
  "Get a computed CSS style property of an element. Returns the value string."
  [driver selector prop]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (e/js-execute driver
                  "return getComputedStyle(arguments[0]).getPropertyValue(arguments[1])"
                  el (name prop))))

(defn visible?
  "Check if an element is visible (display is not 'none', visibility is not 'hidden')."
  [driver selector]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (let [display (e/js-execute driver "return getComputedStyle(arguments[0]).display" el)
          visibility (e/js-execute driver "return getComputedStyle(arguments[0]).visibility" el)]
      (and (not= display "none") (not= visibility "hidden")))))

;; --- Bulk inspection (single round-trip) ---

(defn inspect
  "Extract multiple properties/attributes from an element in a single JS round-trip.
   Props is a vector of strings — each is tried as a JS property first, then as
   an HTML attribute. Returns a map of prop->value.

   Example: (inspect driver {:id \"btn\"} [\"textContent\" \"disabled\" \"data-id\"])"
  [driver selector props]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (let [result (e/js-execute driver
                               (str "var e=arguments[0], ps=" (pr-str props) ", r={};"
                                    "ps.forEach(function(p){"
                                    "  var v = e[p];"
                                    "  if(v===undefined||v===null) v=e.getAttribute(p);"
                                    "  r[p] = (v===null||v===undefined) ? null : String(v);"
                                    "});"
                                    "return JSON.stringify(r);")
                               el)]
      (when result
        (clojure.data.json/read-str result)))))

(defn inner-html
  "Get innerHTML of an element."
  [driver selector]
  (when-let [el (if (string? selector) (q1 driver {:css selector}) (q1 driver selector))]
    (e/js-execute driver "return arguments[0].innerHTML" el)))
