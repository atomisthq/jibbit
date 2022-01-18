(ns jibbit.creds
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :refer [*project-root*]]))

(defn project-root [local]
  (let [f (io/file *project-root* local)]
    (when (.exists f) f)))

(defn local-file [local]
  (let [f (io/file local)]
    (when (.exists f) f)))

(defn load-edn [{:keys [local]}]
  (some-> (or (project-root local) (local-file local))
          (slurp)
          (read-string)))
