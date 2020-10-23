(ns plugins.wrappers.merge-exp
  "Merge expression files."
  (:require [plugins.libs.commons :refer [read-csv write-csv!]]))

(set! *warn-on-reflection* true)

(defn sort-exp-data
  [coll]
  (sort-by :GENE_ID coll))

(defn read-csvs
  [files]
  (map #(sort-exp-data (read-csv %)) files))

(defn merge-exp
  "[[{:GENE_ID 'XXX0' :YYY0 1.2} {:GENE_ID 'XXX1' :YYY1 1.3}]
    [{:GENE_ID 'XXX0' :YYY2 1.2} {:GENE_ID 'XXX1' :YYY3 1.3}]]"
  [all-exp-data]
  (apply map merge all-exp-data))

(defn merge-exp-files!
  "Assumption: all files have the same GENE_ID list, no matter what order."
  [files path]
  (->> (read-csvs files)
       (merge-exp)
       (write-csv! path)))
