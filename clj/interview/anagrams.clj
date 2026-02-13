(ns interview.anagrams)

(def coll ["bat", "tab", "tap", "pat", "cat", "bat"])

(defn normalize-anagram [s]
  (apply str (sort (into [] s))))

(->> coll ;; start with the collection
     (map #(conj [] (normalize-anagram %) %)) ;; make 2-tuple envelope with normalized, original
     (sort-by first) ;; sort by normalized
     (partition-by (comp identity first)) ;; partition into sets
     (map #(map second %))) ;; extract the originals, leave the partitions 

