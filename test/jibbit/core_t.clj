(ns jibbit.core-t
  (:require [clojure.test :as t]
            [jibbit.core :refer [build]]
            [test-with-files.tools :refer [with-tmp-dir]]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.data.json :as json])
  (:import [org.apache.commons.vfs2 FileSystemManager VFS])
  )

(def sample-project
  {:deps '{:paths ["src"]
           :deps {org.clojure/clojure {:mvn/version "1.10.3"}
                  compojure/compojure {:mvn/version "1.6.2"}
                  ring/ring-jetty-adapter {:mvn/version "1.9.4"}
                  ring/ring-json {:mvn/version "0.5.1"}}
           :aliases {:jetty
                     {:main-opts ["-m" "atomist.web.handler"]}}}

   :main "atomist.web.handler"

   :clj '[(ns atomist.web.handler
            (:require [compojure.core :refer [defroutes GET]]
                      [ring.adapter.jetty :refer [run-jetty]]
                      [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
                      [compojure.route :as route])
            (:import [java.security KeyStore]
                     [java.io ByteArrayOutputStream FileInputStream]
                     [java.util Base64])
            (:gen-class))

          (defroutes app
            (GET "/health" _ (constantly {:status 200 :body "ok"}))
            (->
             (GET "/" _ (constantly {:status 200 :body {:hey "dockerhub-eks-clj-web" :version 179}}))
             (wrap-json-body {:keywords? true :bigdecimals? true})
             (wrap-json-response))
            (route/not-found "<h1>not found</h1>"))

          (defn -main
            [& args]
            (try
              (println "... run-jetty")
              (run-jetty app {:port 3000})
              (catch Throwable t
                (println "failed to start " t))))]})

(defn clj-ns-file [s]
  (-> s
      (s/replace #"\." "/")
      (str ".clj")))

(defn layout-sample-project [tmp-dir sample-project]
  (spit (io/file tmp-dir "deps.edn")
        (-> sample-project :deps pr-str))
  (let [f (io/file tmp-dir (str "src/" (clj-ns-file (:main sample-project))))]
    (io/make-parents f)
    (spit
     f
     (->> (:clj sample-project)
          (map pr-str)
          (s/join "\n")))))

(def file-system-manager (VFS/getManager))

(defn extract-entry-point [f]
  (let [tar-file (.resolveFile file-system-manager (format "tar:%s" (.getAbsolutePath f)))]
    (->> (.getChildren tar-file)
         (filter #(#{"config.json"} (.getBaseName (.getName %))))
         (map #(some-> (.getContent %)
                       (.getInputStream)
                       (io/reader)
                       (json/read :key-fn keyword)))
         (first)
         :config
         :Entrypoint)))

(def non-aot-entrypoint
  ["java"
   "-Dclojure.main.report=stderr"
   "-Dfile.encoding=UTF-8"
   "-cp"
   "src:lib/compojure__compojure_compojure-1.6.2.jar:lib/org.clojure__clojure_clojure-1.10.3.jar:lib/ring__ring-jetty-adapter_ring-jetty-adapter-1.9.4.jar:lib/ring__ring-json_ring-json-0.5.1.jar:lib/clout__clout_clout-2.2.1.jar:lib/medley__medley_medley-1.3.0.jar:lib/org.clojure__tools.macro_tools.macro-0.1.5.jar:lib/org.clojure__core.specs.alpha_core.specs.alpha-0.2.56.jar:lib/org.clojure__spec.alpha_spec.alpha-0.2.194.jar:lib/org.eclipse.jetty__jetty-server_jetty-server-9.4.42.v20210604.jar:lib/ring__ring-core_ring-core-1.9.4.jar:lib/ring__ring-servlet_ring-servlet-1.9.4.jar:lib/cheshire__cheshire_cheshire-5.10.0.jar:lib/instaparse__instaparse_instaparse-1.4.8.jar:lib/javax.servlet__javax.servlet-api_javax.servlet-api-3.1.0.jar:lib/org.eclipse.jetty__jetty-http_jetty-http-9.4.42.v20210604.jar:lib/org.eclipse.jetty__jetty-io_jetty-io-9.4.42.v20210604.jar:lib/commons-fileupload__commons-fileupload_commons-fileupload-1.4.jar:lib/commons-io__commons-io_commons-io-2.10.0.jar:lib/crypto-equality__crypto-equality_crypto-equality-1.0.0.jar:lib/crypto-random__crypto-random_crypto-random-1.2.1.jar:lib/ring__ring-codec_ring-codec-1.1.3.jar:lib/com.fasterxml.jackson.core__jackson-core_jackson-core-2.10.2.jar:lib/com.fasterxml.jackson.dataformat__jackson-dataformat-cbor_jackson-dataformat-cbor-2.10.2.jar:lib/com.fasterxml.jackson.dataformat__jackson-dataformat-smile_jackson-dataformat-smile-2.10.2.jar:lib/tigris__tigris_tigris-0.1.2.jar:lib/org.eclipse.jetty__jetty-util_jetty-util-9.4.42.v20210604.jar:lib/commons-codec__commons-codec_commons-codec-1.15.jar"
   "clojure.main"
   "-m"
   "\"atomist.web.handler\""])

(def aot-entrypoint
  ["java"
   "-Dclojure.main.report=stderr"
   "-Dfile.encoding=UTF-8"
   "-jar"
   "app.jar"])

(t/deftest deps-edn-test
  (t/testing "packaging a clojure project without :aot"
    (with-tmp-dir tmp-dir
      (layout-sample-project tmp-dir sample-project)
      (build {:project-dir tmp-dir
              :config {:main (-> sample-project :main)
                       :git-url "https://github.com"
                       :target-image {:type :tar}}})
      (extract-entry-point (io/file "app.tar"))
      (t/is (= non-aot-entrypoint (extract-entry-point (io/file "app.tar"))))))
  (t/testing "packaging a clojure project with :aot"
    (with-tmp-dir tmp-dir
      (layout-sample-project tmp-dir sample-project)
      (build {:project-dir tmp-dir
              :aot true
              :config {:main (-> sample-project :main)
                       :git-url "https://github.com"
                       :target-image {:type :tar}}})
      (extract-entry-point (io/file "app.tar"))
      (t/is (= aot-entrypoint (extract-entry-point (io/file "app.tar")))))))
