(ns nextjournal.garden-cli.garden
  (:require [nextjournal.garden-cli.util :as util]
            [clojure.java.browse :as browse]
            [java-http-clj.websocket :as ws]
            [babashka.curl :as http]
            [clojure.string :as str]))

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
                                                (flush)
                                                (when (= "✅ Build finished" (str/trim text))
                                                  (System/exit 0)))})
                                   {:success true}))
                     p))]
    (reduce (fn [a v] @v) nil promises)))

(defn- show-build-progress [url]
  (let [{:keys [status err body]} (http/get url)]
    (when (= 200 status)
      (stream-progress (parse-ws-urls body)))))

(defn format-query-params [params]
  (some->> params
           (map (fn [[k v]] (format "%s=%s" k v)))
           (str/join "&")
           (format "?%s")))

(defn- format-url [{:as opts :keys [env repo branch]}]
  (let [host (case env
               :production "https://github.clerk.garden"
               :staging "https://github.staging.clerk.garden"
               :dev "http://localhost:8001")
        query-params (format-query-params (select-keys opts [:update :rebuild]))]
    (if branch
      (format "%s/%s/tree/%s%s" host repo branch query-params)
      (format "%s/%s%s" host repo query-params))))

(defn build [{:as opts :keys [browse show-progress]}]
  (let [url (format-url opts)]
    (when browse
      (browse/browse-url url))
    (when show-progress
      (show-build-progress url))))

(comment
  (build {:env :dev
          :repo "sohalt/clerk-minimal"
          :browse false
          :update false
          :rebuild false
          :show-progress true}))
