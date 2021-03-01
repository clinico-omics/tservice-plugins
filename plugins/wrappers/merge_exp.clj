(ns plugins.wrappers.merge-exp
  "Merge expression files."
  (:require [plugins.libs.commons :refer [read-csv vec-remove write-csv-by-cols!]]))

(set! *warn-on-reflection* true)

(defn merge-exp
  "[[{:GENE_ID 'XXX0' :YYY0 1.2} {:GENE_ID 'XXX1' :YYY1 1.3}]
    [{:GENE_ID 'XXX0' :YYY2 1.2} {:GENE_ID 'XXX1' :YYY3 1.3}]]"
  [all-exp-data]
  (apply map merge all-exp-data))

(defn read-csvs
  [files id]
  (map #(sort-by id (read-csv %)) files))

(defn reorder
  [data id]
  (let [cols (vec (sort (keys (first data))))]
    (cons id (vec-remove (.indexOf cols id) cols))))

(defn write-csv-by-ordered-cols!
  [path row-data id]
  (let [cols (reorder row-data id)]
    (write-csv-by-cols! path row-data cols)))

(defn merge-exp-files!
  "Assumption: all files have the same `id` column, no matter what order."
  [files path id]
  (->> (read-csvs files id)
       (merge-exp)
       (write-csv-by-ordered-cols! path id)))
