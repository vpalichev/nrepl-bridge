(ns nrepl-bridge.paren-repair
  "Paren repair for eval forms using edamame.
   Detects syntax errors, attempts to fix missing/mismatched delimiters.
   No cljfmt (not available in Babashka) -- eval forms don't need formatting."
  (:require [edamame.core :as edamame]
            [clojure.string :as str]
            [nrepl-bridge.logging :as log]
            [nrepl-bridge.nrepl-client :as nrepl-client]))

(def ^:private delimiter-pairs
  {\( \) \[ \] \{ \}})

(def ^:private closing-delimiters
  (set (vals delimiter-pairs)))

(defn check-syntax
  "Parse with edamame. Returns {:ok? true} or {:ok? false :error msg :type type}.
   :type is :eof (missing closer), :mismatch, or :other."
  [form-str]
  (try
    (edamame/parse-string-all form-str {:all true})
    {:ok? true}
    (catch Exception e
      (let [msg (.getMessage e)]
        {:ok? false
         :error msg
         :type (cond
                 (or (str/includes? msg "EOF")
                     (str/includes? msg "Unexpected EOF"))
                 :eof

                 (str/includes? msg "Unmatched")
                 :mismatch

                 (str/includes? msg "Expected")
                 :mismatch

                 :else :other)}))))

(defn- infer-missing-closers
  "Walk the form string tracking open delimiters. Return string of missing closers."
  [form-str]
  (let [in-string? (volatile! false)
        escape?    (volatile! false)
        stack      (volatile! [])]
    (doseq [ch form-str]
      (cond
        @escape?
        (vreset! escape? false)

        (= ch \\)
        (when @in-string? (vreset! escape? true))

        (= ch \")
        (vswap! in-string? not)

        @in-string?
        nil

        (contains? delimiter-pairs ch)
        (vswap! stack conj ch)

        (contains? closing-delimiters ch)
        (when (seq @stack)
          (vswap! stack pop))

        :else nil))
    (apply str (reverse (mapv delimiter-pairs @stack)))))

(defn attempt-repair
  "Try to fix the form by appending missing closers.
   Returns {:repaired form-str} or {:unfixable error-msg}."
  [form-str error-type]
  (if (= error-type :other)
    {:unfixable "Cannot repair: not a delimiter error"}
    (let [closers (infer-missing-closers form-str)]
      (if (empty? closers)
        {:unfixable "Cannot infer missing delimiters"}
        (let [candidate (str form-str closers)
              check     (check-syntax candidate)]
          (if (:ok? check)
            (do
              (log/log! :info (str "Paren repair: appended '" closers "'"))
              {:repaired candidate})
            ;; If simple append didn't work, try replacing last wrong closer
            {:unfixable (str "Repair attempt failed: " (:error check))}))))))

(defn process-form
  "Full preprocessing pipeline: strip fences, check syntax, attempt repair.
   Returns {:form str :original str-or-nil :error str-or-nil}."
  [raw-form]
  (let [stripped (nrepl-client/strip-markdown-fences raw-form)
        trimmed  (str/trim stripped)
        check    (check-syntax trimmed)]
    (if (:ok? check)
      {:form trimmed :original nil :error nil}
      ;; Attempt repair
      (let [repair (attempt-repair trimmed (:type check))]
        (if (:repaired repair)
          {:form (:repaired repair) :original trimmed :error nil}
          ;; Unfixable
          {:form nil :original trimmed :error (:error check)})))))
