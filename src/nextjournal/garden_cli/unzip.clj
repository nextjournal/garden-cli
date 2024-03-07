(ns nextjournal.garden-cli.unzip
  (:require [clojure.java.io :as io]
            [babashka.fs :refer [create-dirs parent]])
  (:import [java.net URI]
           [java.nio.file CopyOption
            Files
            StandardCopyOption
            LinkOption Path]
           [java.util.zip ZipInputStream]))

(defn- as-path
  ^Path [path]
  (if (instance? Path path) path
      (if (instance? URI path)
        (java.nio.file.Paths/get ^URI path)
        (.toPath (io/file path)))))

(defn- ->copy-opts ^"[Ljava.nio.file.CopyOption;"
  [replace-existing copy-attributes atomic-move nofollow-links]
  (into-array CopyOption
              (cond-> []
                replace-existing (conj StandardCopyOption/REPLACE_EXISTING)
                copy-attributes  (conj StandardCopyOption/COPY_ATTRIBUTES)
                atomic-move      (conj StandardCopyOption/ATOMIC_MOVE)
                nofollow-links   (conj LinkOption/NOFOLLOW_LINKS))))

;; adapted from babashka.fs to work with resources as input streams
(defn unzip
  "Unzips `zip-file` to `dest` directory (default `\".\"`).

   Options:
   * `:replace-existing` - `true` / `false`: overwrite existing files"
  ([zip-file] (unzip zip-file "."))
  ([zip-file dest] (unzip zip-file dest nil))
  ([zip-file dest {:keys [replace-existing]}]
   (let [output-path (as-path dest)
         _ (create-dirs dest)
         cp-opts (->copy-opts replace-existing nil nil nil)]
     (with-open
      [fis (io/input-stream zip-file)
       zis (ZipInputStream. fis)]
       (loop []
         (let [entry (.getNextEntry zis)]
           (when entry
             (let [entry-name (.getName entry)
                   new-path (.resolve output-path entry-name)]
               (if (.isDirectory entry)
                 (create-dirs new-path)
                 (do
                   (create-dirs (parent new-path))
                   (Files/copy ^java.io.InputStream zis
                               new-path
                               cp-opts))))
             (recur))))))))
