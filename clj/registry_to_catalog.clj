(ns registry-to-catalog
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [schema]
   [clj-yaml.core :as clj-yaml]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]))

(defn extract-server-name
  "Extract server name from fully qualified name (com.docker.mcp/server-name -> server-name)"
  [full-name]
  (-> full-name
      (string/replace #"/" "-")
      (string/replace #"\." "-")))

(defn collect-variables
  "Collect all variables from packages and remotes"
  [server-detail]
  (let [packages (:packages server-detail)
        remotes (:remotes server-detail)]
    (merge
     ;; Variables from package arguments
     (->> packages
          (mapcat :packageArguments)
          (mapcat :variables)
          (into {}))
     ;; Variables from runtime arguments
     (->> packages
          (mapcat :runtimeArguments)
          (mapcat :variables)
          (into {}))
     ;; Variables from environment variables
     (->> packages
          (mapcat :environmentVariables)
          (mapcat :variables)
          (into {}))
     ;; Variables from remote headers
     (->> remotes
          (mapcat :headers)
          (mapcat :variables)
          (into {})))))

(defn separate-secrets-and-config
  "Separate variables into secrets and config based on isSecret flag"
  [variables]
  (let [grouped (group-by #(get (second %) :isSecret false) variables)]
    {:secrets (into {} (get grouped true []))
     :config (into {} (get grouped false []))}))

(defn build-config-schema
  "Build JSON schema object from config variables"
  [config-vars server-name]
  (when (seq config-vars)
    [{:name server-name
      :type "object"
      :description (format "Configuration for %s" server-name)
      :properties (->> config-vars
                       (map (fn [[var-name var-def]]
                              [(keyword var-name)
                               {:type (case (:format var-def "string")
                                        "number" "number"
                                        "boolean" "boolean"
                                        "string")
                                :description (:description var-def)}]))
                       (into {}))
      :required (->> config-vars
                     (filter #(get (second %) :isRequired false))
                     (map first)
                     (into []))}]))

(defn build-secrets
  "Build secrets array from secret variables"
  [server-name secret-vars]
  (->> secret-vars
       (map (fn [[var-name var-def]]
              (merge
               {:name (str server-name "." (name var-name))
                :env (str (string/upper-case (name var-name)))}
               (when (:placeholder var-def)
                 {:example (:placeholder var-def)}))))
       (into [])))

(defn extract-image-info
  "Extract image repository and digest from OCI package"
  [package]
  (when (and (= "oci" (:registryType package))
             (= "stdio" (get-in package [:transport :type])))
    (str (:identifier package) "@" (:version package))))

(defn restore-interpolated-value
  "Restore original interpolated value from processed value and variables"
  [processed-value variables]
  ;; This reverses the variable substitution done in add-variable
  ;; For example: "Bearer token123" with variables {"auth_token" {...}}
  ;; should become "Bearer {{auth_token}}"
  (reduce
   (fn [value [var-name {:keys [isSecret]}]]
     (let [n (name var-name)]
       (string/replace value (format "{%s}" n) (if isSecret (format "${%s}" (string/upper-case n)) (format "{{%s}}" n)))))
   processed-value
   variables))

(defn convert-env-variables
  "Convert environmentVariables back to docker format with interpolation restored"
  [env-vars]
  (when (seq env-vars)
    (->> env-vars
         (map (fn [{:keys [name value variables]}]
                {:name name
                 :value (if variables
                          (restore-interpolated-value value variables)
                          value)}))
         (into []))))

(defn parse-runtime-arg
  "Parse a runtime argument back to its original form"
  [{:keys [type name value variables]}]
  (let [restored-value (if variables
                         (restore-interpolated-value value variables)
                         value)]
    (case type
      "named" (str name "=" restored-value)
      "positional" restored-value)))

(defn extract-user-from-runtime-args
  "Extract user argument from runtime arguments"
  [runtime-args]
  (some->> runtime-args
           (filter #(and (= "named" (:type %))
                         (= "-u" (:name %))))
           first
           ((fn [{:keys [value variables]}]
              (if variables
                (restore-interpolated-value value variables)
                (second (string/split value #"=")))))))

(defn extract-volumes-from-runtime-args
  "Extract volume arguments from runtime arguments, restoring interpolation"
  [runtime-args]
  (->> runtime-args
       (filter #(and (= "named" (:type %))
                     (= "-v" (:name %))))
       (map (fn [{:keys [value variables]}]
              (let [vol-value (second (string/split value #"="))]
                (if variables
                  (restore-interpolated-value vol-value variables)
                  vol-value))))
       (into [])))

(defn convert-package-args-to-command
  "Convert package arguments back to command array"
  [package-args]
  (when (seq package-args)
    (->> package-args
         (map parse-runtime-arg)
         (into []))))

(defn convert-remote
  "Convert remote from community format to docker format"
  [remote]
  (when remote
    (cond->
     {:url (:url remote)
      :transport_type (:type remote)}

      (:headers remote)
      (assoc :headers
             (->> (:headers remote)
                  (map (fn [{:keys [name value variables]}]
                         [(keyword name)
                          (if variables
                            (restore-interpolated-value value variables)
                            value)]))
                  (into {}))))))

(defn transform-to-docker
  "Transform a ServerDetail (community format) back to docker server format"
  [server-detail]
  (if (s/valid? :registry/ServerDetail server-detail)
    (let [server-name (extract-server-name (:name server-detail))
          package (first (:packages server-detail))
          remote (first (:remotes server-detail))
          variables (collect-variables server-detail)
          {:keys [secrets config]} (separate-secrets-and-config variables)
          runtime-args (:runtimeArguments package)
          metadata (get-in server-detail [:_meta :io.modelcontextprotocol.registry/publisher-provided])]

      (cond->
       {:description (:description server-detail)
        :name server-name
        :title (:title server-detail)}

        ;; Add image if it's an OCI package
        (and package (extract-image-info package))
        (assoc :image (extract-image-info package))

        ;; Add remote if present
        remote
        (assoc :remote (convert-remote remote)
               :type "remote")

        ;; Add config schema if we have config variables
        (seq config)
        (assoc :config (build-config-schema config server-name))

        ;; Add secrets if we have secret variables
        (seq secrets)
        (assoc :secrets (build-secrets server-name secrets))

        ;; Add environment variables
        (:environmentVariables package)
        (assoc :env (convert-env-variables (:environmentVariables package)))

        ;; Add command from package arguments
        (:packageArguments package)
        (assoc :command (convert-package-args-to-command (:packageArguments package)))

        ;; Add user from runtime arguments
        (extract-user-from-runtime-args runtime-args)
        (assoc :user (extract-user-from-runtime-args runtime-args))

        ;; Add volumes from runtime arguments
        (seq (extract-volumes-from-runtime-args runtime-args))
        (assoc :volumes (extract-volumes-from-runtime-args runtime-args))

        ;; Add metadata from publisher-provided
        metadata
        (merge metadata)

        (:icons server-detail)
        (assoc :icon (-> server-detail :icons first :src))
        ))
    (s/explain :registry/ServerDetail server-detail)))

(defn ->mcp-registry [s]
  (cond-> (-> s (dissoc :title :description) (assoc :about (select-keys s [:title :description :icon])))
    (:remote s) (assoc :type "remote" :dynamic {:tools true})
    (:secrets s) (-> (dissoc :secrets) (assoc :config (select-keys s [:secrets])))
    (:oauth s) (-> (update :oauth (fn [{:keys [providers]}] (->> providers (into [])))))))

(spit
 "legacy.json"
 (json/generate-string
   (let [server0 (transform-to-docker (json/parse-string (slurp "./grounding_lite.json") keyword))
         server1 (transform-to-docker (json/parse-string (slurp "./gke-mcp-server.json") keyword))
         server2 (transform-to-docker (json/parse-string (slurp "./google-cloud-compute-mcp_server.json") keyword))
         server3 (transform-to-docker (json/parse-string (slurp "./server_bigquery_mcp.json") keyword))]
     {:name "Google"
      :displayName "Google"
      :registry
      {(:name server0) server0
       (:name server1) server1
       (:name server2) server2
       (:name server3) server3}})
  {:pretty true}))

(let [server0 (transform-to-docker (json/parse-string (slurp "./grounding_lite.json") keyword))
      server1 (transform-to-docker (json/parse-string (slurp "./gke-mcp-server.json") keyword))
      server2 (transform-to-docker (json/parse-string (slurp "./google-cloud-compute-mcp_server.json") keyword))
      server3 (transform-to-docker (json/parse-string (slurp "./server_bigquery_mcp.json") keyword))]
  (doseq [s [server0 server1 server2 server3]]
    (fs/create-dirs (format "./ai-mcp/%s" (:name s)))
    (spit (format "./ai-mcp/%s/server.yaml" (:name s)) (clj-yaml/generate-string (->mcp-registry s) :dumper-options { :flow-style :block}))))
