(ns registry
  (:require
   [babashka.curl :as curl]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clj-yaml.core :as clj-yaml]
   [clojure.data :as data]
   [clojure.pprint :as pprint]
   [clojure.string :as string]))

(defn fetch-servers [{:keys [cursor]}]
  (->
   (curl/get
    "http://localhost:8080/v0/servers"
    {:query-params (merge {:limit 100}
                          (when cursor {:cursor cursor}))})
   :body
   (json/parse-string true)))

(defn server-detail [{:keys [id name version_detail]}]
  (->
   (curl/get
    (format "http://localhost:8080/v0/servers/%s" id)
    {})
   :body
   (json/parse-string true)))

(comment
  (def servers
    (loop [agg [] opts {}]
      (let [{:keys [servers metadata]} (fetch-servers opts)]
        (if-let [next-cursor (:next_cursor metadata)]
          (recur (concat agg (map server-detail servers)) {:cursor next-cursor})
          (concat agg (map server-detail servers)))))))

(defn get-batch [last]
  (->
   (curl/get "https://registry.modelcontextprotocol.io/v0/servers"
             {:query-params
              (merge
               {:limit 100}
               (when-let [cursor (-> last :metadata :nextCursor)]
                 {:cursor cursor}))})

   :body
   (json/parse-string keyword)))

(defn write-server-file [{{:keys [name version]} :server :as server}]
  (let [f (fs/file (fs/file "community-registry") (format "%s_%s.json" name version))]
    (fs/create-dirs (fs/parent f))
    (spit f (json/generate-string server {:pretty true}))))

(comment
  (loop [result (get-batch nil)]
    (when (-> result :metadata :nextCursor)
      (println "metadata: " (-> result :metadata))
      (doseq [server (:servers result)]
        (write-server-file server))
      (recur (get-batch result)))))

(defn list-community-registry-files
  "Returns a list of all files in the community-registry directory."
  [dir]
  (let [community-registry-dir (fs/file dir)]
    (->> (file-seq community-registry-dir)
         (filter #(.isFile %))
         (map (comp (fn [s] (json/parse-string s keyword)) slurp))
         (into []))))

(defn remote? [server] (-> server :server :remotes seq))
(defn oci? [server] (->> server :server :packages seq (some #(= (:registryType %) "oci"))))
(defn summary [server]
  (let [{:keys [name version]} (->> server :server)]
    (format "%-80s%-20s %4d %s"
            name version
            (count (-> server :server :remotes))
            (->> server :server :packages (map :registryType) (string/join ",")))))

(defn just-domain [url]
  (let [[x] (re-find #"(https://[^/]*)" url)] x))
(comment
  ;; all community pypi servers
  (spit "pypi-urls.txt"
    (->>
      (list-community-registry-files "./community-registry")
      (filter #(not (or (remote? %) (oci? %))))
      (map :server)
      (partition-by :name)
      (map first)
      (filter #(some (fn [m] (= "pypi" (:registryType m))) (:packages %)))
      (map #(format "https://registry.modelcontextprotocol.io/v0/servers/%s/versions/%s" (string/replace (:name %) "/" "%2F") (:version %)))
      (interpose "\n")
      (apply str))))
;; Example usage:
(comment
  (set! *print-level* nil)
  (->> (list-community-registry-files "./community-registry")
       (partition-by #(-> % :server :name))
       (map first)
       (map summary)
       (interpose "\n")
       (doall)
       (apply str)
       (println))
  (->> (list-community-registry-files "./community-registry")
       (partition-by #(-> % :server :name))
       (count))
  (def community-remotes
    (->>
     (list-community-registry-files "./community-registry")
     (filter remote?)
     (mapcat (fn [server] (-> server :server :remotes)))
     (map :url)
     (filter (complement #(string/includes? % "smithery")))
     (map just-domain)
     (into #{})))
  (def registry-remotes
    (->>
     (-> (slurp "./docker-catalog.json")
         (json/parse-string keyword)
         :servers)
     (filter #(= (:type %) "remote"))
     (map (comp :url :remote :server :snapshot))
     (filter identity)
     (map just-domain)
     (into #{})))
  (count registry-remotes)
  (count community-remotes)
  ;; analyze the remote overlaps
  (let [[community registry both] (data/diff community-remotes registry-remotes)]
    (println "community: " (count community-remotes))
    (println "registry: " (count registry-remotes))
    (println "community-only: " (count community))
    (println "registry-only: " (count registry))
    (println "both: " (count both))
    (pprint/pprint both)
    (pprint/pprint registry)
    (pprint/pprint community))

  (->>
   (list-community-registry-files "./community-registry")
   (partition-by #(-> % :server :name))
   (map first)
   (filter (fn [s] (or (remote? s) (oci? s))))
   #_(filter (fn [coll] (string/includes? (-> coll first :server :name) "smithery")))
   (count)))
