(ns nextjournal.garden-cli.ssh
  (:require [babashka.process :as p])
  (:import [java.net Socket ConnectException]))

(def hosts {:production ["-J" "deploy@jump.sauspiel.de" "deploy@172.16.227.70"]
            :staging ["-J" "deploy@jump.sauspiel.de" "deploy@62.113.212.138"]
            :dev ["-p" "2222" "deploy@127.0.0.1"]})
(defonce tunnels (atom {}))

(comment
  (def port 2019)
  (def env :dev)
  (def p (p/process (concat ["ssh" "-N" (format "-L:%s:localhost:%s" port port)]
                            (hosts env))
                    {:shutdown p/destroy-tree
                     :out :string
                     :err :string}))
  (p/destroy-tree p))

(def ssh-opts ["-N" "-S" "none" "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null" "-o" "ControlMaster=no" "-o" "ControlPath=none"])

(defn tunnel-up? [port]
  (try (Socket. "localhost" port)
       true
       (catch ConnectException e false)))

(defn tunnel [{:keys [port remote-host remote-port env]
               :or {remote-host "localhost"
                    remote-port port}}]
  (when-not (get-in @tunnels [env port])
    (swap! tunnels assoc-in [env port] (p/process (concat ["ssh"]
                                                          ssh-opts
                                                          [(format "-L:%s:%s:%s" port remote-host remote-port)]
                                                          (hosts env))
                                                  {:inherit true
                                                   :shutdown p/destroy-tree}))
    ;; wait for tunnel
    (loop [retries 0]
      (if (< retries 100)
        (when-not (tunnel-up? port)
          (Thread/sleep 50)
          (recur (inc retries)))
        (throw (ex-info (format "could not open tunnel within %s retries" retries) {}))))))

(defn cleanup-tunnels []
  (doseq [port-map (vals @tunnels)]
    (doseq [process (vals port-map)]
      (p/destroy-tree process)
      @process))
  (reset! tunnels {}))

(comment
  (tunnel {:port 8000 :env :staging})
  (cleanup-tunnels))

(defonce _cleanup-hook
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. cleanup-tunnels)))
