(ns nextjournal.garden-cli.util
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [taoensso.timbre :as log]))

;; Change default client for the whole application:
;; Needed for TLS connection to runners
(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Handling `:error` values

(defmacro if-ok-let
  "Similar to `if-let` but the 1st branch is selected if the bound expression
  doesn't contain a truthy :error key"
  [binding-form ok-branch error-branch]
  (when-not (= 2 (count binding-form))
    (throw (java.lang.IllegalArgumentException. "if-ok-let only supports 1 binding pair")))
  `(let [v# ~(second binding-form)
         ~(first binding-form) v#] ;; bind separately to support destructuring
     (if (:error v#)
       ~error-branch
       ~ok-branch)))

(defmacro when-ok [x & body]
  `(when-not (:error ~x)
     ~@body))

(defmacro when-ok-let
  "Similar to `when-let` but only evaluates the body when the bound expression
  isn't an error map (any map value containing an `:error` key)"
  [binding-form & body]
  (when-not (= 2 (count binding-form))
    (throw (java.lang.IllegalArgumentException. "when-ok-let only supports 1 binding pair")))
  `(let [v# ~(second binding-form)]
     (when-ok v#
              (let [~(first binding-form) v#]
                ~@body))))

(defmacro ok->
  "Like `some->` but for `:error` keys instead of `nil` values:
  When expr does not contain an `:error` key, threads it into the first
  form (via ->), and when that result does not contain an `:error` key, through
  the next etc"
  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(if (:error ~g)
                                 ~g
                                 (-> ~g ~step)))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

(defmacro ok->>
  "Like `some->>` but for `:error` keys instead of `nil` values.
  When expr does not contain an `:error` key, threads it into the
  first form (via ->>), and when that result does not contain an
  `:error` key, through the next etc"
  [expr & forms]
  (let [g (gensym)
        steps (map (fn [step] `(if (:error ~g)
                                 ~g
                                 (->> ~g ~step)))
                   forms)]
    `(let [~g ~expr
           ~@(interleave (repeat g) (butlast steps))]
       ~(if (empty? steps)
          g
          (last steps)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn retry
  ([f]
   (retry f nil))
  ([f {:keys [max-retries timeout]
       :or {max-retries 20
            timeout 50}}]
   (loop [retries 0]
     (if (< retries max-retries)
       (when-not (:success (try (f) (catch Exception e {:error e})))
         (Thread/sleep timeout)
         (recur (inc retries)))
       (throw (ex-info "retry failed" {}))))))

(defn wait-for-server [url]
  (retry
   (fn [] {:success (= 200 (:status @(client/get url)))})
   {:max-retries 1000
    :timeout 2000}))
