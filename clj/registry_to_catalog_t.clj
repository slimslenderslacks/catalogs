(ns registry-to-catalog-t
  (:require
   [clj-yaml.core]
   [catalog-to-registry :refer [transform-all-servers]]
   [registry-to-catalog :refer [transform-to-docker] :as a]))

(comment
  (def docker-servers (:registry (clj-yaml.core/parse-string (slurp "/Users/slim/.docker/mcp/catalogs/docker-mcp.yaml"))))
  (def transformed-servers (transform-all-servers docker-servers))
  (def atlassian
    (->> transformed-servers
         (filter #(= (:name %) "com.docker.mcp/atlassian"))
         first)))

(dissoc
  (transform-to-docker atlassian)
  :tools)
(a/extract-server-name (:name atlassian))
(a/collect-variables atlassian)
