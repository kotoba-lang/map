(ns kotoba.map-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.map :as m]))

(deftest namespaces-list-test
  (is (= 9 (count m/namespaces)))
  (is (every? symbol? m/namespaces)))
