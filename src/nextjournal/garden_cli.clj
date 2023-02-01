(ns nextjournal.garden-cli
  (:require [babashka.cli :as cli]
            [nextjournal.garden-cli.domains :as domains]
            [nextjournal.garden-cli.garden :as garden]
            [nextjournal.garden-cli.git :as git]
            [clojure.string :as str]))

(def spec
  {:domain {:desc "domain"}
   :repo {:desc "repo"}
   :branch {:desc "branch"}
   :garden-url {:desc "An url pointing to a repo on clerk.garden"}
   :env {:desc "environment (production or staging)"
         :coerce :keyword
         :default :production}})

(defn wrap-with-error-reporting [f]
  (fn [x] (when-let [error (:error (f (:opts x)))]
            (binding [*out* *err*]
              (println error)))))

(def usage (str/trim "
garden build <repo>

garden domains list
garden domains add <domain> --garden-url <url>
garden domains remove <domain>
garden domains update <domain> [--branch <branch>]
"))

(defn add [{:as opts :keys [domain garden-url]}]
  (if garden-url
    (domains/add opts)
    (let [garden-url (git/find-garden-url-from-current-repo)]
      (println "Inferred target garden-url from current repo:" garden-url)
      (domains/add (assoc opts :garden-url garden-url)))))

(defn -main [& _args]
  (cli/dispatch
   [{:cmds ["domains" "list"] :fn #(doseq [{:keys [domain garden-url]} (domains/list (:opts %))]
                                     (println domain "->" garden-url))}
    {:cmds ["domains" "add"] :fn (wrap-with-error-reporting add) :args->opts [:domain :garden-url]}
    {:cmds ["domains" "remove"] :fn (wrap-with-error-reporting domains/remove) :args->opts [:domain]}
    {:cmds ["domains" "update"] :fn (wrap-with-error-reporting domains/update-domain) :args->opts [:domain]}

    {:cmds ["build"] :fn #(garden/build (:opts %)) :args->opts [:repo]}

    {:cmds [] :fn #(println (str/join "\n" ["command not found:"
                                            (str/join " " (:cmds %))
                                            ""
                                            "usage:"
                                            usage]))}]
   *command-line-args*
   {:spec spec
    :exec-args {:deps-file "deps.edn"}})
  nil)

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
