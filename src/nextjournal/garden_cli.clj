#!/usr/bin/env bb
;; -*- mode: clojure -*-
;; launch nrepl from project `bb --config <path-to-garden-cli>/bb.edn nrepl-server `
(ns nextjournal.garden-cli
  (:refer-clojure :exclude [pr-str])
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell sh]]
            [babashka.http-client :as http]
            [clojure.core :as core]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [nextjournal.edit-distance :as edit-distance]))

(def version (let [semver (try (str/trim (slurp (io/resource "VERSION")))
                               (catch Exception e nil))
                   gitrev (try (or (let [{:keys [exit out]} (sh ["git" "rev-parse" "--short" "HEAD"] {:dir (str (fs/parent *file*))
                                                                                                      :out :string})]
                                     (when (zero? exit)
                                       (str/trim out))
                                     (System/getProperty "nextjournal.garden.rev")))
                               (catch Exception e nil))
                   version (str "v" semver (when gitrev (str "-" gitrev)))]
               version))

(defn print-version [_]
  (println version))

(declare help)

(defn garden-project? [] (fs/exists? "garden.edn"))

(defn pr-str [& xs]
  (binding [*print-namespace-maps* false]
    (apply core/pr-str xs)))

(defn print-error [s]
  (binding [*out* *err*]
    (println s))
  {:exit-code 1})

(def arboretum-ssh-host
  (or (System/getenv "ARBORETUM_SSH_DEST")
      "arboretum@ssh.application.garden"))

(defn git-remote-url [project] (str "ssh://" arboretum-ssh-host "/" project ".git"))

