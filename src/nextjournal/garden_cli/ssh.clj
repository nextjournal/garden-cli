(ns nextjournal.garden-cli.ssh
  (:require [babashka.curl :as curl]
            [babashka.process :as p]))

(def jumphost "deploy@jump.sauspiel.de")
(def hosts {:production "deploy@172.16.227.137"
            :staging "deploy@62.113.212.138"})
(defonce tunnels (atom {}))

(comment
  (def port 2019)
  (def env :staging)
  (def p (p/process ["ssh" "-N" (format "-L:%s:localhost:%s" port port) "-J" jumphost (hosts env)]
                    {:shutdown p/destroy-tree
                     :out :inherit
                     :err :inherit}))
  (p/destroy-tree p))

(def ssh-opts ["-N" "-S" "none" "-o" "StrictHostKeyChecking=no" "-o" "UserKnownHostsFile=/dev/null"])

(defn tunnel [{:keys [port env]}]
  (when-not (get-in @tunnels [env port])
    (swap! tunnels assoc-in [env port] (p/process (concat ["ssh"]
                                                          ssh-opts
                                                          [(format "-L:%s:localhost:%s" port port) "-J" jumphost (hosts env)])
                                                  {:inherit true
                                                   :shutdown p/destroy-tree}))
    ;; wait for tunnel
    (loop [retries 20]
      (when (and (> retries 0)
                 (try
                   (curl/get (format "http://localhost:%s" port))
                   false
                   (catch clojure.lang.ExceptionInfo _
                     true)))
        (Thread/sleep 50)
        (recur (dec retries))))))

(defn cleanup-tunnels []
  (doseq [port-map (vals @tunnels)]
    (doseq [process (vals port-map)]
      (p/destroy-tree process)
      @process)))

(comment
  (cleanup-tunnels))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. cleanup-tunnels))
