(ns nextjournal.start-command
  (:require [clojure.string :as str]))

(defn start-command [{:as opts
                      :keys [skip-inject-nrepl garden-alias sdeps]
                      :or {sdeps {}}}]
  (let [sdeps (cond-> sdeps
                (not skip-inject-nrepl) (merge {:deps {'io.github.nextjournal/garden-nrepl {:git/sha "d64532bf7c16565b0dfc825bc27eafdb453c1a61"}}
                                                :aliases {:nextjournal/garden-nrepl {:exec-fn 'nextjournal.garden-nrepl/start!}}}))]
    (filterv some?
             ["clojure"
              "-Srepro"
              "-Sdeps" (pr-str sdeps)
              "-J-Dclojure.main.report=stdout"
              (when-some [extra-aliases (get garden-alias :nextjournal.garden/aliases)]
                (when-not (every? keyword? extra-aliases) (throw (ex-info "`:nextjournal.garden/aliases` must be a vector of keywords" opts)))
                (str "-A" (str/join extra-aliases)))
              (if (not skip-inject-nrepl)
                "-X:nextjournal/garden:nextjournal/garden-nrepl"
                "-X:nextjournal/garden")
              ":host" "\"0.0.0.0\"" ":port" "7777"])))
