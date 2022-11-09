(ns nextjournal.garden-cli.domains
  (:require [clojure.string :as str]
            [org.httpkit.client :as http]
            [babashka.process :as p]
            [nextjournal.garden-cli.caddy :as caddy]
            [nextjournal.garden-cli.util :as util]))

(defn- resolve [domain]
  (-> (p/sh ["dig" "+short" domain])
      :out
      str/trim))

(defn- check-domain-dns [{:as opts :keys [domain env]}]
  (let [server-domain (case env
                        :production "github.clerk.garden"
                        :staging "github.staging.clerk.garden")
        server-ip (resolve server-domain)
        domain-ip (resolve domain)]
    (if (= server-ip domain-ip)
      opts
      {:error (format "domain %s should point to %s but points to %s" domain domain-ip server-ip)})))

(defn- routes-endpoint [{:keys [domain env]}]
  (let [generic-routes-endpoint "/config/apps/http/servers/greenhouse/routes"
        greenhouse-routes-endpoint "/id/greenhouse-routes/routes"]
    (if (str/ends-with? domain (case env
                                 :staging ".cloud.staging.clerk.garden"
                                 :production ".cloud.clerk.garden"))
      greenhouse-routes-endpoint
      generic-routes-endpoint)))

(defn- strip-prefix [prefix s]
  (if (str/starts-with? s prefix)
    (str/replace-first s prefix "")
    s))

(defn add [{:as opts :keys [domain garden-url]}]
  (let [garden-url (->> garden-url
                        (strip-prefix "https://github.clerk.garden/")
                        (strip-prefix "/"))]
    (util/if-ok-let [r (check-domain-dns opts)]
                    (if (= 404 (:status (caddy/get opts (format "/id/%s" domain))))
                      (util/if-ok-let [r (caddy/post! opts
                                                      (routes-endpoint opts)
                                                      {:body {"@id" domain
                                                              :match [{:host [domain]}]
                                                              :handle [{:handler "file_server"
                                                                        :root (format "/var/lib/garden/notebooks/%s" garden-url)}]}})]
                                      opts
                                      r)
                      {:error "Attempt to add a domain for which there already is a mapping"})
                    r)))

(comment
  (add {:domain "clerk.vision" :garden-url "nextjournal/clerk-website/commit/23ffaed466488747dcdbccaada9d4e10a0964368/"})
  (add {:domain "book.clerk.vision" :garden-url "https://github.clerk.garden/nextjournal/book-of-clerk/commit/70d0459fbe941e689e0c2e7df0afc887eaf5900b/"})
  (add {:domain "foo.cloud.clerk.garden" :garden-url "https://github.clerk.garden/nextjournal/book-of-clerk/commit/70d0459fbe941e689e0c2e7df0afc887eaf5900b/"}))

(defn list [opts]
  (->> (concat (caddy/get! opts "/id/greenhouse-routes/routes")
               (caddy/get! opts "/config/apps/http/servers/greenhouse/routes"))
       (filter #(and (contains? % (keyword "@id"))
                     (= "file_server" (get-in % [:handle 0 :handler]))))
       (map (fn [x] {:domain (get x (keyword "@id"))
                     :garden-url (str/replace-first (get-in x [:handle 0 :root]) #"/var/lib/garden/notebooks" "https://github.clerk.garden")}))))

(comment
  (list {:env :staging})
  (list {:env :production}))

(defn remove [{:as opts :keys [domain]}]
  (util/if-ok-let [r (util/ok-> opts
                                (check-domain-dns)
                                (caddy/delete! (format "/id/%s" domain)))]
                  opts
                  r))

(defn update-domain [{:as opts :keys [domain branch]}]
  (util/if-ok-let [r (check-domain-dns opts)]
                  (util/if-ok-let
                   [old-target (util/ok->> (caddy/get! opts (format "/id/%s" domain))
                                           :handle
                                           first
                                           :root)]
                   (let [[_ repo] (re-matches #"/var/lib/garden/notebooks/(.+)/commit/.*" old-target)
                         url (if branch
                               (format "https://github.clerk.garden/%s/tree/%s?update=1" repo branch)
                               (format "https://github.clerk.garden/%s?update=1" repo))
                         opts (if (some? repo)
                                (if-let [url (some->> @(http/get url {:follow-redirects false})
                                                      :headers
                                                      :location
                                                      (str "https://github.clerk.garden"))]
                                  (assoc opts :garden-url url)
                                  {:error "Failed to get newest revision for branch"})
                                {:error "Domain does not point to a garden repo"})]
                     (util/ok-> opts
                                (remove)
                                (add)))
                   old-target)
                  r))

(comment
  (update-domain {:env :staging :domain "clerk.vision" :branch "main"})
  (update-domain {:env :production :domain "foo.cloud.clerk.garden" :branch "main"}))

(defn domain-redirect [{:as opts :keys [from to]}]
  (util/ok-> opts
             (check-domain-dns)
             (caddy/post! (routes-endpoint (assoc opts :domain from))
                          {:body {"@id" (format "%s->%s" from to)
                                  :match [{:host [from]}]
                                  :handle [{:handler "static_response"
                                            :headers {"Location" [(format "https://%s{http.request.uri}" to)]}
                                            :status_code 302}]}})))

(comment
  (domain-redirect {:env :staging :from "clerk.garden" :to "github.clerk.garden"}))
