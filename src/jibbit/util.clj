(ns jibbit.util
  (:require [clojure.string :as str]))

(declare subst-env-var)

(defn env-subst
  "substitute $ENV or ${ENV} in strings using lookup function
     throws exception if the lookup function can't find the replacement
     returns string with all replacements
  
   only supports capitalized var names with underscores [A-Z_]
   example: (env-subst \"gcr.io/$PROJECT_ID/service\" System/getenv)"
  [s lookup]
  (and s (reduce (partial subst-env-var lookup) s (re-seq #"\$\{?([A-Z_]*)\}?" s))))

(defn subst-env-var [lookup s [p v]]
  (str/replace s p (or (lookup v) (throw (ex-info "no lookup" {:value v})))))

