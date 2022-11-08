(ns nextjournal.garden-cli
  (:require [babashka.cli :as cli]
            [nextjournal.garden-cli.domains :as domains]
            [nextjournal.garden-cli.garden :as garden]
            [clojure.string :as str]))

(def spec
  {:domain {:desc "domain"}
   :repo {:desc "repo"}
   :branch {:desc "branch"}
   :garden-url {:desc "An url pointing to a repo on clerk.garden"}
   :env {:desc "environment (production or staging)"
         :coerce :keyword
         :default :staging}})

(defn wrap-with-error-reporting [f]
  (fn [x] (when-let [error (:error (f (:opts x)))]
            (binding [*out* *err*]
              (println error)))))

(defn -main [& _args]
  (cli/dispatch
   [{:cmds ["domains" "add"] :fn (wrap-with-error-reporting domains/add) :args->opts [:domain]}
    {:cmds ["domains" "update"] :fn (wrap-with-error-reporting domains/update-domain) :args->opts [:domain]}
    {:cmds ["domains" "remove"] :fn (wrap-with-error-reporting domains/remove) :args->opts [:domain]}
    {:cmds ["domains" "list"] :fn #(doseq [{:keys [domain garden-url]} (domains/list (:opts %))]
                                     (println domain "->" garden-url))}
    {:cmds ["build"] :fn #(garden/build (:opts %)) :args->opts [:repo]}
    {:cmds [] :fn #(println "command not found:" (str/join " " (:cmds %)))}]
   *command-line-args*
   {:spec spec
    :exec-args {:deps-file "deps.edn"}})
  nil)

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
