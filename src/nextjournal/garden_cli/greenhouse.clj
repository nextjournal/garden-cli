(ns nextjournal.garden-cli.greenhouse
  (:require [nextjournal.garden-cli.ssh :as ssh]
            [babashka.curl :as curl]
            [cheshire.core :as json]))

(defn request
  ([opts path]
   (request opts path {}))
  ([opts path http-opts]
   (let [port 8000]
     (ssh/tunnel {:port port})
     (let [url (str "localhost:" port opts path)]
       (-> (curl/request (-> http-opts
                             (assoc :url url)
                             (update :body json/encode)))
           :body
           (json/parse-string keyword))))))

(defn get
  ([opts path]
   (get opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :get))))

(defn post
  ([opts path]
   (post opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :post))))

(defn list-vms [opts]
  (get opts "/vm"))

(defn start-vm
  ([opts]
   (start-vm opts nil))
  ([{:as opts :keys [flake]} flake-opts]
   (post opts "/vm/spawn" {:body (merge flake-opts {:flake flake})})))

(defn stop-vm [{:as opts :keys [vm-id]}]
  (post opts (format "/vm/%s/stop" vm-id)))
