(ns catalog-to-registry-t
  (:require
   [catalog-to-registry :refer [create-config-input add-variable]]
   [clojure.spec.alpha :as spec]
   [clojure.string :as string]))

(create-config-input {} {:name "elasticsearch"
                         :description "Configure the connection to ElasticSearch"
                         :type "object"
                         :properties
                         {:url
                          {:type "string"}}
                         :required ["url"]})

(create-config-input {} {:name "atlassian"
                         :description "Configure the connection to ElasticSearch"
                         :type "object"
                         :properties
                         {:confluence
                          {:type "object"
                           :properties
                           {:url {:type "string" :description "stuff"}}}
                          :jira
                          {:type "object"
                           :properties
                           {:url {:type "string"}} } } })

(add-variable {"hello" {}} "{{hello}} hello")
(add-variable {"hello" {}} "{{hello|volume|into}} hello")

(string/split "aalsjdf" #"\|")
(string/split "{{aalsjdf}}" #"\|")


(spec/explain :registry/ServerDetail {:name "hello" :description "description" :version "v0.1.0"})
(spec/explain :registry/ServerDetail {:name "hello" :description "description" :version "v0.1.0"
                              :packages [{:registryType "oci"
                                          :transport {:type "stdio"}
                                          :version ""
                                          :identifier ""}]})
(spec/explain :registry/ServerDetail {:name "hello" :description "description" :version "v0.1.0"
                              :remotes [{:type "sse" :url ""}]})
(spec/explain :registry/repository {:source "github" :url ""})
(spec/explain :registry/ServerDetail {:name "hello" :description "description" :version "v0.1.0"
                              :remotes [{:type "sse"
                                         :url ""
                                         :headers
                                         [{:name "Authorization" :value "{something}"
                                           :variables
                                           {"something" {:default "blah"}}}]}]
                              :_meta {:io.modelcontextprotocol.registry/publisher-provided {:blah "whatever"}
                                      :io.modelcontextprotocol.registry/official {:blah1 "hey"}
                                      :other "whatever"}})
(spec/explain :registry/ServerDetail {:name "hello" :description "description" :version "v0.1.0"
                              :packages [{:registryType "oci"
                                          :transport {:type "stdio"}
                                          :version ""
                                          :identifier ""
                                          :environmentVariables [{:name "HELLO"}]
                                          :packageArguments [{:type "named" :valueHint "volumes" :name "blah"}
                                                             {:type "positional" :value "hello"}]
                                          :runtimeArguments []}]})

(spec/conform :registry/Argument {:type "named" :name "hello"})
(spec/conform :registry/Argument {:type "positional" :valueHint ""})
(spec/conform :registry/Argument {:type "positional" :value "{hello}" :variables {"hello" {:default "blah"}}})

