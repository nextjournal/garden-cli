(ns nextjournal.start-command
  (:require [clojure.string :as str]))

(defn start-command [{:as opts
                      :keys [skip-inject-nrepl garden-alias sdeps]
                      :or {sdeps {}}}]
  (let [sdeps (cond-> sdeps
                (not skip-inject-nrepl) (merge {:deps {'io.github.nextjournal/garden-nrepl {:git/sha "911ca60148f893e2791287741f9fd97b852ea702"}}
                                                :aliases {:nextjournal/garden-nrepl {:exec-fn 'nextjournal.garden-nrepl/start!}}}))]
    (filterv some?
             ["clojure"
              "-Sdeps" (pr-str sdeps)
              "-J-Dclojure.main.report=stdout"
              (when-some [extra-aliases (get garden-alias :nextjournal.garden/aliases)]
                (when-not (every? keyword? extra-aliases) (throw (ex-info "`:nextjournal.garden/aliases` must be a vector of keywords" opts)))
                (str "-A" (str/join extra-aliases)))
              (if (not skip-inject-nrepl)
                "-X:nextjournal/garden:nextjournal/garden-nrepl"
                "-X:nextjournal/garden")
              ":host" "\"0.0.0.0\"" ":port" "7777"])))
