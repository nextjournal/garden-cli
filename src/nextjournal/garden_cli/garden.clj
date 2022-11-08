(ns nextjournal.garden-cli.garden
  (:require [babashka.process :as p]))

(defn- open [url]
  (try
    (p/sh ["open" url])
    (catch Exception _
      (try
        (p/sh ["xdg-open" url])
        (catch Exception _
          (println "open" url))))))

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
    (open url)))
