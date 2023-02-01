(ns nextjournal.garden-cli.git
  (:require [clojure.string :as str]
            [babashka.process :as p]
            [org.httpkit.client :as http]
            [org.httpkit.sni-client :as sni-client]))
;;
;; Change default client for the whole application:
;; Needed for TLS connection to runners
(alter-var-root #'org.httpkit.client/*default-client* (fn [_] sni-client/default-client))

(defn- tryp [cmd]
  (try (some-> @(p/sh cmd)
               :out
               str/trim)
       (catch Exception e
         nil)))

(defn- git-branch []
  (tryp ["git" "branch" "--show-current"]))

(defn- git-sha []
  (tryp ["git" "rev-parse" "HEAD"]))

(defn- git-remote [branch]
  (tryp ["git" "config" "--get" (format "branch.%s.remote" branch)]))

(defn- git-remote-url [remote]
  (tryp ["git" "config" "--get" (format "remote.%s.url" remote)]))

(defn- strip-suffix [s suffix]
  (if (str/ends-with? s suffix)
    (let [len (count s)
          suffix-len (count suffix)]
      (subs s 0 (- len suffix-len)))
    s))

(defn- repo-from-remote [remote-url]
  (let [[_ repo-ssh] (re-matches #"git@github\.com:([^/]+/[^/]+)" remote-url)
        [_ repo-http] (re-matches #"https?://github\.com/([^/]+/[^/]+).*" remote-url)]
    (or (some-> repo-ssh (strip-suffix ".git"))
        (some-> repo-http (strip-suffix ".git")))))

(defn find-garden-url-from-current-repo []
  (let [remote-url (some-> (git-branch)
                           (git-remote)
                           (git-remote-url))
        repo (repo-from-remote remote-url)
        sha (git-sha)]
    (when (and repo sha)
      (format "https://github.clerk.garden/%s/commit/%s" repo sha))))

(comment
  (find-garden-url-from-current-repo))
