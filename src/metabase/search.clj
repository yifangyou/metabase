(ns metabase.search
  (:require [clojure.core.memoize :as memoize]
            [clojure.string :as str]
            [metabase.models.table :refer [Table]]
            [schema.core :as s]))

;;; Utility functions

(s/defn normalize :- s/Str
  [query :- s/Str]
  (str/lower-case query))

(s/defn tokenize :- [s/Str]
  "Break a search `query` into its constituent tokens"
  [query :- s/Str]
  (filter seq
          (str/split query #"\s+")))

(def largest-common-subseq-length
  (memoize/fifo
   (fn
     ([eq xs ys]
      (largest-common-subseq-length eq xs ys 0))
     ([eq xs ys tally]
      (if (or (zero? (count xs))
              (zero? (count ys)))
        tally
        (max
         (if (eq (first xs)
                 (first ys))
           (largest-common-subseq-length eq (rest xs) (rest ys) (inc tally))
           tally)
         (largest-common-subseq-length eq xs (rest ys) 0)
         (largest-common-subseq-length eq (rest xs) ys 0)))))))

;;; Model setup

(defn- model-name->class
  [model-name]
  (Class/forName (format "metabase.models.%s.%sInstance" model-name (str/capitalize model-name))))

(defmulti searchable-columns-for-model
  "The columns that will be searched for the query."
  {:arglists '([model])}
  class)

(defmethod searchable-columns-for-model :default
  [_]
  [:name])

(defmethod searchable-columns-for-model (class Table)
  [_]
  [:name
   :display_name])

;;; Scoring

(defn- hits->ratio
  [hits total]
  (/ hits total))

(defn- matches?
  [search-term haystack]
  (str/includes? haystack search-term))

(defn- matches-in?
  [term haystack-tokens]
  (some #(matches? term %) haystack-tokens))

(defn- score-with
  [scoring-fns tokens result]
  (->>
   (for [column (searchable-columns-for-model (model-name->class (:model result)))
         :let [haystack (-> result
                          (get column)
                          normalize
                          tokenize)]]
     (map #(% tokens haystack) scoring-fns))
   (map (partial apply max))
   (map #(hits->ratio % (count tokens)))))

(def ^:private consecutivity-scorer
  (partial largest-common-subseq-length matches?))

(def ^:private exact-match-scorer
  (partial largest-common-subseq-length =))

(defn- total-occurrences-scorer
  [tokens haystack]
  (->> tokens
       (map #(if (matches-in? % haystack) 1 0))
       (reduce +)))

(s/defn score :- s/Num
  [query :- s/Str, result :- s/Any] ;; TODO. It's a map with result columns + :model
  (let [query-tokens (tokenize query)]
    (reduce +
            (score-with [consecutivity-scorer
                         total-occurrences-scorer
                         exact-match-scorer]
                        query-tokens
                        result))))
