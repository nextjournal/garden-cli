#!/usr/bin/env bb
;; -*- mode: clojure -*-
;; launch nrepl from project `bb --config <path-to-garden-cli>/bb.edn nrepl-server `
(ns nextjournal.garden-cli
  (:refer-clojure :exclude [pr-str])
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell sh]]
            [clojure.core :as core]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

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
        (println "Found empty dir (except for .git)")
        (template target-dir))
      (if (garden-project?)
        (println "It seems you have already initialized a Garden project in this repository. Use --force to overwrite.")

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
                _ (println "Pushing code to garden…")
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
              (do
                (println "Your project has been deleted.")
                (System/exit 0))
              (println message)))
          (do (println "That's not the project name.")
              (System/exit 1)))))))

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
          (println "…bye 👋."))))))

(defn add-secret [{:keys [opts]}]
  (if (and (not (:force opts))
           (some #{(:secret-name opts)}
                 (:secrets (call-api (assoc opts :command "list-secrets")))))
    (println (format "A secret with the same name already exist. Use `garden secrets add %s --force` to overwrite it."
                     (:secret-name opts)))
    (let [{:keys [ok message]}
          (call-api (assoc opts :command "add-secret"
                           :secret-value (or (when-some [c (System/console)]
                                               (println "Type your secret and press Enter:")
                                               (String. (.readPassword c)))
                                             (read-line))))]
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
             :required? true
             ;; this is not bb.cli `:require` as we do not want to throw, see `conform-arguments` below
             :message "Command '%s' needs either a --project option or has to be run inside a garden project."
             :desc "The project name. Defaults to the `:project` entry in your `garden.edn` file."}
   :quiet {:coerce :boolean
           :alias :q
           :desc "Don't print output"}
   :output-format (let [valid-formats #{:edn :json}]
                    {:ref "<output-format>"
                     :coerce :keyword
                     :validate valid-formats
                     :desc (str "Print result in a machine readable format. One of: " (str/join ", " (map name valid-formats)))})})

(def secrets-spec
  {:secret-name {:ref "<secret-name>"
                 :required? true
                 :desc "The secret name"
                 :coerce :string
                 :validate {:pred #(re-matches #"[a-zA-Z_]+" %)
                            :ex-msg (constantly "secret names must only contain alphanumeric characters or underscores")}}})

(def table
  [{:cmds ["init"] :fn init :spec (-> default-spec
                                      (update :project dissoc :required?)
                                      (assoc :force {:ref "<boolean>"
                                                     :desc "By-passes an existing `garden.edn` found the repository and re-initializes the project with a new name."}))
    :help "Set up a garden project in the local directory."}
   {:cmds ["deploy"] :fn deploy
    :help "Deploy a git ref to Garden."
    :spec (-> default-spec (dissoc :output-format)
              (assoc-in [:project :desc] "The project to be deployed. A new project will be created if it doesn't exist yet.")
              (assoc :git-ref {:ref "<git-ref>"
                               :default "HEAD"
                               :desc "The git branch, commit, tag, etc. to be deployed, defaults to HEAD."}
                     :force {:desc "Starts a deployment regardless of the project's code being changed from the last deploy."}
                     :deploy-strategy {:ref "<mode>"
                                       :coerce :keyword
                                       :default :zero-downtime
                                       :validate #{:zero-downtime :restart}
                                       :desc "Specifies whether the current deployment should be stopped _before_ starting the new version (`:restart`) or if it should be stopped only when the new instance is ready (`:zero-downtime`). Defaults to `:zero-downtime`."}))}
   {:cmds ["list"] :fn list-projects :spec (dissoc default-spec :project)
    :help "List your current projects and their status"}
   {:cmds ["info"] :fn info :spec default-spec
    :help "Show information about a project"}
   {:cmds ["log"] :fn log :spec default-spec
    :help "Forward the log for a project to stdout"}
   {:cmds ["restart"] :fn restart :spec default-spec
    :help "Restart a project in your garden"}
   {:cmds ["stop"] :fn stop :spec default-spec
    :help "Stop the application in your garden"}
   {:cmds ["stop-all"] :fn stop-all
    :help "Stop every application in your garden (!)"}
   {:cmds ["delete"] :fn delete :spec (assoc default-spec :force {:coerce :boolean :desc "Don't ask for confirmation"})
    :help "Stop the application and remove all project data from your garden (!)"}
   {:cmds ["rename"] :fn rename :args->opts [:new-project-name]
    :spec (assoc default-spec
                 :new-project-name {:ref "<new-name>"
                                    :required? true
                                    :desc "A new project name"})
    :help "Rename the current project to `new-project-name`"}
   {:cmds ["tunnel"] :args->opts [:port] :fn tunnel
    :help "Open a tunnel from the specified port onto the project's remote service socket"
    :spec (assoc default-spec
                 :port {:ref "<port>" :required? false
                        :desc "The local TCP port to be forwarded"})}
   {:cmds ["sftp"] :fn sftp
    :help "Spawn a SFTP session to your project storage"}
   {:cmds ["publish"] :args->opts [:domain] :fn publish
    :spec (assoc default-spec
                 :domain {:ref "<domain>"
                          :required? true
                          :desc "The domain"})
    :help "Publish your project to a custom domain"}
   {:cmds ["secrets" "add"] :fn add-secret :args->opts [:secret-name]
    :help "Adds a secret to a project"
    :spec (assoc (merge default-spec secrets-spec) :force {:coerce :boolean})}
   {:cmds ["secrets" "remove"] :fn remove-secret :args->opts [:secret-name]
    :help "Removes a secret from a project" :spec (merge default-spec secrets-spec)}
   {:cmds ["secrets" "list"] :fn list-secrets :spec default-spec :help "List all secrets for a project"}
   {:cmds ["groups" "list"] :fn list-groups :help "Returns the groups you are part of"}
   {:cmds ["groups" "create"] :fn create-group
    :help "Creates a group"
    :spec {:group-handle {:ref "<handle>"
                          :desc "The group handle identifies a group in group-related commands."
                          :required? true}}}
   {:cmds ["groups" "add-member"] :fn add-group-member
    :help "Adds a member to a group."
    :spec (assoc default-spec
                 :person-nickname {:ref "<nickname>"
                                   :desc "The person we want to add to the group."
                                   :required? true}
                 :group-handle {:ref "<handle>"
                                :required? true
                                :desc "The group we wish to add a member to."})}
   {:cmds ["groups" "remove-member"] :fn remove-group-member
    :help "Removes a member from a group."
    :spec (assoc default-spec
                 :person-nickname {:ref "<nickname>"
                                   :desc "The person we want to remove from the group."
                                   :required? true}
                 :group-handle {:ref "<handle>"
                                :required? true
                                :desc "The group we wish to remove a member from"})}
   {:cmds ["groups" "add-project"] :fn add-project-to-group
    :help "Adds a project to a group"
    :spec (-> default-spec
              (assoc-in [:project :desc] "The project to be added to the group. Defaults to current project.")
              (assoc :group-handle {:ref "<handle>"
                                    :required? true
                                    :desc "The group we wish to add a project to."}))}
   {:cmds ["groups" "remove-project"] :fn remove-project-from-group
    :help "Removes a project from a group"
    :spec (-> default-spec
              (assoc-in [:project :desc] "The project to be removed from the group. Defaults to current project.")
              (assoc :group-handle {:ref "<handle>"
                                    :required? true
                                    :desc "The group we wish to remove a project from."}))}
   {:cmds ["help"] :fn #'help :help "Show help for a command"}
   {:cmds ["version"] :fn #'print-version :help "Print garden cli version"}
   {:cmds [] :fn #'help}])

(defn signature [{:as command :keys [cmds args->opts]}]
  (str/join " " (concat cmds (map #(str "<" (name %) ">") args->opts))))

(defn help-text [{:as command :keys [help]}]
  help)

(defn print-options [command]
  (let [options-help (cli/format-opts command)]
    (when-not (str/blank? options-help)
      (println)
      (println "Options:")
      (println options-help))))

(defn wrap-with-help [command]
  (update command :fn (fn [f] (fn [{:as m :keys [opts]}]
                                (if (:help opts)
                                  (do (println (help-text command))
                                      (print-options command))
                                  (f m))))))

(defn dev-null-print-writer []
  (java.io.PrintWriter. "/dev/null"))

(defn wrap-with-quiet [command]
  (update command :fn (fn [f]
                        (fn [{:as m :keys [opts]}]
                          (if (:quiet opts)
                            (binding [*out* (dev-null-print-writer)
                                      *err* (dev-null-print-writer)]
                              (f m))
                            (f m))))))

(defn wrap-with-output-format [command]
  (update command :fn (fn [f]
                        (fn [{:as m :keys [opts]}]
                          (if-let [output-format (:output-format opts)]
                            (let [result (f (assoc-in m [:opts :quiet] true))]
                              (case output-format
                                :edn (prn result)
                                :json (println (json/encode result))))
                            (f m))))))

(defn wrap-with-exit-code [command]
  (update command :fn (fn [f]
                        (fn [m]
                          (let [{:as result :keys [exit-code]} (f m)]
                            (if exit-code
                              (System/exit exit-code)
                              result))))))

;; copied from private bb cli fn
(defn split [a b]
  (let [[prefix suffix] (split-at (count a) b)]
    (when (= prefix a)
      suffix)))

(defn help [{:keys [args]}]
  (let [{:as command :keys [cmds]} (some #(when (split (:cmds %) args) %) table)]
    (if (seq cmds)
      (do
        (println)
        (println (signature command) "\t" (help-text command))
        (print-options command))
      (let [max-length (apply max (map (comp count (partial str/join " ") :cmds) table))]
        (println)
        (println "The Garden CLI currently supports these commands:")
        (println)
        (doseq [{:as command :keys [cmds]} table]
          (when (seq cmds)
            (let [cmd (str/join " " cmds)]
              (println (str "\t" cmd (pad cmd max-length) (help-text command))))))
        (println)
        (println "Run `garden help <cmd>` for help on specific options.")
        (println)))))

(defn ->option [[k v]] [(str "--" (name k)) (str v)])

(defn conform-arguments
  "Validates and fills in missing CLI arguments when they can be inferred from project configuration."
  [table command-line-arguments]
  (let [{:keys [cmds args]} (cli/parse-cmds command-line-arguments)
        {:as match dispatch :cmds :keys [spec suffix]}
        (some #(when-some [suffix (split (:cmds %) cmds)]
                 (assoc % :suffix suffix)) table)]
    (if (empty? dispatch)
      {:args ()}
      (let [cfg (read-config)
            {:keys [args opts]} (cli/parse-args (concat suffix args)
                                                ;; we dissoc from spec the default values that we already have in the config
                                                ;; otherwise they'll override the merge below
                                                (update match :spec (partial reduce #(update %1 %2 dissoc :default)) (keys cfg)))
            merged (merge (select-keys cfg (keys spec)) opts)]
        (if-some [missing (when-not (contains? merged :help)
                            (some (fn [[k {:keys [required? message]}]]
                                    (when (and required? (not (contains? merged k)))
                                      (or message (str "Command '%s' needs a --" (name k) " option.")))) spec))]

          {:error (format missing (first dispatch))}
          {:args (concat dispatch args (mapcat ->option merged))})))))

#_(conform-arguments table '("log"))
#_(conform-arguments table '("rename"))
#_(conform-arguments table '("rename" "--new-name" "hello"))
#_(conform-arguments table '("rename" "hello"))
#_(read-config)
#_(conform-arguments table '("deploy" "--force"))
#_(conform-arguments table '("deploy" "--force" "--deploy-strategy" ":zero-downtime"))
#_(conform-arguments table '("deploy" "--force" "--deploy-strategy" ":restart"))
#_(conform-arguments table '("deploy" "--force"))

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
     (catch clojure.lang.ExceptionInfo e
       (when (try (parse-boolean (System/getenv "DEBUG")) (catch Exception e false))
         (throw e))
       (binding [*out* *err*] (println (ex-message e)))
       (System/exit 1))))

(defn -main [& args]
  (with-exception-reporting
    (migrate-config-file!)
    (let [{:keys [args error]} (conform-arguments table *command-line-args*)]
      (if args
        (cli/dispatch (map (comp wrap-with-exit-code
                                 wrap-with-output-format
                                 wrap-with-quiet
                                 wrap-with-help) table) args)
        (print-error error)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
