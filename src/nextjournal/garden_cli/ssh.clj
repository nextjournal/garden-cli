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

(defn tunnel-up? [port]
  (some? (:status (curl/get (format "http://localhost:%s" port) {:throw false}))))

(defn tunnel [{:keys [port env]}]
  (when-not (get-in @tunnels [env port])
    (swap! tunnels assoc-in [env port] (p/process (concat ["ssh"]
                                                          ssh-opts
                                                          [(format "-L:%s:localhost:%s" port port) "-J" jumphost (hosts env)])
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
      @process)))

(comment
  (cleanup-tunnels))

(.addShutdownHook
 (Runtime/getRuntime)
 (Thread. cleanup-tunnels))
