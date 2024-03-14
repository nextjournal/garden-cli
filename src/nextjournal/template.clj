(ns nextjournal.template
  (:require [babashka.http-client :as http]
            [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Workaround for pmap + require which doesn't work well in bb - 2023-02-04

(def ^:private lock (Object.))

(defn- serialized-require
  [& args]
  (locking lock
    (apply require args)))

(defn req-resolve
  [sym]
  (if (qualified-symbol? sym)
    (or (resolve sym)
        (do (-> sym namespace symbol serialized-require)
            (resolve sym)))
    (throw (IllegalArgumentException. (str "Not a qualified symbol: " sym)))))

;; End workaround

;; adapted from babashka.neil.git

(def github-user (or (System/getenv "NEIL_GITHUB_USER")
                     (System/getenv "BABASHKA_NEIL_DEV_GITHUB_USER")))
(def github-token (or (System/getenv "NEIL_GITHUB_TOKEN")
                      (System/getenv "BABASHKA_NEIL_DEV_GITHUB_TOKEN")))

(defn curl-get-json [url]
  (let [response    (http/get url (merge {:throw false}
                                         (when (and github-user github-token)
                                           {:basic-auth [github-user github-token]})))
        parsed-body (-> response :body (cheshire/parse-string true))]
    (if (and (= 403 (:status response))
             (str/includes? url "api.github")
             (str/includes? (:message parsed-body) "rate limit"))
      (throw (ex-info "You've hit the GitHub rate-limit (60 reqs/hr).
  You can set the environment variables NEIL_GITHUB_USER to your GitHub user
  and NEIL_GITHUB_TOKEN to a GitHub API Token to increase the limit." {:error :github-rate-limit}))
      parsed-body)))

(defn default-branch [lib]
  (get (curl-get-json (format "https://api.github.com/repos/%s/%s"
                              (namespace lib) (name lib)))
       :default_branch))

(defn clean-github-lib [lib]
  (-> lib
      (str/replace "com.github." "")
      (str/replace "io.github." "")
      (symbol)))

(defn latest-github-sha [lib]
  (try (let [lib (clean-github-lib lib)
         branch (default-branch lib)]
     (get (curl-get-json (format "https://api.github.com/repos/%s/%s/commits/%s"
                                 (namespace lib) (name lib) branch))
          :sha))
       (catch clojure.lang.ExceptionInfo e
         (println (ex-message e)))))

(defn list-github-tags [lib]
  (let [lib (clean-github-lib lib)]
    (curl-get-json (format "https://api.github.com/repos/%s/%s/tags"
                           (namespace lib) (name lib)))))

(defn latest-github-tag [lib]
  (-> (list-github-tags lib)
      first))

(defn find-github-tag [lib tag]
  (->> (list-github-tags lib)
       (filter #(= (:name %) tag))
       first))

(defn- github-repo-http-url [lib]
  (str "https://github.com/" (clean-github-lib lib)))

(def github-repo-ssh-regex #"^git@github.com:([^/]+)/([^\.]+)\.git$")
(def github-repo-http-regex #"^https://github.com/([^/]+)/([^\.]+)(\.git)?$")

(defn- parse-git-url [git-url]
  (let [[[_ gh-user repo-name]] (or (re-seq github-repo-ssh-regex git-url)
                                    (re-seq github-repo-http-regex git-url))]
    (if (and gh-user repo-name)
      {:gh-user gh-user :repo-name repo-name}
      (throw (ex-info "Failed to parse :git/url" {:git/url git-url})))))

(defn- git-url->lib-sym [git-url]
  (when-let [{:keys [gh-user repo-name]} (parse-git-url git-url)]
    (symbol (str "io.github." gh-user) repo-name)))

(def lib-opts->template-deps-fn
  "A map to define valid CLI options for deps-new template deps.

  - Each key is a sequence of valid combinations of CLI opts.
  - Each value is a function which returns a tools.deps lib map."
  {[#{:local/root}]
   (fn [lib-sym lib-opts]
     {lib-sym (select-keys lib-opts [:local/root])})

   [#{} #{:git/url}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           tag (latest-github-tag (git-url->lib-sym url))]
       (if tag
         {lib-sym {:git/url url :git/tag (:name tag) :git/sha (-> tag :commit :sha)}}
         (let [sha (latest-github-sha (git-url->lib-sym url))]
           {lib-sym {:git/url url :git/sha sha}}))))

   [#{:git/tag} #{:git/url :git/tag}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           tag (:git/tag lib-opts)
           {:keys [commit]} (find-github-tag (git-url->lib-sym url) tag)]
       {lib-sym {:git/url url :git/tag tag :git/sha (:sha commit)}}))

   [#{:git/sha} #{:git/url :git/sha}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           sha (:git/sha lib-opts)]
       {lib-sym {:git/url url :git/sha sha}}))

   [#{:latest-sha} #{:git/url :latest-sha}]
   (fn [lib-sym lib-opts]
     (let [url (or (:git/url lib-opts) (github-repo-http-url lib-sym))
           sha (latest-github-sha (git-url->lib-sym url))]
       {lib-sym {:git/url url :git/sha sha}}))

   [#{:git/url :git/tag :git/sha}]
   (fn [lib-sym lib-opts]
     {lib-sym (select-keys lib-opts [:git/url :git/tag :git/sha])})})

(def valid-lib-opts
  "The set of all valid combinations of deps-new template deps opts."
  (into #{} cat (keys lib-opts->template-deps-fn)))

(defn- deps-new-cli-opts->lib-opts
  "Returns parsed deps-new template deps opts from raw CLI opts."
  [cli-opts]
  (-> cli-opts
      (set/rename-keys {:sha :git/sha})
      (select-keys (into #{} cat valid-lib-opts))))

(defn- invalid-lib-opts-error [provided-lib-opts]
  (ex-info (str "Provided invalid combination of CLI options for deps-new "
                "template deps.")
           {:provided-opts (set (keys provided-lib-opts))
            :valid-combinations valid-lib-opts}))

(defn- find-template-deps-fn
  "Returns a template-deps-fn given lib-opts parsed from raw CLI opts."
  [lib-opts]
  (some (fn [[k v]] (and (contains? (set k) (set (keys lib-opts))) v))
        lib-opts->template-deps-fn))

(defn- template-deps
  "Returns a tools.deps lib map for the given CLI opts."
  [template cli-opts]
  (let [lib-opts (deps-new-cli-opts->lib-opts cli-opts)
        lib-sym (edn/read-string template)
        template-deps-fn (find-template-deps-fn lib-opts)]
    (if-not template-deps-fn
      (throw (invalid-lib-opts-error lib-opts))
      (template-deps-fn lib-sym lib-opts))))

(def bb? (System/getProperty "babashka.version"))

(def create-opts-deny-list
  [:git/sha :git/url :latest-sha :local/root :sha])

(defn- cli-opts->create-opts
  "Returns options for org.corfield.new/create based on the cli-opts."
  [cli-opts]
  (apply dissoc cli-opts create-opts-deny-list))

(defn default-template []
  (edn/read-string (slurp (io/resource "default-template-coords.edn"))))

(defn- deps-new-plan
  "Returns a plan for calling org.corfield.new/create.

  :template-deps - These deps will be added with babashka.deps/add-deps before
                   calling the create function.

  :create-opts   - This map contains the options that will be passed to the
                   create function."
  [cli-opts]
  (let [create-opts (cli-opts->create-opts cli-opts)
        tpl-deps (try (template-deps (:template create-opts) cli-opts)
                      (catch Exception e
                        (if (= {:error :github-rate-limit} (ex-data e))
                          (binding [*out* *err*]
                            (println (ex-message e))
                            (println "Using default template:")
                            (prn (default-template))
                            (default-template))
                          (throw e))))]
    {:template-deps tpl-deps
     :create-opts create-opts}))

(defn- deps-new-set-classpath
  "Sets the java.class.path property.

  This is required by org.corfield.new/create. In Clojure it's set by default,
  but in Babashka it must be set explicitly."
  []
  (let [classpath ((req-resolve 'babashka.classpath/get-classpath))]
    (System/setProperty "java.class.path" classpath)))

(defn- deps-new-add-template-deps
  "Adds template deps at runtime."
  [template-deps]
  ((req-resolve 'babashka.deps/add-deps) {:deps template-deps}))

(defn run-deps-new [opts]
  (let [plan (deps-new-plan opts)
        {:keys [template-deps create-opts]} plan]
    (deps-new-add-template-deps template-deps)
    (when bb? (deps-new-set-classpath))
    ((req-resolve 'org.corfield.new/create) create-opts)))

(defn create [opts]
  (run-deps-new (merge {:target-dir "."
                        :overwrite true}
                       opts)))
