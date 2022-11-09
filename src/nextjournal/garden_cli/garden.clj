(ns nextjournal.garden-cli.garden
  (:require [babashka.process :as p]
            [clojure.java.browse :as browse]))


(defn build [{:keys [env repo branch update]
              :or {update true}}]
  (let [host (case env
               :production "github.clerk.garden"
               :staging "github.staging.clerk.garden")
        url (if branch
              (format "https://%s/%s/tree/%s" host repo branch)
              (format "https://%s/%s" host repo))
        url (if update
              (str url "?update=1")
              url)]
    (browse/browse-url url)))
