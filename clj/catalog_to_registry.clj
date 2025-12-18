(ns catalog-to-registry
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clj-yaml.core]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as spec]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [schema]))

;; ====================================================================
;; Docker -> Community
;; ====================================================================

(defn valid-server? [m]
  (let [b (spec/valid? :docker/server m)]
    (when (not b) (println (spec/explain :docker/server m)))
    b))
(defn poci? [[_ server]]
  (and (not (:image server)) (not (:remote server))))
(defn outside-contributor? [[_ server]]
  (and (:image server) (not (string/starts-with? (:image server) "mcp"))))
(defn remote? [[_ server]]
  (:remote server))
(defn report-contributor [[s server]]
  [s (-> (:image server)
         (string/split #"@")
         (first))])

(defn parse-ref [s]
  (let [v (and s (string/split s #"@"))]
    (when (= (count v) 2)
      v)))

(defn add-metadata [_ server]
  {:_meta
   {:io.modelcontextprotocol.registry/publisher-provided
    (merge
     (dissoc (:metadata server) :pulls :githubStars :stars)
     (select-keys server [:upstream :dateAdded :readme :toolsUrl :icon :tools :prompts :resources :source :longLived :oauth]))}})

(defn add-package [community-server config-map _server-name server]
  (if-let [[repository digest] (parse-ref (:image server))]
    (merge
     community-server
     {:packages
      [(merge
        {:registryType "oci"
         :transport {:type "stdio"}
         :identifier repository
         :version digest}
        (when-let [env (:env config-map)]
          (when (seq env)
            {:environmentVariables env}))
        (when-let [args (:runtime-args config-map)]
          (when (seq args)
            {:runtimeArguments args}))
        (when-let [args (:package-args config-map)]
          (when (seq args)
            {:packageArguments args})))]})
    community-server))

(defn create-config-input [agg {:keys [properties _required type description] property-name :name}]
  (cond
    (and (seq properties) (= "object" type))
    (reduce
     create-config-input
     agg
     (map (fn [[k v]]
            (assoc v :name (string/join "." [property-name (name k)])))
          properties))
    property-name
    (-> agg (assoc property-name
                   (merge
                    {:isSecret false
                     :format "string"}
                    (when description {:description description}))))
    :else
    {}))

(defn create-secret-inputs [secrets]
  ;; returns map of names to :registry/Inputs that are secret
  (list
   ;; create-secret inputs to be used by other things
   (->> secrets
        (map (fn [{:keys [name]}] [name {:isSecret true}]))
        (into {}))
   ;; here are the environment variables to add
   (->> secrets
        (map (fn [{:keys [name env]}]
               {:name env
                :value (format "{%s}" name)
                :variables {name
                            {:isSecret true
                             :isRequired true}}}))
        (into []))
   ;; env-keyed-secret-map
   (->> secrets
        (map (fn [{:keys [name env]}]
               [env {:name name :isSecret true}]))
        (into {}))))

(defn operate-on-variable-name [{:keys [value whole-expression expression] :as m}
                                operator]
  (case operator
    "volume" (assoc m :value (string/replace value whole-expression (format "{%s}:{%s}" expression expression)))
    "into" (assoc m :is-repeated true)
    ;; TODO volume-target?
    "volume-target" m
    m))

(defn add-variable
  "  variables - map of variables (name->Input)
     value     - the string that might have things to interpolate"
  [variables value]
  ;; handle interpolation
  ;;   |or:[]|volume|into
  ;;   |volume|into
  ;;   |volume-target
  ;;   positional args might have |into and |volume-target|into and |volume|into
  (reduce
   (fn [m [whole-expression expression]]
     ;; exp - the {{...}}
     ;; variable-name - the inner variable-name
     (let [[variable-name & operators] (string/split expression #"\|")
           {:keys [value is-repeated]} (reduce
                                        operate-on-variable-name
                                        {:value (string/replace value whole-expression (format "{%s}" expression))
                                         :is-repeated false
                                         :whole-expression (format "{%s}" expression)
                                         :expression variable-name}
                                        operators)]
       (-> m
           (assoc :value value :isRepeated is-repeated)
           (assoc-in
            [:variables variable-name]
            (get variables variable-name)))))
   ;; default value - gets overridden
   {:value value}
   (re-seq #"\{\{(.*?)\}\}" value)))

(defn add-variable-from-header
  "  variables - map of variables (name->Input)
     value     - the string that might have things to interpolate"
  [variables value]
  ;; handle interpolation
  ;;   |or:[]|volume|into
  ;;   |volume|into
  ;;   |volume-target
  ;;   positional args might have |into and |volume-target|into and |volume|into
  (reduce
   (fn [m [whole-expression expression]]
     ;; exp - the {{...}}
     ;; variable-name - the inner variable-name
     (let [{:keys [name]} (get variables expression)]
       (-> m
           (assoc :value (string/replace value whole-expression (format "{%s}" name))
                  :isRepeated false)
           (assoc-in
            [:variables name]
            {:isSecret true
             :isRequired true}))))
   ;; default value - gets overridden
   {:value value}
   (re-seq #"\$\{(.*)\}" value)))

(defn to-arg [m s]
  (let [[arg value] (string/split s #"=")]
    (merge
     (if value
       {:type "named"
        :name arg}
       {:type "positional"})
     (add-variable m (or value arg)))))

(defn to-env-input [m {:keys [name value]}]
  (merge
   {:name name}
   (add-variable m value)))

(defn generate-config-map [server-name server]
  (let [{:keys [config secrets env command volumes user]} server]
    (when (seq config)
      (assert (= (name server-name) (-> config first :name))  (format "%s has incorrect config" server-name))
      (assert  (= "object" (-> config first :type))))
    ;; this is not true today
    #_(doseq [s secrets]
        (assert (string/starts-with? (:name s) (name server-name)) (format "%s: %s is not prefixed" (name server-name) s)))

    (let [config-map (create-config-input {} (first config))
          [secret-map secret-env-coll env-keyed-secret-map] (create-secret-inputs secrets)]

      {:env (concat
             secret-env-coll
             (->> env
                  (map (partial to-env-input config-map))
                  (into [])))
       :runtime-args (concat
                      (when user [(to-arg (merge config-map secret-map) (format "-u=%s" user))])
                      (when volumes (->> volumes
                                         (map (fn [s] (format "-v=%s" s)))
                                         (map (partial to-arg (merge config-map secret-map)))
                                         (into []))))
       :package-args (->> command
                          (map (partial to-arg (merge config-map secret-map)))
                          (into []))
       :variables (merge config-map secret-map)
       :env-keyed-variables env-keyed-secret-map})))

(defn transform-header [header-name header-value config-map]
  (merge
   {:name (name header-name)}
   (add-variable-from-header (:env-keyed-variables config-map) header-value)))

(defn add-remote [community-server config-map _ server]
  (if-let [remote (:remote server)]
    (merge
     community-server
     {:remotes
      [(merge
        {:type (:transport_type remote)
         :url (:url remote)}
        (when-let [headers (-> remote :headers)]
          {:headers
           (->> headers
                (map (fn [[k v]] (transform-header k v config-map)))
                (into []))}))]})
    community-server))

#_(def publisher-namespace "io.github.slimslenderslacks")
(def publisher-namespace "com.docker.mcp")

(defn transform-to-community [[server-name server]]
  (let [config-map (generate-config-map server-name server)]
    (->
     {:name (format "%s/%s" publisher-namespace (name server-name))
      :description (:description server)
      :title (:title server)
      :$schema "https://static.modelcontextprotocol.io/schemas/2025-10-17/server.schema.json"
      :version "v0.1.0"}
     (add-package config-map server-name server)
     (add-remote config-map server-name server)
     (merge (add-metadata server-name server)))))

(defn valid-server-details? [m]
  (let [b (spec/valid? :registry/ServerDetail m)]
    (when (not b) (spec/explain :registry/ServerDetail m))
    b))

(defn transform-one-server [m k]
  (transform-to-community
   [k (k m)]))

(defn transform-all-servers [docker-servers]
  (->> docker-servers
       (filter (complement poci?))
       (filter (complement outside-contributor?))
       (filter (comp valid-server? second))
       (map transform-to-community)
       (filter valid-server-details?)))

(defn server-name [m]
  (-> m
      :name
      ((fn [s] (let [[_ post] (re-find #".*/(.*)" s)]
                 post)))))

(defn canonical [m]
  (-> m
      (walk/keywordize-keys)
      (dissoc :version)
      (update-in [:_meta :io.modelcontextprotocol.registry/publisher-provided] select-keys [:oauth :longLived])
      #_(update-in [:_meta :io.modelcontextprotocol.registry/publisher-provided] dissoc :pulls :githubStars :stars)))

(defn server-updated? [new old]
  (let [b (not (= (canonical new) (canonical old)))]
    b))

(defn increment-version [s]
  (let [[_ minor] (re-find #"v0\.1\.(\d+)" s)
        minor-version (Integer/parseInt minor)]
    (format "v0.1.%d" (inc minor-version))))

(defn output-json [m]
  ;; if new get both server-id and version-id
  ;; if changed, increment version and create new entry
  ;; if unchanged, do nothing
  (let [server-json (format "servers/%s/server.json" (server-name m))
        previous (try (json/parse-string (slurp server-json) keyword) (catch Throwable _ {}))]
    (cond
      ;; new server
      (not (fs/exists? server-json))
      (do
        (fs/create-dirs (fs/parent server-json))
        (spit
         server-json
         (json/generate-string m {:pretty true})))
      ;; server updated
      (server-updated? m previous)
      (let [version (:version previous)]
        (fs/create-dirs (fs/parent server-json))
        (spit (format "servers/%s/%s.json" (server-name m) version) (json/generate-string previous {:pretty true}))
        (spit server-json (json/generate-string (assoc m :version (increment-version version)) {:pretty true})))
      ;; do nothing
      :else
      (println (format "%s @ %s unchanged" (server-name m) (:version m))))))

(defn output-seed-data [m]
  (spit
   "data/seed.json"
   (json/generate-string
    m
    {:pretty true})))

(defn extract-package-variables [package]
  (concat
   (->> package
        :packageArguments
        (mapcat :variables))
   (->> package
        :runtimeArguments
        (mapcat :variables))
   (->> package
        :environmentVariables
        (mapcat :variables))))

(defn extract-variables [m]
  {:name (:name m)
   :inputs
   (concat
    (->> (:packages m)
         (mapcat extract-package-variables))
    (->> (:remotes m)
         :headers))})

(defn guid []
  (str (java.util.UUID/randomUUID)))

(defn add-seed-guids [m]
  (-> m
      (assoc-in [:_meta :io.modelcontextprotocol.registry/official :serverId] (guid))
      (assoc-in [:_meta :io.modelcontextprotocol.registry/official :versionId] (guid))))

(defn file-db []
  (->> (file-seq (fs/file "./servers"))
       (filter (complement fs/directory?))
       (filter (complement #(= "ids.edn" (fs/file-name %))))
       (map (comp #(drop 2 %)  fs/components))
       (map (fn [x] () (->> x (map #(.toString %)) (into []))))
       (sort-by first)
       (partition-by first)))

(defn parse-server-entries [transform coll]
  (doseq [[server f] coll]
    (let [file-name (format "servers/%s/%s" server f)]
      (spit file-name
            (json/generate-string
             (transform
              (json/parse-string
               (slurp file-name)
               keyword))
             {:pretty true})))))

(defn clean-out-metadata [m]
  (update-in m [:_meta :io.modelcontextprotocol.registry/publisher-provided] dissoc :pulls :stars :githubStars))

(defn server-id [s]
  (let [m (edn/read-string (slurp "servers/ids.edn"))]
    (if (contains? m s)
      (get m s)
      (let [id (guid)]
        (spit "servers/ids.edn" (pr-str (assoc m s id)))
        id))))

(defn ground-server-id [m]
  (let [s (server-name m)
        id (server-id s)]
    (update-in m [:_meta :io.modelcontextprotocol.registry/official :serverId] (constantly id))))

(comment
  (def docker-servers (:registry (clj-yaml.core/parse-string (slurp "/Users/slim/.docker/mcp/catalogs/docker-mcp.yaml"))))
  (count docker-servers)
  (->> docker-servers
       (vals)
       (map :oauth)
       (filter identity)
       )
  (def transformed-servers (transform-all-servers docker-servers))
  (count transformed-servers)
  ;; update the servers dir
  (doall
    (map output-json transformed-servers))

  (output-seed-data (into [] transformed-servers))

  (map first
       (mapcat :inputs
               (map extract-variables transformed-servers)))

  ;; needed to clean out the metadata one time
  (map (partial parse-server-entries clean-out-metadata) (file-db))
  (map (partial parse-server-entries ground-server-id) (file-db))
  (clojure.pprint/pprint (count (edn/read-string (slurp "servers/ids.edn"))))

  ;; 208 servers
  (count transformed-servers)
  (count
   (->> transformed-servers
        (filter :remotes)
        (map :remotes)))

  ;; just transform one server
  (transform-one-server docker-servers :openapi-schema)
  (transform-one-server docker-servers :dappier-remote)
  (transform-one-server docker-servers :fibery)

  (count docker-servers)
  ;; we're not moving over POCIs
  (->> docker-servers
       (filter poci?)
       (map first))

  ;; we're not moving over outside contributions
  (count
   (->> docker-servers
        (filter outside-contributor?)
        (map report-contributor)))

  (count
   (->> docker-servers
        (filter remote?)
        (filter (comp :headers :remote second))))

  (count docker-servers)
  (- 225 3 15 29))

