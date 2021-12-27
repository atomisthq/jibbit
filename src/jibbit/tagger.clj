(ns jibbit.tagger
  (:require [clojure.string :as s]
            [clojure.tools.build.api :as b]))

(defn tag [{:keys [local]}]
  (if local
    (s/trim (slurp local))
    (if (b/git-process {:dir b/*project-root* :git-args ["status" "--porcelain"]})
      (throw (ex-info "jibbit.tagger/tag requires a clean working directory" {}))
      (let [sha (b/git-process {:dir b/*project-root* :git-args ["rev-parse" "HEAD"]})
            tag (b/git-process {:dir b/*project-root* :git-args ["describe" "--exact-match"]})]
        (or tag sha)))))