(defn ssh-args
  ([] (ssh-args nil nil))
  ([command] (ssh-args command nil))
  ([command body]
   (let [[host port] (clojure.string/split arboretum-ssh-host #":")]
     (concat (when port ["-p" port])
             ;FIXME actually check host key
             (cond-> ["-n" "-o" "StrictHostKeyChecking=no" "-o" "ControlMaster=no" "-o" "ControlPath=none" host]
               command (conj command)
               body (conj (pr-str body)))))))

(defn read-config []
  (try
    (edn/read-string (slurp "garden.edn"))
    (catch Throwable _ {})))
#_(read-config)

(defn update-config! [f & args] (spit "garden.edn" (str (pr-str (apply f (read-config) args)) "\n")))
#_(update-config! assoc :v "1.2.1")

(defn project-name [] (:project (read-config)))

(defn call-api [{:as body :keys [as]}]
  (cond-> (apply shell {:out (if (= :stream as) :inherit :string)} "ssh" (ssh-args "api" (assoc body :version version)))
    (not= :stream as)
    (-> :out edn/read-string)))

#_(call-api {:command "create"})
#_(call-api {:command "create" :project "hello"})
#_(call-api {:command "info" :project "toboga"})
#_(call-api {:command "list"})

(defn reset []
  (fs/delete-if-exists "garden.edn"))

(defn template [target-dir]
  (fs/copy-tree (fs/path (io/resource "project-template")) target-dir {:replace-existing true}))

(defn project-dir []
  (fs/cwd))

(defn git-repo? [target-dir]
  (try (= 0 (:exit (sh ["git" "status"] {:dir target-dir})))
       (catch Exception e false)))

(defn path-from-git-root-parent []
  (not-empty (str/trim (str (:out (sh "git rev-parse --show-prefix"))))))

#_(path-from-git-root-parent)

(defn empty-git-repo? [target-dir]
  (pos? (:exit (sh ["git" "rev-parse" "HEAD"] {:dir target-dir}))))

(defn setup-git-remote! [remote-url]
  (sh "git remote rm garden")
  (sh "git remote add garden" remote-url))
#_(setup-git-remote! "git@github.com:zampino/foo")

(defn init [{:keys [opts]}]
  (let [target-dir (str (fs/cwd))]
    (when-not (git-repo? target-dir)
      (println "Initializing git repo")
      (sh ["git" "init"] {:dir target-dir}))
    (let [project-name (or (-> opts :project)
                           (when-not (:force opts) (project-name)))]
      (when (:force opts) (reset))
      (when (empty? (filter #(not= ".git" %) (map fs/file-name (fs/list-dir (project-dir)))))
        (template target-dir))
      (if (garden-project?)
        (print-error "It seems you have already initialized a Garden project in this repository. Use --force to overwrite.")

        ;; we might have cloned a repo tracking `garden.edn`: we validate the project name against the server anyway
        (let [{:keys [ok message id project]} (call-api (cond-> {:command "create"}
                                                          project-name (assoc :project project-name)))]
          (if ok
            (do
              (println message)
              (when-not (-> opts :project)
                (println "You can rename your project at any time via `garden rename <your-name>`."))
              (if (empty-git-repo? target-dir)
                (println (str "Create your first commit, then run `garden deploy`."))
                (println (str "Now you can run `garden deploy`.")))
              (setup-git-remote! (git-remote-url id))
              (update-config! assoc :project project))

            (print-error message)))))))

(defn run [_]
  (println "Starting application locally...")
  (let [http-port 7777
        url (str "http://localhost:" http-port)
        repl-port 6666
        storage-dir ".garden/storage"
        timeout-seconds 10]
    (doto (Thread. (fn [] (loop [time-left timeout-seconds]
                            (let [sleep-seconds 1]
                              (Thread/sleep (* sleep-seconds 1000))
                              (if (try (<= 200
                                           (:status (http/head url {:client (http/client {:follow-redirects :never})}))
                                           399)
                                       (catch Throwable _ false))
                                (println "Application ready on" url)
                                (if (>= time-left 0)
                                  (recur (- time-left sleep-seconds))
                                  (do
                                    (println (format "Application did not start after %s." timeout-seconds))
                                    (System/exit 1))))))))
      .start)
    (fs/create-dirs storage-dir)
    (sh ["clojure" "-X:nextjournal/garden"] {:extra-env {"GARDEN_STORAGE" storage-dir
                                                         "GARDEN_EMAIL_ADDRESS" "just-a-placeholder@example.com"}
                                             :out :inherit
                                             :err :inherit})))

(defn deploy [{:keys [opts]}]
  (let [{:keys [git-ref force]} opts
        {:keys [out exit]} (sh "git rev-parse" git-ref)]
    (if (pos? exit)
      (print-error (if (= git-ref "HEAD")
                 "You need commit before you can deploy."
                 (format "`%s` is not a valid git ref." git-ref)))
      (let [sha (str/trim out)
            branch (-> (sh "git symbolic-ref --short HEAD") :out str/trim)
            remote (-> (sh "git" "config" (str "branch." branch ".remote")) :out str/trim)
            remote-url (-> (sh "git" "remote" "get-url" remote) :out str/trim)
            {:keys [ok project message id git-rev]} (call-api (assoc opts :command "create"))]
        (if ok
          (let [_ (when (= :new ok) (println (str "A new project '" project "' has been created.")))
                _ (println "Pushing code to garden...")
                {:keys [out err exit]} (sh "git push --force" (git-remote-url id) (str git-ref ":___garden_deploy___"))]
            (if-not (zero? exit)
              (println (str "Cannot push to garden\n" out "\n" err "\n"))
              ;; this guesses
              (let [working-dir (path-from-git-root-parent)]
                (when-not working-dir
                  (sh "git update-ref refs/remotes/garden/main" sha))
                (if (and (= sha git-rev) (not force))
                  (println "Project is up-to-date. Use `--force` to deploy the same version again.")
                  (call-api (-> opts
                                (assoc :command "deploy" :commit sha :as :stream)
                                (cond->
                                  working-dir
                                  (assoc :working-dir working-dir)

                                  (seq remote-url)
                                  (assoc :remote-url remote-url))))))))
          (print-error message))))))

(defn sftp [_ctx]
  (let [{:keys [id]} (call-api {:command "info" :project (project-name)})
        [host port] (clojure.string/split arboretum-ssh-host #":")]
    (shell (concat ["sftp" (str "-o SetEnv SFTP_PROJECT=" id)]
                   (when port ["-P" port])
                   [host]))))

(defn rename [{:keys [opts]}]
  (if-not (garden-project?)
    (println "`rename` might only be called from inside a garden project. Run `garden init` to get started.")
    (if-some [new-project-name (:new-project-name opts)]
      (let [{:keys [ok message project]} (call-api (merge {:command "rename" :project (project-name)} opts))]
        (if ok
          (do (update-config! assoc :project project)
              (println "Project renamed successfully.")
              (println (str "Once deployed, your application will be available at: 'https://" new-project-name "." "live.clerk.garden'.")))
          (print-error message)))
      (print-error "You need to pass a `new-project-name` argument."))))

(def cols '[name status git-rev domains deployed-at deployed-by owner groups])
(def col-sep 2)
(def max-cell-lenght (apply max (map (comp count name) cols)))
(defn pad [entry max-length] (apply str (repeat (+ col-sep (- max-length (count (name entry)))) " ")))

;TODO disentangle formatting
(defn info [{:keys [opts]}]
  (let [{:as m :keys [ok message]} (call-api (assoc opts :command "info"))]
    (if ok
      (let [info-map (select-keys m (map keyword cols))]
        (doseq [[k v] info-map]
          (if (coll? v)
            (do (println (str (name k) ":" (pad k max-cell-lenght) (first v)))
                (doseq [v (rest v)]
                  (println (str (pad "" max-cell-lenght) " " v))))
            (when v (println (str (name k) ":" (pad k max-cell-lenght) v)))))
        info-map)
      (println message))))

(defn format-domains-oneline [domains]
  (when domains
    (let [c (count domains)
          domain (first domains)]
      (str domain (when (> c 1) (format " (%s more)" (dec c)))))))

(defn list-projects [_]
  (let [{:keys [ok message projects]} (call-api {:command "list"})]
    (if ok
      (do (pp/print-table (remove #{'owner 'groups} cols)
                          (->> projects
                               (map (fn [p] (update p :domains format-domains-oneline)))
                               (map #(update-keys % (comp symbol name)))))
          projects)
      (println message))))

(defn log [{:keys [opts]}]
  (call-api (assoc opts :command "log" :as :stream)))

(defn restart [{:keys [opts]}]
  (call-api (assoc opts :command "restart" :as :stream)))

(defn stop [m]
  (let [{:keys [ok message]} (call-api (merge {:command "stop"} (:opts m)))]
    (when-not ok (println message))))

(defn stop-all [_]
  (let [{:keys [ok message]} (call-api {:command "stop-all"})]
    (when-not ok (println message))))

(def domain-setup-message
  {:missing-a-record (fn [{:keys [ip]}]
                       (str "Please add an A-record with '" ip "' to your domain and try again. It might take some time for DNS changes to take effect."))
   :missing-txt-record (fn [{:keys [txt-record]}]
                         (str "Please add a TXT-record with '" txt-record "' to your domain and try again. It might take some time for DNS changes to take effect."))
   :missing-deployment (fn [_]
                         "You need to run `garden deploy` first.")})

(defn publish [{:as m :keys [opts]}]
  (let [{:keys [project domain] :or {project (project-name)}} opts]
    (if-not domain
      (do (println "Missing domain")
          (println)
          (help m))
      (let [{:as ret :keys [ok ip txt-record reason]} (call-api {:command "get-domain-verification-info"
                                                                 :project project
                                                                 :domain domain})]
        (if ok
          (do
            (println (str "Please configure DNS for '" domain "' with the following A record:"))
            (println ip)
            (println "and the following TXT record:")
            (println txt-record)
            (println "After you have added the records, press enter.")
            (read-line)
            (println "Checking configuration...")
            (Thread/sleep 1000)          ;wait a bit more for DNS changes
            (let [{:as ret :keys [ok reason message]} (call-api {:command "publish"
                                                                 :project project
                                                                 :domain domain})]
              (cond
                (and (not ok) reason)
                (println ((domain-setup-message reason) ret))
                (not ok)
                (println message)
                ok
                (do
                  (restart m)
                  (println (str "Done. Your project is available at https://" domain))))))
          (println ((domain-setup-message reason) ret)))))))

(defn delete [{:keys [opts]}]
  (let [{:keys [ok message name]} (call-api (assoc opts :command "info"))
        guard (fn [project-name]
                (println (str "Deleting a project will stop your current application and remove your data permanently. This cannot be undone!\n"
                              "If you do, your project's name will be available to anyone else again.\n"
                              "Please confirm by typing the project's name and pressing 'Enter':"))
                (= project-name (read-line)))]
    (if-not ok
      (println message)
      (do
        (println (str "Deleting '" name "'"))
        (if (or (:force opts) (guard name))
          (let [{:keys [ok message]} (call-api (assoc opts :command "delete"))]
            (if ok
              (println "Your project has been deleted.")
              (println message)))
          (print-error "That's not the project name."))))))

(defn free-port
  "Finds an free, unprivileged port.

  The port is free at the time of calling this function, but might be used afterwards. Beware of race conditions."
  []
  (let [s (doto (java.net.Socket.)
            (.bind (java.net.InetSocketAddress. "localhost" 0)))
        p (.getLocalPort s)]
    (.close s)
    p))

(defn tunnel [{:keys [opts]}]
  (let [{:keys [repl-port]} (call-api (merge {:command "info" :project (project-name)} opts))
        {:keys [port]} opts]
    (let [port (or port (free-port))
          old-port (try (slurp ".nrepl-port") (catch java.io.FileNotFoundException e nil))]
      (println (str "Forwarding port " port " to remote nrepl, use ^-C to quit."))
      (spit ".nrepl-port" port)
      (try
        (apply shell
               (concat ["ssh" "-N" "-L" (str port ":localhost:" repl-port)]
                       (ssh-args)
                       "tunnel"))
        (catch Throwable _
          (if old-port
            (spit ".nrepl-port" old-port)
            (fs/delete-if-exists ".nrepl-port"))
          (println "Tunnel closed"))))))

(defn add-secret [{:keys [opts]}]
  (if (and (not (:force opts))
           (some #{(:secret-name opts)}
                 (:secrets (call-api (assoc opts :command "list-secrets")))))
    (print-error (format "A secret with the same name already exist. Use `garden secrets add %s --force` to overwrite it."
                         (:secret-name opts)))
    (let [{:keys [ok message]}
          (call-api (assoc opts :command "add-secret"
                           :secret-value (do
                                           (println "Type your secret and press Enter:")
                                           (or
                                            ;;FIXME reenable when https://github.com/oracle/graal/issues/7567 is fixed
                                            #_(when-some [c (System/console)]
                                                (String. (.readPassword c)))
                                            (read-line)))))]
      (if ok
        (println "Secret added successfully. Note that users with access to this project will be able to use/see your secrets.")
        (println message)))))

(defn remove-secret [{:keys [opts]}]
  (let [{:keys [ok message]} (call-api (assoc opts :command "remove-secret"))]
    (if ok
      (println "Secret removed successfully")
      (println message))))

(defn list-secrets [{:keys [opts]}]
  (let [{:keys [ok secrets message]} (call-api (assoc opts :command "list-secrets"))]
    (if ok
      (do (doseq [s secrets] (println s)) secrets)
      (println message))))

;; ## Groups

(defn create-group [{:keys [opts]}]
  (let [{:as ret :keys [ok message]} (call-api (assoc opts :command "create-group"))]
    (println message)
    ret))

(defn list-groups [{:keys [opts]}]
  (let [{:as ret :keys [ok message groups]} (call-api (assoc opts :command "list-groups"))]
    (if-not ok
      (println message)
      (doseq [g groups] (println g)))
    ret))

(defn add-group-member [{:keys [opts]}]
  (let [{:as ret :keys [ok message]} (call-api (assoc opts :command "add-group-member"))]
    (println message)
    ret))

(defn remove-group-member [{:keys [opts]}]
  (let [{:as ret :keys [ok message]} (call-api (assoc opts :command "remove-group-member"))]
    (println message)
    ret))

(defn add-project-to-group [{:keys [opts]}]
  (let [{:as ret :keys [ok message]} (call-api (assoc opts :command "add-project-to-group"))]
    (println message)
    ret))

(defn remove-project-from-group [{:keys [opts]}]
  (let [{:as ret :keys [ok message]} (call-api (assoc opts :command "remove-project-from-group"))]
    (println message)
    ret))

(def default-spec
  {:project {:ref "<project-name>"
             :require true
             :message "Command '%s' needs either a --project option or has to be run inside an application.garden project."
             :desc "The project name"
             :default-desc "`:project` from `garden.edn`"}
   :quiet {:coerce :boolean
           :alias :q
           :desc "Do not print output"}
   :output-format (let [valid-formats #{:edn :json}]
                    {:ref "<output-format>"
                     :coerce :keyword
                     :validate valid-formats
                     :desc (str "Print result in a machine readable format. One of: " (str/join ", " (map name valid-formats)))})})

(def secrets-spec
  {:secret-name {:ref "<secret-name>"
                 :require true
                 :desc "The secret name"
                 :coerce :string
                 :validate {:pred #(re-matches #"[a-zA-Z_]+" %)
                            :ex-msg (constantly "secret names must only contain alphanumeric characters or underscores")}}})

(def cmd-tree
  {"stop"
   {:fn stop,
    :spec default-spec,
    :help "Stop the application in your garden"}
   "run"
   {:fn run,
    :spec default-spec,
    :help "Run the application locally"},
   "deploy"
   {:fn deploy,
    :help "Deploy a project to application.garden",
    :spec
    (->
     default-spec
     (dissoc :output-format)
     (assoc-in
      [:project :desc]
      "The project to be deployed. A new project will be created if it does not exist yet")
     (assoc
      :git-ref
      {:ref "<git-ref>",
       :default "HEAD",
       :desc "The git branch, commit, tag, etc. to be deployed"}
      :force
      {:desc
       "Force a deployment, even when the code has not changed since the last deploy"}
      :deploy-strategy
      {:ref "<mode>",
       :coerce :keyword,
       :default :zero-downtime,
       :validate #{:restart :zero-downtime},
       :desc
       "How to deploy a new version: stop old instance before starting new instance (restart), stop old instance after new instance is ready (zero-downtime)"}))},
   "rename"
   {:fn rename,
    :args->opts [:new-project-name],
    :spec
    (->
     default-spec
     (assoc
      :new-project-name
      {:ref "<new-name>", :require true, :desc "New project name"})
     (assoc-in [:project :desc] "Old project name")),
    :help "Rename a project"},
   "list"
   {:fn list-projects,
    :spec (dissoc default-spec :project),
    :help "List your projects and their status"},
   "tunnel"
   {:args->opts [:port],
    :fn tunnel,
    :help "Open a tunnel to an nREPL server in the application",
    :spec
    (assoc
     default-spec
     :port
     {:ref "<port>",
      :require false,
      :desc "The local TCP port to tunnel to the remote nREPL port"})},
   "delete"
   {:fn delete,
    :spec
    (assoc
     default-spec
     :force
     {:coerce :boolean, :desc "Do not ask for confirmation"}),
    :help
    "Stop the application and remove all project data from your garden (!)"},
   "info"
   {:fn info,
    :spec default-spec,
    :help "Show information about a project"},
   "log"
   {:fn log, :spec default-spec, :help "Show a project's log on stdout"},
   "publish"
   {:args->opts [:domain],
    :fn publish,
    :spec
    (assoc
     default-spec
     :domain
     {:ref "<domain>", :require true, :desc "The domain"}),
    :help "Publish your project to a custom domain"},
   "stop-all"
   {:fn stop-all, :help "Stop every application in your garden (!)"},
   "restart"
   {:fn restart,
    :spec default-spec,
    :help "Restart a project in your garden"},
   "init"
   {:fn init,
    :spec
    (->
     default-spec
     (update :project dissoc :require)
     (assoc
      :force
      {:ref "<boolean>",
       :desc
       "Ignore an existing `garden.edn` and re-initialize the project with a new name"})),
    :help
    "Initialize an application.garden project in the local directory"},
   "version" {:fn #'print-version, :help "Print garden cli version"},
   "help" {:fn #'help, :help "Show help for a command"},
   "secrets"
   {:fn (fn [_] (help {:cmds ["secrets"]})),
    :help "Manage secrets",
    "add"
    {:fn add-secret,
     :args->opts [:secret-name],
     :help "Add a secret to a project",
     :spec
     (assoc
      (merge default-spec secrets-spec)
      :force
      {:coerce :boolean})},
    "remove"
    {:fn remove-secret,
     :args->opts [:secret-name],
     :help "Remove a secret from a project",
     :spec (merge default-spec secrets-spec)},
    "list"
    {:fn list-secrets,
     :spec default-spec,
     :help "List all secrets for a project"}},
   "groups"
   {:fn (fn [_] (help {:cmds ["groups"]})),
    :help "Manage groups",
    "list" {:fn list-groups, :help "List the groups you are part of"},
    "create"
    {:fn create-group,
     :help "Create a group",
     :args->opts [:group-handle]
     :spec
     {:group-handle
      {:ref "<handle>",
       :desc "Unique identifier for a group",
       :require true}}},
    "add-member"
    {:fn add-group-member,
     :help "Add a member to a group"
     :args->opts [:group-handle],
     :spec
     (assoc
      default-spec
      :person-nickname
      {:ref "<nickname>",
       :desc "The person to be added to the group",
       :require true}
      :group-handle
      {:ref "<handle>",
       :require true,
       :desc "The group to add a member to"})},
    "remove-member"
    {:fn remove-group-member,
     :help "Remove a member from a group"
     :args->opts [:group-handle],
     :spec
     (assoc
      default-spec
      :person-nickname
      {:ref "<nickname>",
       :desc "The person to be removed from the group",
       :require true}
      :group-handle
      {:ref "<handle>",
       :require true,
       :desc "The group to remove a member from"})},
    "add-project"
    {:fn add-project-to-group,
     :help "Add a project to a group"
     :args->opts [:group-handle],
     :spec
     (->
      default-spec
      (assoc-in [:project :desc] "The project to be added to the group")
      (assoc
       :group-handle
       {:ref "<handle>",
        :require true,
        :desc "The group to add a project to"}))},
    "remove-project"
    {:fn remove-project-from-group,
     :help "Remove a project from a group"
     :args->opts [:group-handle],
     :spec
     (->
      default-spec
      (assoc-in
       [:project :desc]
       "The project to be removed from the group")
      (assoc
       :group-handle
       {:ref "<handle>",
        :require true,
        :desc "The group to remove a project from"}))}},
   "sftp"
   {:fn sftp,
    :help "Spawn a SFTP session to your project's persistent storage"}})

(defn keyword-map [m]
  (select-keys m (filter keyword? (keys m))))

(defn ->option [k] (str "--" (name k)))

(defonce !errors (atom []))
(defn error-fn [{:as m :keys [cause]}]
  (swap! !errors conj
         (case cause
           :require (format "Missing option: %s" (->option (:option m)))
           :validate (format "Invalid value for option %s" (->option (:option m)))
           :coerce (format "Invalid value for option %s" (->option (:option m)))
           :restricet (format "Invalid option %s" (->option (:option m)))
           nil "Error")))

(defn deep-merge [a b]
  (reduce (fn [acc k] (update acc k (fn [v]
                                      (if (map? v)
                                        (deep-merge v (b k))
                                        (b k)))))
          a (keys b)))

(defn has-parse-opts? [m]
  (some #{:spec :coerce :require :restrict :validate :args->opts :exec-args} (keys m)))

(defn is-option? [s]
  (some-> s (str/starts-with? "-")))

(defn dispatch-tree' [tree args opts]
  (loop [cmds [] all-opts {} args args cmd-info tree]
    (let [m (keyword-map cmd-info)
          should-parse-args? (or (has-parse-opts? m)
                                 (is-option? (first args)))
          parse-opts (deep-merge opts m)
          {:keys [args opts]} (if should-parse-args?
                                (cli/parse-args args parse-opts)
                                {:args args
                                 :opts {}})
          [arg & rest] args]
      (if-let [subcmd-info (get cmd-info arg)]
        (recur (conj cmds arg) (merge all-opts opts) rest subcmd-info)
        (if (:fn cmd-info)
          {:cmd-info cmd-info
           :dispatch cmds
           :opts (merge all-opts opts)
           :args args}
          (if arg
            {:error :no-match
             :dispatch cmds
             :wrong-input arg
             :available-commands (sort (filter string? (keys cmd-info)))}
            {:error :input-exhausted
             :dispatch cmds
             :available-commands (sort (filter string? (keys cmd-info)))}))))))

(defn dispatch' [cmd-tree args opts]
  (dispatch-tree' cmd-tree args opts))

(comment
  (= :input-exhausted (:error (dispatch' cmd-tree [])))
  (= :no-match (:error (dispatch' cmd-tree ["foo"])))
  (dispatch' cmd-tree ["help" "list"]))

(defn indent
  "indent a multiline string by <indent> spaces"
  [indent lines]
  (->> (str/split-lines lines)
       (map (fn [line] (str (apply str (repeat indent " ")) line)))
       (str/join "\n")))

(defn signature [cmd-tree cmds]
  (when (seq cmds)
    (when-let [{:as cmd-info :keys [args->opts]} (get-in cmd-tree cmds)]
     (str/join " " (concat cmds (map #(str "<" (name %) ">") args->opts))))))

(defn help-text [cmd-tree cmds]
  (:help (get-in cmd-tree cmds)))

(defn options-text [cmd-tree cmds]
  (let [s (cli/format-opts (assoc (get-in cmd-tree cmds) :indent 0))]
    (when-not (str/blank? s)
      s)))

(defn subcommand-help-text [cmd-tree cmds]
  (let [subcommands (sort (filter string? (keys (get-in cmd-tree cmds))))]
    (when (seq subcommands)
      (cli/format-table
       {:rows (mapv (fn [c] (let [subcommand (concat cmds [c])]
                              [(signature cmd-tree subcommand) (help-text cmd-tree subcommand)]))
                    subcommands)
        :indent 0}))))

(defn print-command-help [cmd-tree command]
  (when-let [s (signature cmd-tree command)]
    (println s "\t" (help-text cmd-tree command))))

(defn print-command-options [cmd-tree command]
  (when-let [s (options-text cmd-tree command)]
    (println)
    (println "Options:")
    (println (indent 2 s))))

(defn print-available-commands [cmd-tree command]
  (when-let [s (subcommand-help-text cmd-tree command)]
    (println)
    (println "Available commands:")
    (println (indent 2 s))))

(defn help [{:as m :keys [args]}]
  (if (get-in cmd-tree args)
    (do
      (print-command-help cmd-tree args)
      (print-command-options cmd-tree args)
      (print-available-commands cmd-tree args))
    (do
      (println "Unknown command")
      (print-available-commands cmd-tree []))))

(defn dispatch [cmd-tree args {:as opts :keys [middleware]}]
  (let [{:as res :keys [error cmd-info dispatch wrong-input available-commands]} (dispatch' cmd-tree args opts)]
    (if error
      (case error
        :input-exhausted (print-error (str "Available commands:\n\n" (subcommand-help-text cmd-tree dispatch)))
        :no-match (print-error (let [candidates (edit-distance/candidates wrong-input available-commands)]
                                 (if (seq candidates)
                                   (str "Unknown command. Did you mean one of:\n"
                                        (indent 2 (str/join "\n" (map
                                                                  #(str/join " " (concat ["garden"] dispatch [%]))
                                                                  candidates))))
                                   (str "Available commands:\n\n" (subcommand-help-text cmd-tree dispatch))))))
      (let [res (reduce (fn [r m] (m r)) res middleware)]
        ((get-in res [:cmd-info :fn]) res)))))

(defn wrap-with-help [{:as res :keys [dispatch]}]
  (update-in res [:cmd-info :fn] (fn [f] (fn [{:as m :keys [opts]}]
                                         (if (:help opts)
                                           (do
                                             (reset! !errors [])
                                             (help {:args dispatch}))
                                           (f m))))))

(defn dev-null-print-writer []
  (java.io.PrintWriter. "/dev/null"))

(defn wrap-with-quiet [res]
  (update-in res [:cmd-info :fn]
             (fn [f]
               (fn [{:as m :keys [opts]}]
                 (if (:quiet opts)
                   (binding [*out* (dev-null-print-writer)
                             *err* (dev-null-print-writer)]
                     (f m))
                   (f m))))))

(defn wrap-with-output-format [res]
  (update-in res [:cmd-info :fn]
             (fn [f]
               (fn [{:as m :keys [opts]}]
                 (if-let [output-format (:output-format opts)]
                   (let [result (f (assoc-in m [:opts :quiet] true))]
                     (case output-format
                       :edn (prn result)
                       :json (println (json/encode result))))
                   (f m))))))

(defn wrap-with-error-reporting [res]
  (update-in res [:cmd-info :fn]
             (fn [f]
               (fn [m]
                 (if-let [errors (seq @!errors)]
                   (do
                     (doseq [error errors]
                       (print-error error))
                     {:exit-code 1})
                   (f m))))))

(defn wrap-with-exit-code [res]
  (update-in res [:cmd-info :fn]
             (fn [f]
               (fn [m]
                 (let [{:as result :keys [exit-code]} (f m)]
                   (if exit-code
                     (System/exit exit-code)
                     result))))))

(defn migrate-config-file! []
  (when (fs/exists? ".garden.edn")
    (spit "garden.edn"
          (pr-str (:nextjournal/garden (edn/read-string (slurp ".garden.edn")))))
    (fs/delete ".garden.edn"))
  (when-some [pid (:project/id (read-config))]
    (when (uuid? pid)
      (try
        (let [{:keys [name id]} (call-api {:command "info" :project pid})]
          (assert (and name id))
          (update-config! #(-> % (assoc :project name) (dissoc :project/id))))
        (catch Throwable _
          (println (str "There were issues migrating to new project spec. Please run `garden init --force --project " pid "`.")))))))

(defmacro with-exception-reporting [& body]
  `(try
     ~@body
     (catch clojure.lang.ExceptionInfo e#
       (when (try (parse-boolean (System/getenv "DEBUG")) (catch Exception _# false))
         (throw e#))
       (binding [*out* *err*]
         (println (ex-message e#))
         (System/exit 1)))))

(defn -main [& args]
  (with-exception-reporting
    (migrate-config-file!)
    (dispatch cmd-tree *command-line-args* {:middleware [wrap-with-error-reporting
                                                         wrap-with-help
                                                         wrap-with-quiet
                                                         wrap-with-exit-code
                                                         wrap-with-output-format]
                                            :exec-args (read-config)
                                            :error-fn error-fn})))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
