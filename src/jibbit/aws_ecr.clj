(ns jibbit.aws-ecr
  (:require [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [cognitect.aws.util :as util]))

(defn get-client [api credentials-config]
  (if-let [crp (case (:type credentials-config)
                 :access-key
                 (credentials/basic-credentials-provider (select-keys credentials-config [:access-key-id :secret-access-key]))

                 :system-properties
                 (credentials/system-property-credentials-provider)

                 :profile
                 (credentials/profile-credentials-provider (:profile-name credentials-config))

                 :environment
                 (credentials/environment-credentials-provider))]
    (aws/client (merge {:api api :credentials-provider crp}
                       (select-keys credentials-config [:region])))
    (aws/client {:api api})))

(defn ecr-auth [credentials-config]
  (println "Generating ECR authorization from" (str credentials-config))
  (let [ecr-response (aws/invoke (get-client :ecr credentials-config)
                                 {:op :GetAuthorizationToken})]
    (if-let [authz (get-in ecr-response
                           [:authorizationData 0])]
      (let [[user pass] (str/split (slurp (util/base64-decode (:authorizationToken authz))) #":")]
        {:username user
         :password pass
         :endpoint (:proxyEndpoint authz)})
      (throw (ex-info (str "Cannot generate ECR authorization credentials: " ecr-response) ecr-response)))))
