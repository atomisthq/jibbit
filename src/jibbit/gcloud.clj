(ns jibbit.gcloud
  (:require [clojure.java.shell :as sh]
            [clojure.string :as s]))

;; needs gcloud auth login to have already been run
(defn authorizer [_]
  {:username "oauth2accesstoken" 
   :password (s/trim (:out (sh/sh "gcloud" "auth" "print-access-token")))})
