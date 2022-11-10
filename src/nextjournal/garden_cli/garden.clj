(ns nextjournal.garden-cli.garden
  (:require [nextjournal.garden-cli.util :as util]
            [clojure.java.browse :as browse]
            [java-http-clj.websocket :as ws]
            [babashka.curl :as http]))

(defn- parse-ws-urls [html]
  (into #{} (re-seq #"wss?://[^\"]+" html)))

(defn- stream-progress [ws-urls]
  (let [promises (for [ws-url ws-urls]
                   (let [p (promise)]
                     (util/retry (fn []
                                   (ws/build-websocket
                                    ws-url
                                    {:on-close (fn [ws status reason]
                                                 (deliver p nil))
                                     :on-error (fn [ws error]
                                                 (deliver p nil))
                                     :on-text (fn [ws text last?]
                                                (print text)
                                                (flush))})
                                   {:success true}))
                     p))]
    (reduce (fn [a v] @v) nil promises)))

(defn- show-build-progress [url]
  (let [{:keys [status err body]} (http/get url)]
    (when (= 200 status)
      (stream-progress (parse-ws-urls body)))))

(defn build [{:keys [env repo branch update browse show-progress]
              :or {update true
                   browse true
                   show-progress true}}]
  (let [host (case env
               :production "https://github.clerk.garden"
               :staging "https://github.staging.clerk.garden"
               :dev "http://localhost:8001")
        url (if branch
              (format "%s/%s/tree/%s" host repo branch)
              (format "%s/%s" host repo))
        url (if update
              (str url "?update=1")
              url)]
    (when browse
      (browse/browse-url url))
    (when show-progress
      (show-build-progress url))))
