(ns jibbit.cyclonedx
  (:require [clojure.tools.build.api :as b]))

(b/create-basis {:project "deps.edn"})

(defn ->bom [{:keys [libs] :as basis}]
  (for [[lib-sym {:keys [paths] 
                  :git/keys [url sha]
                  :deps/keys [manifest]}] libs]
    (case manifest
      :mvn {}
      :deps {}
      )
    )
  )
