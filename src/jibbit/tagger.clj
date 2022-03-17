(ns jibbit.tagger
  (:require [clojure.string :as s]
            [clojure.tools.build.api :as b]))

(defn local-file
  "slurp the image tag from a local version file"
  [{:keys [image-name file]}]
  (let [[_ n _] (re-find #"(.*):(.*)" image-name)]
    (format "%s:%s" (or n image-name) (s/trim (slurp file)))))

(defn tag
  "update the tag of the image-name 
   with the output of 'git describe --tags --exact-match' 
   or the commit sha if there's no tag match"
  [{:keys [image-name]}]
  (if (b/git-process {:dir b/*project-root* :git-args ["status" "--porcelain"]})
    (throw (ex-info "jibbit.tagger/tag requires a clean working directory" {}))
    (let [sha (b/git-process {:dir b/*project-root* :git-args ["rev-parse" "HEAD"]})
          tag (b/git-process {:dir b/*project-root* :git-args ["describe" "--tags" "--exact-match"]})
          [_ n _] (re-find #"(.*):(.*)" image-name)]
      (format "%s:%s" (or n image-name) (or tag sha)))))

(defn environment 
  "use an environment variable for the image name"
  [{:keys [varname]}]
  (.get (System/getenv) varname))
