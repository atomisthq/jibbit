(ns jibbit.core
  (:require [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [jibbit.build :refer [configure-image get-path]])
  (:import
   (com.google.cloud.tools.jib.api Jib Containerizer LogEvent JibContainerBuilder)
   (com.google.cloud.tools.jib.api.buildplan ImageFormat)
   (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath 
                                             FileEntriesLayer 
                                             OwnershipProvider
                                             FileEntriesLayer$Builder)
   (java.io File)
   (java.util.function Consumer)))

(defn docker-path [& args]
  (AbsoluteUnixPath/fromPath (apply get-path args)))

(defn libs
  "Files for each lib in the classpath (often jars for :mvn deps, and dirs for :git libs)"
  [{:keys [classpath]}]
  (->> classpath
       (filter #(-> % val :lib-name))
       (map (comp io/file key))))

(defn manifest-class-path
  "just the libs (not the source or resource paths) relative to the WORKDIR"
  [basis]
  (->> (libs basis)
       (map #(str "lib/" (.getName %)))
       (str/join " ")))

(defn paths [{:keys [classpath]}]
  (->> classpath
       (filter #(-> % val :path-key))
       (mapv key)))

(defn container-cp
  "container classpath (suitable for -cp)
   paths are relative to container WORKDIR, 
   libs are copied into WORKDIR/lib"
  [basis]
  (->> (paths basis)
       (concat (->> (libs basis)
                    (map #(str "lib/" (.getName %)))))
       (str/join ":")))

(defn set-user! [x {:keys [user base-image]}]
  (if-let [user (cond
                  ;; user-defined
                  user user
                  ;; gcr.io/distroless/java ships with a nobody user
                  (.startsWith (:image-name base-image) "openjdk") "65534"
                  (= "gcr.io/distroless/java" (:image-name base-image)) "65532")]
    (.setUser x user)
    x))

(defn user-group-ownership 
  "root:root is actually smart in general - expecially when the process is running as non-root.  Processes can't alter packaged files.
   Some dev processes, like skaffold, have workflows where code changes are written directly into running pods - only works if the container user can write these files
   nobody:root for openjdk:11-slim-buster
   65532:root for distroless"
  [{:keys [base-image]}]
  (cond
    (and (:image-name base-image) (.startsWith (:image-name base-image) "openjdk")) {:user "65534" :group "0"}
    (= "gcr.io/distroless/java" (:image-name base-image)) {:user "65532" :group "0"}
    :else {:user "0" :group "0"}))

(defn add-tags [{:keys [tagger type tag] :as target-image}]
  (cond
    ;; don't add tags when we're building to a tar file
    (= type :tar)
    target-image

    ;; tag name passed in to cli - always use this
    tag
    (update target-image :image-name (fn [image-name]
                                       (let [[_ n] (re-find #"(.*):(.*)" image-name)]
                                         (str (or n image-name) \: tag))))

    ;; call the custom tagger function
    tagger
    (update target-image :image-name (fn [image-name]
                                       (require [(symbol (namespace (:fn tagger)))])
                                       (if-let [s (eval `(~(:fn tagger) (assoc ~(:args tagger) :image-name ~image-name)))]
                                         s
                                         (throw (ex-info ":tagger returned nil image name" {:tagger tagger})))))

    ;; leave the image-name unchanged - will use latest if there is no tag in the image-name
    :else
    target-image))

;; assumes aot-ed jar is in root of WORKDIR
(defn entry-point
  [{:keys [basis aot jar-name main]}]
  (into ["java" "-Dclojure.main.report=stderr" "-Dfile.encoding=UTF-8"]
        (concat
         (-> basis :classpath-args :jvm-opts)
         (if aot
           ["-jar" jar-name]
           (concat
            ["-cp" (container-cp basis) "clojure.main"]
            (if-let [main-opts (-> basis :classpath-args :main-opts)]
              main-opts
              ["-m" (pr-str main)]))))))

(defn add-file-entries-layer
  "build one layer"
  [^JibContainerBuilder b {layer-name :name build-layer :fn}]
  (.addFileEntriesLayer b (.build
                           (doto (FileEntriesLayer/builder)
                             (.setName layer-name)
                             build-layer))))

(defn add-all-layers!
  "add all layers to the jib builder"
  [^JibContainerBuilder b layers]
  (doseq [l layers]
    (add-file-entries-layer b l)))

(defn ownership-provider [user group]
  (reify OwnershipProvider
    (get [_ source-file path-in-container]
      (format "%s:%s" user group))))

(defn clojure-app-layers
  "use basis to create a dependencies and an app layer"
  [{:keys [aot basis jar-file jar-name working-dir user group]}]
  [{:name "dependencies layer"
    :fn (fn [^FileEntriesLayer$Builder layer-builder]
          (doseq [^File f (libs basis)]
            (if (.isDirectory f)
              (.addEntryRecursive layer-builder (.toPath f) (docker-path working-dir "lib" (.getName f)))
              (.addEntry layer-builder (.toPath f) (docker-path working-dir "lib" (.getName f))))))}
   ;; this layer supports non-root file ownership so that tools like skaffold can sync clojure source files in dev mode
   ;; the dependencies layer does not support this - must rebuild an image to swap out a dependency 
   {:name "clojure application layer"
    :fn (fn [^FileEntriesLayer$Builder layer-builder]
          (if aot
            (let [path (get-path jar-file)
                  unix-path (docker-path working-dir jar-name)]
              (.addEntry layer-builder
                         path
                         unix-path
                         (.get FileEntriesLayer/DEFAULT_FILE_PERMISSIONS_PROVIDER path unix-path)
                         FileEntriesLayer/DEFAULT_MODIFICATION_TIME
                         (format "%s:%s" user group)))
            (doseq [p (paths basis)]
              (.addEntryRecursive layer-builder
                                  (get-path (b/resolve-path p))
                                  (docker-path working-dir p)
                                  FileEntriesLayer/DEFAULT_FILE_PERMISSIONS_PROVIDER
                                  FileEntriesLayer/DEFAULT_MODIFICATION_TIME_PROVIDER
                                  (ownership-provider user group)))))}])

(defn jib-build
  "Containerize using jib
     - dependent jar layer:  copy all dependent jars from target/lib into WORKING_DIR/lib
     - app layer:  
         if aot copy target/app.jar into WORKING_DIR
         else copy source/resource paths too
     - try to set a non-root user
     - add org.opencontainer LABEL image metadata from current HEAD commit"
  [{:keys [git-url base-image target-image working-dir tag debug]
    :or {base-image {:image-name "gcr.io/distroless/java"
                     :type :registry}
         target-image {:image-name "app.tar"
                       :type :tar}
         working-dir "/"
         git-url (or
                  (b/git-process {:dir b/*project-root* :git-args ["ls-remote" "--get-url"]})
                  (do
                    (println "could not discover git remote")
                    "https://github.com/unknown/unknown"))}
    :as c}]
  (.containerize
   (doto (Jib/from (configure-image base-image))
     (.addLabel "org.opencontainers.image.revision" (b/git-process {:dir b/*project-root* :git-args ["rev-parse" "HEAD"]}))
     (.addLabel "org.opencontainers.image.source" git-url)
     (.addLabel "com.atomist.containers.image.build" "clj -Tjib build")
     (.setWorkingDirectory (docker-path working-dir))
     (.setFormat (if (= :oci (:image-format target-image)) ImageFormat/OCI ImageFormat/Docker) )
     (add-all-layers! (clojure-app-layers (-> c 
                                              (assoc :working-dir working-dir)
                                              (merge (user-group-ownership c)))))
     (set-user! (assoc c :base-image base-image))
     (.setEntrypoint (entry-point c)))
   (-> (cond-> target-image
         tag (assoc :tag tag))
       add-tags
       configure-image
       (Containerizer/to)
       (.setToolName "clojure jib builder")
       (.setToolVersion "0.1.12")
       (.addEventHandler
        LogEvent
        (reify Consumer
          (accept [_ event]
            (when (or debug (not (#{"DEBUG" "INFO"} (str (.getLevel event)))))
              (printf "jib:%-10s%s\n" (.getLevel event) (.getMessage event)))))))))

(def default-jibbit-config-file "jib.edn")
(def class-dir "target/classes")

(defn load-config
  "load jib config from either JIB_CONFIG env variable, or from a jib.edn file in the project-dir"
  [dir]
  (when-let [edn-file (if-let [e (System/getenv "JIB_CONFIG")]
                        (io/file e)
                        (io/file dir default-jibbit-config-file))]
    (when (.exists edn-file)
      (edn/read-string (slurp edn-file)))))

(defn aot-clj
  "aot compile and jar the paths and resources - not an uberjar
    Class-Path manifest references all mvn libs"
  [{:keys [jar-file basis] :as jib-config}]
  (println "... clojure.tools.build.api/copy-dir")
  (b/copy-dir {:src-dirs (paths basis)
               :target-dir class-dir})
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
  [{:keys [project-dir config aliases] :as params}]
  (when project-dir
    (b/set-project-root! project-dir))
  (let [c (or config (load-config (if project-dir (io/file project-dir) (io/file "."))) {})
        basis (b/create-basis {:project "deps.edn" :aliases (or aliases (:aliases c) [])})
        jar-name (or (:jar-name c) "app.jar")
        jib-config (merge
                    c
                    {:jar-file (str "target/" jar-name)
                     :jar-name jar-name
                     :basis basis}
                    (dissoc params :config))]
    (when-not (or (-> basis :classpath-args :main-opts) (:main jib-config))
      (throw (ex-info "config must specify either :main or an alias with :main-opts" {})))
    (when (:aot jib-config)
      (aot-clj jib-config))
    (println "... run jib")
    (jib-build jib-config)))

(comment
  (clean {})
  (build {:project-dir "/Users/slim/repo/google-cloud"
          :config {:main "main"
                   :git-url "https://github.com"
                   :base-image {:image-name "gcr.io/distroless/java"
                                :type :registry}
                   :target-image {:image-name "gcr.io/personalsdm-216019/distroless-jib-clojure"
                                  :authorizer {:fn 'jibbit.gcloud/authorizer}
                                  :type :docker}}}))
