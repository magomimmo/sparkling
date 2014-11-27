(ns flambo.api-test
  (:import [org.apache.spark HashPartitioner]
           [scala None Some])
  (:use midje.sweet)
  (:require [flambo.api :as f]
            [flambo.conf :as conf]
            [flambo.scalaInterop :as si]                    ;; this is to have the reader macro flambo/tuple defined
            [flambo.destructuring :as fd]
            ))

(defn untuple-all [coll]
  (map f/untuple coll))

(defn seq-values [coll]
  (map f/seq-value coll))

(defn untuple-values [coll]
  (map f/untuple-value coll))

(defn optional-second-values [coll]
  (map f/optional-second-value coll))

(defn some-instance? [cls option]
  (and (instance? Some option) (instance? cls (.get option))))

(f/defsparkfn identity-vec [& args]
  (vec args))

(defn vec$ [x]
  (when x (vec x)))

(facts
  "about spark-context"
  (let [conf (-> (conf/spark-conf)
                 (conf/master "local[*]")
                 (conf/app-name "api-test"))]
    (f/with-context c conf
                    (fact
                      "gives us a JavaSparkContext"
                      (class c) => org.apache.spark.api.java.JavaSparkContext)

                    (fact
                      "creates a JavaRDD"
                      (class (f/parallelize c [1 2 3 4 5])) => org.apache.spark.api.java.JavaRDD)

                    (fact
                      "round-trips a clojure vector"
                      (-> (f/parallelize c [1 2 3 4 5]) f/collect vec) => (just [1 2 3 4 5])))))

(facts :fn
       "about serializable functions"

       (let [myfn (f/fn [x] (* 2 x))]
         (fact
           "inline op returns a serializable fn"
           (type myfn) => :serializable.fn/serializable-fn)

         (fact
           "we can serialize it to a byte-array"
           (class (serializable.fn/serialize myfn)) => (Class/forName "[B"))

         (fact
           "it round-trips back to a serializable fn"
           (type (-> myfn serializable.fn/serialize serializable.fn/deserialize)) => :serializable.fn/serializable-fn)

         (fact :comp
               "it round-trips back to a serializable fn (comp)"
               (type (-> (f/comp myfn) serializable.fn/serialize serializable.fn/deserialize)) => :serializable.fn/serializable-fn)

         (fact :comp
               "it round-trips back to a serializable fn (comp)"
               (type (-> (f/comp myfn myfn myfn myfn) serializable.fn/serialize serializable.fn/deserialize)) => :serializable.fn/serializable-fn)

         #_(fact :comp ;; this won't work due to a limitation in serializable-fn
               "it round-trips back to a serializable fn (comp)"
               (type (-> (f/comp myfn myfn myfn myfn myfn) serializable.fn/serialize serializable.fn/deserialize)) => :serializable.fn/serializable-fn)

         ))

(facts
  "about untupling"

  (fact
    "untuple returns a 2 vector"
    (let [tuple2 (scala.Tuple2. 1 "hi")]
      (f/untuple tuple2) => [1 "hi"]))

  (fact
    "double untuple returns a vector with a key and a 2 vector value"
    (let [double-tuple2 (scala.Tuple2. 1 (scala.Tuple2. 2 "hi"))]
      (f/double-untuple double-tuple2) => [1 [2 "hi"]])))

