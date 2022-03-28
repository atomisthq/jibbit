(ns jibbit.report
  (:require [clojure.string :as str]
            [clojure.pprint :refer [pprint]]))

(defn char-limit [s n]
  (->> s
       (partition-all n)
       (map #(apply str %))
       (interpose "\n")
       (apply str)))

(defn layer-report 
  [{:keys [basis working-dir jar-file jar-name] :as jib-config} libs entry-point manifest-class-path jar-file-path docker-jar-file-path]

  (println "## dependencies layer")
  (doseq [{:keys [docker-path dir? :path path-key]} libs]
    (when (not path-key)
      (printf "%-80s -> %s %s\n" docker-path (if dir? "recursively copied from" "copied from") path)))

  (println "## application layer")
  (if (:aot jib-config)
    (do
      (printf "%-80s -> copied from %s\n" jar-file-path docker-jar-file-path)
      (printf "### %s manifest\n" jar-name)
      (println (char-limit manifest-class-path 80)))
    (doseq [{:keys [docker-path dir? path path-key]} libs]
      (when path-key
        (printf "%-80s -> %s %s\n" docker-path (if dir? "recursively copied from" "copied from") path))))

  (println "## entrypoint")
  (println (char-limit (str/join " " entry-point) 80)))
