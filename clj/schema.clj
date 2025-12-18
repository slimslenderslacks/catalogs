(ns schema
  (:require [clojure.spec.alpha :as spec]))

(defn url? [x] true)
(defn version? [s] true)

;; ===================================================
;; Docker Server spec
;; ===================================================

(spec/def :docker/transport_type #{"sse" "streamable-http"})
(spec/def :docker/image string?)
(spec/def :docker/remote (spec/keys :req-un [:registry/url :docker/transport_type]))
(defn docker-server-type [m] (cond (:image m) :image (:remote m) :remote))
(defmulti docker-server docker-server-type)
(defmethod docker-server :image [_]
  (spec/keys :req-un [:registry/description :docker/image]
             :opt-un [:docker/env
                      :docker/config
                      :docker/volumes
                      :docker/command
                      :docker/secrets
                      :docker/user]))
(defmethod docker-server :remote [_]
  (spec/keys :req-un [:registry/description :docker/remote]
             :opt-un [:docker/config
                      :docker/secrets]))
(spec/def :docker/server (spec/multi-spec docker-server docker-server-type))
(spec/def :docker/command (spec/coll-of string?))
(spec/def :docker/volumes (spec/coll-of string?))
(spec/def :docker/user string?)
(spec/def :docker/config (spec/coll-of any?))
(spec/def :docker/env (spec/coll-of (spec/keys :req-un [:registry/name :registry/value])))
(spec/def :docker/secrets (spec/coll-of (spec/keys :req-un [:registry/name :docker.secret/env :docker.secret/example])))
(spec/def :docker.secret/env string?)
(spec/def :docker.secret/example string?)

;; =======================================================
;; Community Server Spec
;; =======================================================

(spec/def :registry/repository (spec/keys :req-un [:registry/url :registry/source]
                                          :opt-un [:registry/id :registry/subfolder]))

;; Package
(spec/def :registry/Package (spec/keys :req-un [:registry/registryType
                                                :registry/identifier
                                                :registry/version
                                                :registry/transport]
                                       :opt-un [:registry/registryBaseUrl
                                                :registry/runtimeHint
                                                :registry/fileSha256
                                                :registry/runtimeArguments
                                                :registry/packageArguments
                                                :registry/environmentVariables]))
(spec/def :registry/runtimeArguments (spec/coll-of :registry/Argument))
(spec/def :registry/packageArguments (spec/coll-of :registry/Argument))
(spec/def :registry/environmentVariables (spec/coll-of :registry/KeyValueInput))

;; Input
(spec/def :registry/registryType #{"npm" "pypi" "oci" "nuget" "mcpb"})
(spec/def :registry/Input (spec/keys :opt-un [:registry/description
                                              :registry/isRequired
                                              :registry/format
                                              :registry/value
                                              :registry/isSecret
                                              :registry/default
                                              :registry/choices
                                              :registry/placeholder]))
(spec/def :registry/format #{"string" "number" "boolean" "filepath"})
(spec/def :registry/InputWithVariables (spec/merge
                                        :registry/Input
                                        (spec/keys :opt-un [:registry/variables])))
(spec/def :registry/KeyValueInput (spec/and
                                   :registry/InputWithVariables
                                   (spec/keys :req-un [:registry/name])))
(spec/def :registry/variables (spec/map-of (fn [s] (or (keyword? s) (string? s))) :registry/Input))
(defmulti argument-type :type)
(defmethod argument-type "positional" [_]
  (spec/merge
   :registry/InputWithVariables
   (spec/keys :req-un [:registry/type]
              :opt-un [:registry/isRepeated])
   (spec/or
    :value (spec/keys :req-un [:registry/value])
    :value-hint (spec/keys :req-un [:registry/valueHint]))))
(defmethod argument-type "named" [_]
  (spec/merge
   :registry/InputWithVariables
   (spec/keys :req-un [:registry/type :registry/name]
              :opt-un [:registry/isRepeated])))
(spec/def :registry/Argument (spec/multi-spec argument-type :type))
(spec/def :registry/type #{"named" "positional"})

;; Icon
(spec/def :icon/src string?) ;maxLength 255 format uri
(spec/def :icon/theme #{"light" "dark"})
(spec/def :icon/mimeType #{"image/png" "image/jpeg" "image/jpg" "image/svg+xml" "image/webp"})
(spec/def :icon/size (fn [s] (and (string? s) (re-matches #"\d+x\d+|.*" s))))
(spec/def :icon/sizes (spec/coll-of :icon/size))
(spec/def :registry/Icon (spec/keys :req-un [:icon/src]
                                    :opt-un [:icon/theme :icon/mimeType :icon/sizes]))

;; Remote
(spec/def :remote/type string?)
(defmulti remote-type :type)
(defmethod remote-type "stdio" [_]
  (spec/keys :req-un [:remote/type]))
(defmethod remote-type "sse" [_]
  (spec/keys :req-un [:remote/type :remote/url] :opt-un [:remote/headers]))
(defmethod remote-type "streamable-http" [_]
  (spec/keys :req-un [:remote/type :remote/url] :opt-un [:remote/headers]))
(spec/def :registry/transport (spec/multi-spec remote-type :type))
(spec/def :remote/headers (spec/coll-of
                           :registry/KeyValueInput))

;; ServerDetail

(spec/def :registry/ServerDetail (spec/keys
                                  :req-un [:registry/name 
                                           :registry/description 
                                           :registry/version 
                                           :registry/$schema]
                                  :opt-un [:registry/repository 
                                           :registry/websiteUrl 
                                           :registry/packages 
                                           :registry/remotes 
                                           :registry/icons
                                           :registry/_meta
                                           :registry/title]))
(spec/def :registry/icons (spec/coll-of :registry/Icon))
(spec/def :registry/packages (spec/coll-of :registry/Package))
(spec/def :registry/remotes (spec/coll-of
                             (spec/and
                              :registry/transport
                              (comp #{"sse" "streamable-http"} :type))))
(spec/def :registry/_meta (spec/and
                           (spec/keys :opt [:io.modelcontextprotocol.registry/publisher-provided
                                            :io.modelcontextprotocol.registry/official])
                           (spec/map-of keyword? any?)))
(spec/def :io.modelcontextprotocol.registry/publisher-provided (spec/map-of keyword? any?))
(spec/def :io.modelcontextprotocol.registry/official (spec/map-of keyword? any?))

(spec/def :registry/$schema string?)
(spec/def :registry/fileSha256 any?)
(spec/def :registry/id string?)
(spec/def :registry/registryBaseUrl string?)
(spec/def :registry/runtimeHint string?)
(spec/def :registry/identifier string?)
(spec/def :registry/version string?)
(spec/def :registry/websiteUrl string?)
(spec/def :registry/subfolder string?)
(spec/def :registry/name string?)
(spec/def :registry/description string?)
(spec/def :registry/source #{"github"})
(spec/def :registry/url url?)
(spec/def :registry/version (spec/and string? version?))
(spec/def :registry/isRequired boolean?)
(spec/def :registry/isSecret boolean?)
(spec/def :registry/choices (spec/coll-of string?))
(spec/def :registry/default string?)
(spec/def :registry/isRepeated boolean?)
(spec/def :registry/valueHint string?)
(spec/def :registry/value string?)
(spec/def :registry/placeholder string?)
(spec/def :registry/title (fn [s] (and (string? s) (< (count s) 100))))