(facts
  "about transformations"

  (let [conf (-> (conf/spark-conf)
                 (conf/master "local[*]")
                 (conf/app-name "api-test"))]
    (f/with-context c conf
                    (fact
                      "map returns an RDD formed by passing each element of the source RDD through a function"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/map (f/fn [x] (* 2 x)))
                          f/collect
                          vec) => [2 4 6 8 10])

                    (fact
                      "map-to-pair returns an RDD of (K, V) pairs formed by passing each element of the source
                      RDD through a pair function"
                      (-> (f/parallelize c ["a" "b" "c" "d"])
                          (f/map-to-pair (f/fn [x] (f/tuple x 1)))
                          (f/map (fd/tuple-fn identity-vec))
                          f/collect
                          vec) => [["a" 1] ["b" 1] ["c" 1] ["d" 1]])


                    (fact
                      "key-by returns an RDD of (K,V) pairs from an RDD of V elements formed by passing each V through a function to get to K."
                      (-> (f/parallelize c [0 1 2 3 4])
                          (f/key-by even?)
                          (f/map f/untuple)
                          f/collect
                          vec) => (just [[true 0] [false 1] [true 2] [false 3] [true 4]] :in-any-order))


                    (fact
                      "reduce-by-key returns an RDD of (K, V) when called on an RDD of (K, V) pairs"
                      (-> (f/parallelize-pairs c [#flambo/tuple ["key1" 1]
                                                  #flambo/tuple ["key1" 2]
                                                  #flambo/tuple ["key2" 3]
                                                  #flambo/tuple ["key2" 4]
                                                  #flambo/tuple ["key3" 5]])
                          (f/reduce-by-key (f/fn [x y] (+ x y)))
                          f/collect
                          untuple-all
                          vec) => (contains #{["key1" 3] ["key2" 7] ["key3" 5]}))

                    (fact
                      "similar to map, but each input item can be mapped to 0 or more output items;
                      mapping function must therefore return a sequence rather than a single item"
                      (-> (f/parallelize c [["Four score and seven years ago our fathers"]
                                            ["brought forth on this continent a new nation"]])
                          (f/flat-map (f/fn [x] (clojure.string/split (first x) #" ")))
                          f/collect
                          vec) => ["Four" "score" "and" "seven" "years" "ago" "our" "fathers" "brought" "forth" "on" "this" "continent" "a" "new" "nation"])

                    (fact
                      "filter returns an RDD formed by selecting those elements of the source on which func returns true"
                      (-> (f/parallelize c [1 2 3 4 5 6])
                          (f/filter (f/fn [x] (even? x)))
                          f/collect
                          vec) => [2 4 6])


                    (fact
                      "cogroup returns an RDD of (K, (V, W)) pairs with all pairs of elements of each key when called on RDDs of type (K, V) and (K, W)"
                      (let [rdd (f/parallelize-pairs c [#flambo/tuple["key1" 1]
                                                        #flambo/tuple["key2" 2]
                                                        #flambo/tuple["key3" 3]
                                                        #flambo/tuple["key4" 4]
                                                        #flambo/tuple["key5" 5]])
                            other1 (f/parallelize-pairs c [#flambo/tuple["key1" 11]
                                                     #flambo/tuple["key3" 33]
                                                     #flambo/tuple["key4" 44]
                                                     #flambo/tuple["key6" 66]
                                                     #flambo/tuple["key6" 666]])
                            ]
                        (-> (f/cogroup rdd other1)
                            (f/map (fd/cogroup-2-fn (f/fn [k v1 v2] [k [(vec$ v1) (vec$ v2)]])))
                            f/collect
                            vec)) => (just [["key1" [[1] [11]]]
                                            ["key2" [[2] nil]]
                                            ["key3" [[3] [33]]]
                                            ["key4" [[4] [44]]]
                                            ["key5" [[5] nil]]
                                            (just ["key6" (just [nil (just [66 666] :in-any-order)])])
                                            ] :in-any-order))



                    (fact
                      "cogroup returns an RDD of (K, (V, W, X)) pairs with all pairs of elements of each key when called on RDDs of type (K, V), (K, W) and (K,X)"
                      (let [rdd (f/parallelize-pairs c [#flambo/tuple["key1" 1]
                                                  #flambo/tuple["key2" 2]
                                                  #flambo/tuple["key3" 3]
                                                  #flambo/tuple["key4" 4]
                                                  #flambo/tuple["key5" 5]])
                            other1 (f/parallelize-pairs c [#flambo/tuple["key1" 11]
                                                     #flambo/tuple["key3" 33]
                                                     #flambo/tuple["key4" 44]
                                                     #flambo/tuple["key6" 66]
                                                     #flambo/tuple["key6" 666]])
                            other2 (f/parallelize-pairs c [#flambo/tuple["key1" 111]
                                                     #flambo/tuple["key3" 333]
                                                     #flambo/tuple["key5" 555]
                                                     ])
                            ]
                        (-> (f/cogroup rdd other1 other2)
                            (f/map (fd/cogroup-3-fn (f/fn [k v1 v2 v3] [k [(vec$ v1) (vec$ v2) (vec$ v3)]])))
                            f/collect
                            vec)) => (just [["key1" [[1] [11] [111]]]
                                            ["key2" [[2] nil nil]]
                                            ["key3" [[3] [33] [333]]]
                                            ["key4" [[4] [44] nil]]
                                            ["key5" [[5] nil [555]]]
                                            (just ["key6" (just [nil (just [66 666] :in-any-order) nil])])
                                            ] :in-any-order))


                    (fact
                      "join returns an RDD of (K, (V, W)) pairs with all pairs of elements of each key when called on RDDs of type (K, V) and (K, W)"
                      (let [LDATA (f/parallelize-pairs c [#flambo/tuple["key1" [2]]
                                                          #flambo/tuple["key2" [3]]
                                                          #flambo/tuple["key3" [5]]
                                                          #flambo/tuple["key4" [1]]
                                                          #flambo/tuple["key5" [2]]])
                            RDATA (f/parallelize-pairs c [#flambo/tuple["key1" [22]]
                                                          #flambo/tuple["key3" [33]]
                                                          #flambo/tuple["key4" [44]]])
                            ]
                        (-> (f/join LDATA RDATA)
                            (f/map (fd/tuple-value-fn identity-vec))
                            f/collect
                            vec)) => (just [["key3" [5] [33]]
                                            ["key4" [1] [44]]
                                            ["key1" [2] [22]]] :in-any-order))

                    (fact
                      "left-outer-join returns an RDD of (K, (V, W)) when called on RDDs of type (K, V) and (K, W)"
                      (let [LDATA (f/parallelize-pairs c [#flambo/tuple["key1" [2]]
                                                          #flambo/tuple["key2" [3]]
                                                          #flambo/tuple["key3" [5]]
                                                          #flambo/tuple["key4" [1]]
                                                          #flambo/tuple["key5" [2]]])
                            RDATA (f/parallelize-pairs c [#flambo/tuple["key1" [22]]
                                                          #flambo/tuple["key3" [33]]
                                                          #flambo/tuple["key4" [44]]])]
                        (-> (f/left-outer-join LDATA RDATA)
                            (f/map (fd/tuple-value-fn identity-vec :optional-second-value? true))
                            f/collect
                            vec)) => (just [["key3" [5] [33]]
                                            ["key4" [1] [44]]
                                            ["key5" [2] nil]
                                            ["key1" [2] [22]]
                                            ["key2" [3] nil]] :in-any-order))


                    (fact
                      "union concats two RDDs"
                      (let [rdd1 (f/parallelize c [1 2 3 4])
                            rdd2 (f/parallelize c [11 12 13])]
                        (-> (f/union rdd1 rdd2)
                            f/collect
                            vec) => (just [1 2 3 4 11 12 13] :in-any-order)))

                    (fact
                      "union concats more than two RDDs"
                      (let [rdd1 (f/parallelize c [1 2 3 4])
                            rdd2 (f/parallelize c [11 12 13])
                            rdd3 (f/parallelize c [21 22 23])]
                        (-> (f/union rdd1 rdd2 rdd3)
                            f/collect
                            vec) => (just [1 2 3 4 11 12 13 21 22 23] :in-any-order)))

                    (fact
                      "sample returns a fraction of the RDD, with/without replacement,
                      using a given random number generator seed"
                      (-> (f/parallelize c [0 1 2 3 4 5 6 7 8 9])
                          (f/sample false 0.1 2)
                          f/collect
                          vec
                          count) => #(<= 1 %1 2))

                    (fact
                      "combine-by-key returns an RDD by combining the elements for each key using a custom
                      set of aggregation functions"
                      (-> (f/parallelize-pairs c [#flambo/tuple["key1" 1]
                                                  #flambo/tuple["key2" 1]
                                                  #flambo/tuple["key1" 1]])
                          (f/combine-by-key identity + +)
                          f/collect
                          untuple-all
                          vec) => (just [["key1" 2] ["key2" 1]] :in-any-order))

                    (fact
                      "sort-by-key returns an RDD of (K, V) pairs sorted by keys in asc or desc order"
                      (-> (f/parallelize-pairs c [#flambo/tuple[2 "aa"]
                                                  #flambo/tuple[5 "bb"]
                                                  #flambo/tuple[3 "cc"]
                                                  #flambo/tuple[1 "dd"]])
                          (f/sort-by-key compare false)
                          f/collect
                          untuple-all
                          vec) => [[5 "bb"] [3 "cc"] [2 "aa"] [1 "dd"]])

                    (fact
                      "coalesce"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/coalesce 1)
                          f/collect
                          vec) => [1 2 3 4 5])

                    (fact
                      "group-by returns an RDD of items grouped by the grouping function"
                      (-> (f/parallelize c [1 1 2 3 5 8])
                          (f/group-by (f/fn [x] (mod x 2)))
                          f/collect
                          untuple-all
                          seq-values
                          vec
                          ) => (just [[0 [2 8]] [1 [1 1 3 5]]] :in-any-order))

                    (fact
                      "group-by-key"
                      (-> (f/parallelize-pairs c [#flambo/tuple["key1" 1]
                                                  #flambo/tuple["key1" 2]
                                                  #flambo/tuple["key2" 3]
                                                  #flambo/tuple["key2" 4]
                                                  #flambo/tuple["key3" 5]])
                          f/group-by-key
                          f/collect
                          untuple-all
                          seq-values
                          vec) => (just [["key3" [5]] ["key1" [1 2]] ["key2" [3 4]]] :in-any-order))

                    (fact
                      "flat-map-to-pair"
                      (-> (f/parallelize c [["Four score and seven"]
                                            ["years ago"]])
                          (f/flat-map-to-pair (f/fn [x] (map (fn [y] (f/tuple y 1))
                                                             (clojure.string/split (first x) #" "))))
                          (f/map f/untuple)
                          f/collect
                          vec) => [["Four" 1] ["score" 1] ["and" 1] ["seven" 1] ["years" 1] ["ago" 1]])

                    (fact
                      "map-partition"
                      (-> (f/parallelize c [0 1 2 3 4])
                          (f/map-partition (f/fn [it] (map identity (iterator-seq it))))
                          f/collect) => [0 1 2 3 4])

                    (fact
                      "map-partition-with-index"
                      (-> (f/parallelize c [0 1 2 3 4])
                          (f/repartition 4)
                          (f/map-partition-with-index (f/fn [i it] (.iterator (map identity [i (iterator-seq it)]))))
                          (f/collect)
                          ((fn [key-value-list]
                             (let [key-value-map (apply array-map key-value-list)]
                               [(keys key-value-map)
                                (apply concat (vals key-value-map))
                                ]
                               )))
                          ) => (just [[0 1 2 3]             ;; partitions
                                      (just [0 1 2 3 4] :in-any-order) ;; values in partitions
                                      ]))

                    (fact
                      "cartesian creates cartesian product of two RDDS"
                      (let [rdd1 (f/parallelize c [1 2])
                            rdd2 (f/parallelize c [5 6 7])]
                        (-> (f/cartesian rdd1 rdd2)
                            f/collect
                            vec) => (just [#flambo/tuple[1 5] #flambo/tuple[1 6] #flambo/tuple[1 7] #flambo/tuple[2 5] #flambo/tuple[2 6] #flambo/tuple[2 7]] :in-any-order)))


                    (future-fact "repartition returns a new RDD with exactly n partitions")

                    )))

(facts
  "about actions"

  (let [conf (-> (conf/spark-conf)
                 (conf/master "local[*]")
                 (conf/app-name "api-test"))]
    (f/with-context c conf
                    (fact
                      "aggregates elements of RDD using a function that takes two arguments and returns one,
                      return type is a value"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/reduce (f/fn [x y] (+ x y)))) => 15)

                    (fact
                      "count-by-key returns a hashmap of (K, int) pairs with the count of each key; only available on RDDs of type (K, V)"
                      (-> (f/parallelize-pairs c [#flambo/tuple["key1" 1]
                                                  #flambo/tuple["key1" 2]
                                                  #flambo/tuple["key2" 3]
                                                  #flambo/tuple["key2" 4]
                                                  #flambo/tuple["key3" 5]])
                          (f/count-by-key)) => {"key1" 2 "key2" 2 "key3" 1})

                    (fact
                      "count-by-value returns a hashmap of (V, int) pairs with the count of each value"
                      (-> (f/parallelize c [["key1" 11]
                                            ["key1" 11]
                                            ["key2" 12]
                                            ["key2" 12]
                                            ["key3" 13]])
                          (f/count-by-value)) => {["key1" 11] 2, ["key2" 12] 2, ["key3" 13] 1})

                    (fact
                      "values returns the values (V) of a hashmap of (K, V) pairs"
                      (-> (f/parallelize-pairs c [#flambo/tuple["key1" 11]
                                                  #flambo/tuple["key1" 11]
                                                  #flambo/tuple["key2" 12]
                                                  #flambo/tuple["key2" 12]
                                                  #flambo/tuple["key3" 13]])
                          (f/values)
                          (f/collect)
                          vec) => [11, 11, 12, 12, 13])

                    (fact
                      "keys returns the keys (K) of a hashmap of (K, V) pairs"
                      (-> (f/parallelize-pairs c [#flambo/tuple["key1" 11]
                                                  #flambo/tuple["key1" 11]
                                                  #flambo/tuple["key2" 12]
                                                  #flambo/tuple["key2" 12]
                                                  #flambo/tuple["key3" 13]])
                          (f/keys)
                          (f/collect)
                          vec) => (just ["key1" "key1" "key2" "key2" "key3"] :in-any-order))


                    (fact
                      "foreach runs a function on each element of the RDD, returns nil; this is usually done for side effcts"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/foreach (f/fn [x] x))) => nil)

                    (fact
                      "foreach-partition runs a function on each partition iterator of RDD; basically for side effects like foreach"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/foreach-partition identity)) => nil)

                    (fact
                      "fold returns aggregate each partition, and then the results for all the partitions,
                      using a given associative function and a neutral 'zero value'"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/fold 0 (f/fn [x y] (+ x y)))) => 15)

                    (fact
                      "first returns the first element of an RDD"
                      (-> (f/parallelize c [1 2 3 4 5])
                          f/first) => 1)

                    (fact
                      "count return the number of elements in an RDD"
                      (-> (f/parallelize c [["a" 1] ["b" 2] ["c" 3] ["d" 4] ["e" 5]])
                          f/count) => 5)

                    (fact
                      "collect returns all elements of the RDD as an array at the driver program"
                      (-> (f/parallelize c [[1] [2] [3] [4] [5]])
                          f/collect
                          vec) => [[1] [2] [3] [4] [5]])

                    (fact
                      "distinct returns distinct elements of an RDD"
                      (-> (f/parallelize c [1 2 1 3 4 5 4])
                          f/distinct
                          f/collect
                          vec) => (contains #{1 2 3 4 5}))

                    (fact
                      "distinct returns distinct elements of an RDD with the given number of partitions"
                      (-> (f/parallelize c [1 2 1 3 4 5 4])
                          (f/distinct 2)
                          f/collect
                          vec) => (contains #{1 2 3 4 5}))

                    (fact
                      "take returns an array with the first n elements of an RDD"
                      (-> (f/parallelize c [1 2 3 4 5])
                          (f/take 3)) => [1 2 3])

                    (fact
                      "glom returns an RDD created by coalescing all elements within each partition into a list"
                      (-> (f/parallelize c [1 2 3 4 5 6 7 8 9 10] 2)
                          f/glom
                          f/collect
                          vec) => (just [[1 2 3 4 5] [6 7 8 9 10]] :in-any-order))

                    (fact
                      "cache persists this RDD with a default storage level (MEMORY_ONLY)"
                      (let [cache (-> (f/parallelize c [1 2 3 4 5])
                                      (f/cache))]
                        (-> cache
                            f/collect) => [1 2 3 4 5]))

                    (fact
                      "histogram uses bucketCount number of evenly-spaced buckets"
                      (-> (f/parallelize c [1.0 2.2 2.6 3.3 3.5 3.7 4.4 4.8 5.5 6.0])
                          (f/histogram 5)) => [[1.0 2.0 3.0 4.0 5.0 6.0] [1 2 3 2 2]])

                    (fact
                      "histogram uses the provided buckets"
                      (-> (f/parallelize c [1.0 2.2 2.6 3.3 3.5 3.7 4.4 4.8 5.5 6.0])
                          (f/histogram [1.0 4.0 6.0])) => [6 4])
                    )))


(facts
  "about other stuff"

  (let [conf (-> (conf/spark-conf)
                 (conf/master "local[*]")
                 (conf/app-name "api-test"))]
    (f/with-context c conf
                    (fact
                      "comp is the same as clojure.core/comp but returns a serializable fn"
                      ((f/comp (partial * 2) inc) 1) => 4
                      )

                    (fact
                      "partitions returns a vec of partitions for a given RDD"
                      (-> (f/parallelize c [1 2 3 4 5 6 7 8 9 10] 2)
                          f/partitions
                          count) => 2)

                    (fact
                      "partition-by partitions a given RDD according to the partitioning-fn using a hash partitioner."
                      (-> (f/parallelize c [1 2 3 4 5 6 7 8 9 10] 1)
                          (f/map-to-pair (f/fn [x] (f/tuple x x)))
                          (f/partition-by (f/hash-partitioner-fn 2))
                          f/glom
                          f/collect
                          vec) => (just [[#flambo/tuple[2 2] #flambo/tuple[4 4] #flambo/tuple[6 6] #flambo/tuple[8 8] #flambo/tuple[10 10]]
                                         [#flambo/tuple[1 1] #flambo/tuple[3 3] #flambo/tuple[5 5] #flambo/tuple[7 7] #flambo/tuple[9 9]]] :in-any-order))


                    (fact
                      "partition-by returns an RDD with a hash partitioner."
                      (-> (f/parallelize c [1 2 3 4 5 6 7 8 9 10] 1)
                          (f/map-to-pair (f/fn [x] (f/tuple x x)))
                          (f/partition-by (f/hash-partitioner-fn 2))
                          (.rdd)
                          (.partitioner)
                          ) => #(some-instance? HashPartitioner %1))

                    #_(fact
                       "partition-by partitions a given RDD according to the partitioning-fn using a hash partitioner."
                       (-> (f/parallelize c [[{:a 1 :b 1} 11]  ;; for me, (mod (hash 1) 2) and (mod (hash 3) 2) return 0 and 1 respectively, resulting in splitting the rdd in two partitions based upon :b
                                             [{:a 2 :b 1} 11]
                                             [{:a 3 :b 1} 12]
                                             [{:a 4 :b 3} 12]
                                             [{:a 5 :b 3} 13]] 1)
                           (f/partition-by (f/hash-partitioner-fn (f/fn [key] (:b key)) 2))
                           f/glom
                           f/collect
                           vec) => (just [[[{:a 1 :b 1} 11]
                                           [{:a 2 :b 1} 11]
                                           [{:a 3 :b 1} 12]]
                                          [[{:a 4 :b 3} 12]
                                           [{:a 5 :b 3} 13]]] :in-any-order))

                    )))