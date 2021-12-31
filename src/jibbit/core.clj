(ns jibbit.core
  (:require [clojure.tools.build.api :as b]
            [clojure.string]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [leiningen.jib-build :refer [configure-image get-path into-list]])
  (:import
   (com.google.cloud.tools.jib.api Jib
                                   DockerDaemonImage
                                   Containerizer
                                   TarImage
                                   RegistryImage
                                   ImageReference 
                                   CredentialRetriever 
                                   Credential
                                   LayerConfiguration
                                   LogEvent)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath)
   (com.google.cloud.tools.jib.frontend
    CredentialRetrieverFactory)
   (java.nio.file Paths)
   (java.io File)
   (java.util List ArrayList Optional)
   (java.util.function Consumer)))

(defn manifest-class-path [meta-lib-jars]
  (->> meta-lib-jars
       (map #(io/file %1))
       (filter #(.exists %1))
       (map #(str "lib/" (.getName %)))
       (clojure.string/join " ")))

(defn set-user [x {:keys [user base-image]}]
  (.setUser x (cond
                ;; user-defined
                user user
                ;; gcr.io/distroless/java ships with a nobody user
                (.startsWith (:image-name base-image) "openjdk") "nobody"
                (= "gcr.io/distroless/java" (:image-name base-image)) "65532")))

(defn add-tags [{:keys [tagger type tag] :as target-image}]
  (cond
    ;; don't add tags when we're building to a tar file
    (= type :tar) 
    target-image

    ;; tag name passed in to cli - always use this
    tag
    (update target-image :image-name (fn [image-name]
                                       (let [[_ n _] (re-find #"(.*):(.*)" image-name)]
                                           (format "%s:%s" (or n image-name) tag))))

    ;; call the custom tagger function
    tagger
    (update target-image :image-name (fn [image-name]
                                       (require [(symbol (namespace (:fn tagger)))])
                                       (let [tag (eval `(~(:fn tagger) (assoc ~(:args tagger) :image-name ~image-name)))]
                                         (let [[_ n _] (re-find #"(.*):(.*)" image-name)]
                                           (format "%s:%s" (or n image-name) tag)))))

    ;; leave the image-name unchanged - will use latest if there is no tag in the image-name
    :else
    target-image))

(defn jib-build
  "Containerize using jib
     - dependent jar layer:  copy all dependent jars from target/lib into WORKING_DIR/lib
     - app jar layer:  copy target/app.jar into WORKING_DIR
     - try to set a non-root user
     - add org.opencontainer LABEL image metadata from current HEAD commit"
  [{:keys [git-url base-image target-image project-dir jar-name tag]
    :or {project-dir (.getPath (io/file (System/getenv "PWD")))
         jar-name "app.jar"
         base-image {:image-name "gcr.io/distroless/java"
                     :type :registry}
         target-image {:image-name "app.tar"
                       :type :tar}
         git-url (or
                  (b/git-process {:dir b/*project-root* :git-args ["ls-remote" "--get-url"]})
                  (do
                    (println "could not discover git remote")
                    "https://github.com/unknown/unknown"))}
    :as c}]
  (let [standalone-jar (format "%s/target/%s" project-dir jar-name)]
    (-> (Jib/from (configure-image base-image {:name "clj-build-test"}))
        (.addLabel "org.opencontainers.image.revision" (clojure.string/trim (:out (sh/sh "git" "rev-parse" "HEAD"))))
        (.addLabel "org.opencontainers.image.source" git-url)
        (.addLabel "com.atomist.containers.image.build" "clj -Tjib build")
        ;; first layer has all the dependent jars
        (.addLayer (apply into-list (->> (file-seq (io/file (format "%s/target/lib" project-dir)))
                                         (filter #(.isFile %))
                                         (map #(get-path (.getPath %)))
                                         (into [])))
                   (AbsoluteUnixPath/get "/lib"))
        ;; second layer has the compiled jar
        (.addLayer (into-list (get-path standalone-jar)) (AbsoluteUnixPath/get "/"))
        ;; TODO should we add layer comments for downstream analyzers?
        #_(.addLayer (-> (LayerConfiguration/builder)
                         (.setName "Custom Java Layer")
                         (.addEntry (get-path standalone-jar) (AbsoluteUnixPath/get "/"))
                         (.build)))
        ;; TODO this might add too much confusion - can we keep this simple and just not add this?
        #_(.setProgramArguments (into-list "server-0.1.1-standalone.jar"))
        (set-user (assoc c :base-image base-image))
        (.setEntrypoint (apply into-list ["java" "-Dclojure.main.report=stderr" "-Dfile.encoding=UTF-8" "-jar" jar-name ]))
        (.containerize (-> (Containerizer/to (-> (merge target-image (when tag {:tag tag}))
                                                 (add-tags)
                                                 (configure-image {:name "clj-jib-test"})))
                           (.setToolName "clojure jib builder")
                           (.setToolVersion "0.1.0")
                           (.addEventHandler
                            LogEvent
                            (reify Consumer
                              (accept [this event] (printf "%-10s%s\n" (.getLevel event) (.getMessage event))))))))))

(def default-jibbit-config-file "jib.edn")

(defn load-config 
  "load jib config from either JIB_CONFIG env variable, or from a jib.edn file in the project-dir"
  [dir]
  (when-let [edn-file (if-let [e (System/getenv "JIB_CONFIG")]
                        (io/file e)
                        (io/file dir default-jibbit-config-file))]
    (edn/read-string (slurp edn-file))))

(defn build
  "clean, compile, metajar, and then jib"
  [{:keys [project-dir config tag]}]
  (when project-dir
    (b/set-project-root! project-dir))
  (b/create-basis {:project "deps.edn"})
  (let [jib-config (merge 
                     (or config (load-config (if project-dir (io/file project-dir) (io/file "."))))
                     (when tag
                       {:tag tag}))
        class-dir "target/classes"
        meta-lib-dir "target/lib"
        basis (b/create-basis {:project "deps.edn"})]
    (when (not (:main jib-config)) (throw (ex-info "must specify :main config" {})))
    #_(b/delete {:path class-dir})
    (println "... clojure.tools.build.api/compile-clj")
    (b/compile-clj {:src-dirs (->> (if-let [src-paths (:paths basis)]
                                     (if (seq src-paths) src-paths ["src"])
                                     ["src"])
                                   (into []))
                    :class-dir class-dir
                    :basis basis})
    (println "... clojure.tools.build.api/jar")
    (b/jar (merge
            {:class-dir class-dir
             :jar-file (format "target/%s" (or (:jar-name config) "app.jar"))
             :manifest {"Class-Path" (manifest-class-path (:classpath-roots basis))}}
            jib-config))
    (println "... run jib")
    (doseq [s (:classpath-roots basis)]
      (b/copy-file {:src s
                    :target (format "%s/%s/%s" (or project-dir ".") meta-lib-dir (.getName (io/file s)))}))
    (jib-build jib-config)))

(comment
  (build {:project-dir "/Users/slim/repo/google-cloud"
          :config {:main "main"
                   :git-url "https://github.com"
                   :base-image {:image-name "gcr.io/distroless/java"
                                :type :registry}
                   :target-image {:image-name "gcr.io/personalsdm-216019/distroless-jib-clojure"
                                  :authorizer {:fn 'jibbit.gcloud/authorizer}
                                  :type :docker}}}))
