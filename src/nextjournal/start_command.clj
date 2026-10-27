(ns nextjournal.start-command
  (:require [clojure.string :as str]))

(def garden-nrepl-sha "0cbcebd2577b5735bdea7bc094cf509753daafd1")

(defn create-sdeps [sdeps skip-inject-nrepl]
  (merge sdeps
         (when (not skip-inject-nrepl)
           {:deps {'io.github.nextjournal/garden-nrepl {:git/sha garden-nrepl-sha}}
            :aliases {:nextjournal/garden-nrepl {:exec-fn 'nextjournal.garden-nrepl/start!}}})))

(defn fetch-deps-command [{:as opts
                           :keys [skip-inject-nrepl garden-alias sdeps]}]
  (filterv some?
           ["clojure"
            "-P"
            "-Srepro"
            "-Sdeps" (pr-str (create-sdeps sdeps skip-inject-nrepl))
            "-J-Dclojure.main.report=stdout"
            (when-some [extra-aliases (get garden-alias :nextjournal.garden/aliases)]
              (when-not (every? keyword? extra-aliases) (throw (ex-info "`:nextjournal.garden/aliases` must be a vector of keywords" opts)))
              (str "-A" (str/join extra-aliases)))
            "-A:nextjournal/garden"]))

(defn start-command [{:as opts
                      :keys [skip-inject-nrepl garden-alias sdeps]
                      :or {sdeps {}}}]
  (filterv some?
           ["clojure"
            "-Srepro"
            "-Sdeps" (pr-str (create-sdeps sdeps skip-inject-nrepl))
            "-J-Xmx3G"
            "-J-Dclojure.main.report=stdout"
            (when-some [extra-aliases (get garden-alias :nextjournal.garden/aliases)]
              (when-not (every? keyword? extra-aliases) (throw (ex-info "`:nextjournal.garden/aliases` must be a vector of keywords" opts)))
              (str "-A" (str/join extra-aliases)))
            (if (not skip-inject-nrepl)
              "-X:nextjournal/garden:nextjournal/garden-nrepl"
              "-X:nextjournal/garden")
            ":host" "\"0.0.0.0\"" ":port" "7777"]))
