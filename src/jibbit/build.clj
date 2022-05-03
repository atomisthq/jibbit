(ns jibbit.build
  (:require [clojure.java.io :as io])
  (:import (com.google.cloud.tools.jib.api Credential CredentialRetriever DockerDaemonImage
                                           ImageReference RegistryImage TarImage)
           (java.util Optional)))

(defn get-path [& args]
  (.toPath (apply io/file args)))

(defn- ^ImageReference to-imgref [image-name]
  (ImageReference/parse image-name))

(defn ^RegistryImage add-registry-credentials [^RegistryImage rimg {:keys [username password authorizer]}]
  (cond-> rimg
    username (.addCredential username password)

    (and (not username) authorizer)
    (.addCredentialRetriever
     (reify CredentialRetriever
       (retrieve [_]
         (require [(symbol (namespace (:fn authorizer)))])
         (let [creds (eval `(~(:fn authorizer) ~(:args authorizer)))]
           (Optional/of (Credential/from (:username creds) (:password creds)))))))))

(defmulti configure-image (fn [image-config] (:type image-config)))

(defmethod configure-image :tar [{:keys [image-name] :or {image-name "app.tar"}}]
  (println "Tar image:" image-name)
  (-> (TarImage/at (get-path image-name))
      (.named ^String image-name)))

(defmethod configure-image :registry [{:keys [image-name] :as image-config}]
  (println "Registry image:" image-name)
  (-> (RegistryImage/named (to-imgref image-name))
      (add-registry-credentials image-config)))

(defmethod configure-image :docker [{:keys [image-name]}]
  (println "Local docker:" image-name)
  (DockerDaemonImage/named (to-imgref image-name)))

(defmethod configure-image :default [{:keys [type] :as opts}]
  (throw (ex-info (str "Unknown image type: " type) {:error :unknown-image-type :input opts})))
