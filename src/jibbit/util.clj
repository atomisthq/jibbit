(ns jibbit.util
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]))

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

(s/def ::port pos-int?)
(s/def ::protocol #{:tcp :udp})
(s/def ::parsed-docker-port
  (s/keys :req-un [::port ::protocol]))

(defn- parse-docker-port-s
  [s]
  (let [[port protocol] (clojure.string/split s #"/")]
    {:port     (clojure.edn/read-string port)
     :protocol (or (keyword protocol) :tcp)}))

(defn parse-docker-port
  "Parses port/protocol as per https://docs.docker.com/engine/reference/builder/#expose"
  [pp]
  (let [parsed (condp apply [pp]
                 pos-int? {:port pp :protocol :tcp}
                 string? (parse-docker-port-s pp)
                 (throw (ex-info "Expect <port> or <port>/<protocol>. Port only can be a number. See https://docs.docker.com/engine/reference/builder/#expose" {:pp pp})))]
    (when-not
      (s/valid? ::parsed-docker-port parsed)
      (throw (ex-info (s/explain-str ::parsed-docker-port parsed) {:pp pp})))
    parsed))