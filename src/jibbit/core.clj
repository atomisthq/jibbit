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

(defn libs [{:keys [classpath]}]
  (->> classpath
       (filter #(-> % val :lib-name))
       (map (comp io/file key))))

(defn manifest-class-path [{:keys [classpath] :as basis}]
  (->> (libs basis)
       (map #(str "lib/" (.getName %)))
       (clojure.string/join " ")))

(defn paths [{:keys [classpath]}]
  (->> classpath
       (filter #(-> % val :path-key))
       (map key)
       (into [])))

(defn container-cp [basis] 
  (->> (paths basis)
       (concat (->> (libs basis)
                    (map #(str "lib/" (.getName %)))))
       (interpose ":")
       (apply str)))

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
  [{:keys [git-url base-image target-image jar-name jar-file tag aot main basis debug]
    :or {base-image {:image-name "gcr.io/distroless/java"
                     :type :registry}
         target-image {:image-name "app.tar"
                       :type :tar}
         git-url (or
                  (b/git-process {:dir b/*project-root* :git-args ["ls-remote" "--get-url"]})
                  (do
                    (println "could not discover git remote")
                    "https://github.com/unknown/unknown"))}
    :as c}]
  (-> (Jib/from (configure-image base-image {:name "clj-build-test"}))
      (.addLabel "org.opencontainers.image.revision" (clojure.string/trim (:out (sh/sh "git" "rev-parse" "HEAD"))))
      (.addLabel "org.opencontainers.image.source" git-url)
      (.addLabel "com.atomist.containers.image.build" "clj -Tjib build")
        ;; first layer has all the dependent jars
      (.addLayer
       (apply into-list (->> (libs basis)
                             (filter #(.isFile %))
                             (map #(get-path (.getPath %)))
                             (into [])))
       (AbsoluteUnixPath/get "/lib"))
      ;; second layer has the compiled jar
      (.addLayer
       (apply into-list (if aot
                          [(get-path jar-file)]
                          (->> (paths basis)
                               (map get-path)
                               (into []))))
       (AbsoluteUnixPath/get "/"))
        ;; TODO should we add layer comments for downstream analyzers?
      #_(.addLayer (-> (LayerConfiguration/builder)
                       (.setName "Custom Java Layer")
                       (.addEntry (get-path standalone-jar) (AbsoluteUnixPath/get "/"))
                       (.build)))
        ;; TODO this might add too much confusion - can we keep this simple and just not add this?
      #_(.setProgramArguments (into-list "server-0.1.1-standalone.jar"))
      (set-user (assoc c :base-image base-image))
      (.setEntrypoint (apply into-list (-> ["java" "-Dclojure.main.report=stderr" "-Dfile.encoding=UTF-8"]
                                           (concat
                                            (if aot
                                              ["-jar" jar-name]
                                              ["-cp" (container-cp basis) "clojure.main" "-m" (pr-str main)])))))
      (.containerize (-> (Containerizer/to (-> (merge target-image (when tag {:tag tag}))
                                               (add-tags)
                                               (configure-image {:name "clj-jib-test"})))
                         (.setToolName "clojure jib builder")
                         (.setToolVersion "0.1.8")
                         (.addEventHandler
                          LogEvent
                          (reify Consumer
                            (accept [this event]
                              (when (or debug (not (= "INFO" (str (.getLevel event)))))
                                (printf "jib:%-10s%s\n" (.getLevel event) (.getMessage event))))))))))

(def default-jibbit-config-file "jib.edn")
(def class-dir "target/classes")

(defn load-config 
  "load jib config from either JIB_CONFIG env variable, or from a jib.edn file in the project-dir"
  [dir]
  (when-let [edn-file (if-let [e (System/getenv "JIB_CONFIG")]
                        (io/file e)
                        (io/file dir default-jibbit-config-file))]
    (edn/read-string (slurp edn-file))))

(defn aot-clj [{:keys [jar-file basis] :as jib-config}]
  (println "... clojure.tools.build.api/compile-clj")
  (b/compile-clj {:src-dirs (paths basis)
                  :class-dir class-dir
                  :basis basis})
  (println "... clojure.tools.build.api/jar")
  (b/jar (merge
          {:class-dir class-dir
           :jar-file jar-file
           :manifest {"Class-Path" (manifest-class-path basis)}}
          jib-config)))

(defn clean [_]
  (b/delete {:path class-dir}))

(defn build
  "clean, optionally compile/metajar, and then jib"
  [{:keys [project-dir config] :as params}]
  (when project-dir
    (b/set-project-root! project-dir))
  (let [c (or config (load-config (if project-dir (io/file project-dir) (io/file "."))))
        basis (b/create-basis {:project "deps.edn" :aliases (or (:aliases c) [])})
        jar-name (or (:jar-name c) "app.jar") 
        jib-config (merge
                    c
                    {:jar-file (format "target/%s" jar-name)
                     :jar-name jar-name
                     :basis basis }
                    (dissoc params :config))]
    (when (not (:main jib-config))
      (throw (ex-info "must specify :main config" {})))
    (when (:aot jib-config)
      (aot-clj jib-config))
    (println "... run jib")
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
