(ns nextjournal.garden-cli.caddy
  (:require [nextjournal.garden-cli.ssh :as ssh]
            [babashka.curl :as http]
            [cheshire.core :as json]))

(defn request
  ([opts path]
   (request opts path {}))
  ([opts path http-opts]
   (let [port 2019]
     (ssh/tunnel (assoc opts :port port))
     (let [url (str "http://localhost:" port path)
           http-opts (-> http-opts
                         (assoc :url url)
                         (update :headers merge {"Content-Type" "application/json"})
                         (update :body json/encode))
           {:as resp :keys [headers]} (try (http/request http-opts)
                                           (catch clojure.lang.ExceptionInfo e
                                             (ex-data e)))]
       (cond-> resp
         (= (clojure.core/get headers "content-type") "application/json")
         (update :body json/parse-string keyword))))))

(comment
  (request {:env :staging} "/config/" {:method :get})
  (request {:env :staging} "/id/greenhouse-routes/routes" {:method :post
                                                           :body {"@id" "foo"}})
  (request {:env :staging} "/id/greenhouse-routes/routes" {:method :get})
  (request {:env :staging} "/id/foo" {:method :get})
  (request {:env :staging} "/id/foo" {:method :delete}))

(defn request!
  ([opts path]
   (request! opts path {}))
  ([opts path http-opts]
   (let [{:as resp :keys [status body err]} (request opts path http-opts)]
     (if (= 200 status)
       body
       {:error {:msg "request unsuccessful"
                :req {:path path
                      :http-opts http-opts}
                :resp resp}}))))

(defn get
  ([opts path]
   (get opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :get))))

(defn delete
  ([opts path]
   (delete opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :delete))))

(defn post
  ([opts path]
   (post opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :post))))

(defn put
  ([opts path]
   (put opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :put))))

(defn patch
  ([opts path]
   (patch opts path {}))
  ([opts path http-opts]
   (request opts path (assoc http-opts :method :patch))))

(defn get!
  ([opts path]
   (get! opts path {}))
  ([opts path http-opts]
   (request! opts path (assoc http-opts :method :get))))

(defn delete!
  ([opts path]
   (delete! opts path {}))
  ([opts path http-opts]
   (request! opts path (assoc http-opts :method :delete))))

(defn post!
  ([opts path]
   (post! opts path {}))
  ([opts path http-opts]
   (request! opts path (assoc http-opts :method :post))))

(defn put!
  ([opts path]
   (put! opts path {}))
  ([opts path http-opts]
   (request! opts path (assoc http-opts :method :put))))

(defn patch!
  ([opts path]
   (patch! opts path {}))
  ([opts path http-opts]
   (request! opts path (assoc http-opts :method :patch))))

(defn show-config [opts]
  (get! opts "/config/"))

(defn load-config [opts config]
  (post! opts "/load" {:body config}))

(comment
  (def c (show-config {:env :staging}))
  (load-config {:env :staging} c))
