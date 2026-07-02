(ns kotoba.map.mvt-test
  "Parity tests: the first three mirror `#[test]`s in the source
  `kami-map/src/mvt.rs` (Rust) 1:1 (zigzag_decode, tile_local_origin_is_tile_origin,
  empty_pbf_yields_no_features). The rest are round-trip tests this port adds
  since the Rust source had no real-PBF fixture test — a minimal PBF encoder
  is built locally here (test-only) to construct synthetic MVT tiles."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.map.mvt :as mvt]))

;; ---------------------------------------------------------------------------
;; Ported 1:1 from mvt.rs `#[cfg(test)] mod tests`
;; ---------------------------------------------------------------------------

(deftest zigzag-decode-test
  (is (= 0 (mvt/zigzag 0)))
  (is (= -1 (mvt/zigzag 1)))
  (is (= 1 (mvt/zigzag 2)))
  (is (= -2 (mvt/zigzag 3))))

(deftest tile-local-origin-is-tile-origin-test
  (let [t {:z 0 :x 0 :y 0}
        [lng lat] (mvt/tile-local-to-lng-lat 0 0 4096 t)]
    ;; z=0 origin is (-180, +85.0511).
    (is (< (Math/abs (- lng -180.0)) 1e-9))
    (is (< (Math/abs (- lat 85.0511287)) 1e-3))))

(deftest empty-pbf-yields-no-features-test
  (let [r (mvt/decode-layer [] {:z 0 :x 0 :y 0} "anything")]
    (is (empty? (:lines r)))
    (is (empty? (:polygons r)))
    (is (empty? (:points r)))))

;; ---------------------------------------------------------------------------
;; Minimal test-only PBF encoder — builds a synthetic MVT tile so the
;; round-trip (encode here / decode in kotoba.map.mvt) can be verified
;; without a real vendor fixture.
;; ---------------------------------------------------------------------------

(defn- encode-varint [n]
  (loop [n (long n) out []]
    (if (zero? (bit-and n (bit-not 0x7f)))
      (conj out (int n))
      (recur (unsigned-bit-shift-right n 7)
             (conj out (bit-or (bit-and n 0x7f) 0x80))))))

(defn- encode-tag [field wire]
  (encode-varint (bit-or (bit-shift-left field 3) wire)))

(defn- encode-zigzag [n]
  (bit-xor (bit-shift-left (long n) 1) (bit-shift-right (long n) 63)))

(defn- str->bytes [s]
  #?(:clj (mapv #(bit-and % 0xff) (.getBytes ^String s "UTF-8"))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) s)))))

(defn- encode-len-delim [field payload]
  (into (encode-tag field 2) (into (encode-varint (count payload)) payload)))

(defn- encode-string-field [field s]
  (encode-len-delim field (str->bytes s)))

(defn- encode-varint-field [field n]
  (into (encode-tag field 0) (encode-varint n)))

(defn- command-header [cmd count-n]
  (bit-or (bit-shift-left count-n 3) cmd))

(defn- encode-geometry [cmds]
  ;; cmds: vector of raw uint32 command-stream values (headers + zigzag deltas),
  ;; NOT yet varint-encoded — this varint-encodes and packs each one.
  (vec (mapcat encode-varint cmds)))

(defn- move-to+deltas [dx dy]
  [(command-header 1 1) (encode-zigzag dx) (encode-zigzag dy)])

(defn- point-feature-bytes [dx dy]
  (let [geom (encode-geometry (move-to+deltas dx dy))]
    (into (encode-varint-field 3 1) ;; geom_type = POINT
          (encode-len-delim 4 geom))))

(defn- point-feature-with-tags-bytes [dx dy tag-idx-pairs]
  (let [geom (encode-geometry (move-to+deltas dx dy))
        tags (vec (mapcat identity tag-idx-pairs))]
    (-> (encode-len-delim 2 (encode-geometry tags)) ; tags: packed uint32 [k,v,k,v...]
        (into (encode-varint-field 3 1))
        (into (encode-len-delim 4 geom)))))

(defn- layer-bytes [{:keys [name features keys values extent]}]
  (-> (encode-string-field 1 name)
      (into (mapcat #(encode-len-delim 2 %) features))
      (into (mapcat #(encode-string-field 3 %) keys))
      (into (mapcat #(encode-len-delim 4 (encode-string-field 1 %)) values)) ; Value{string_value=1}
      (into (encode-varint-field 5 extent))))

(defn- tile-bytes [layers]
  (vec (mapcat #(encode-len-delim 3 (layer-bytes %)) layers)))

;; ---------------------------------------------------------------------------
;; Round-trip tests
;; ---------------------------------------------------------------------------

(deftest decode-layer-features-point-test
  (let [pbf (tile-bytes [{:name "roads" :features [(point-feature-bytes 2048 2048)]
                           :keys [] :values [] :extent 4096}])
        result (mvt/decode-layer-features pbf {:z 0 :x 0 :y 0} "roads")]
    (is (= 1 (count (:features result))))
    (let [{:keys [geometry properties]} (first (:features result))]
      (is (= :point (:type geometry)))
      (let [[lng lat] (:coordinates geometry)]
        (is (< (Math/abs (- lng 0.0)) 1e-9))
        (is (< (Math/abs (- lat 0.0)) 1e-9)))
      (is (= {} properties)))))

(deftest decode-layer-wrapper-test
  (let [pbf (tile-bytes [{:name "roads" :features [(point-feature-bytes 2048 2048)]
                           :keys [] :values [] :extent 4096}])
        result (mvt/decode-layer pbf {:z 0 :x 0 :y 0} "roads")]
    (is (= 1 (count (:points result))))
    (is (empty? (:lines result)))
    (is (empty? (:polygons result)))))

(deftest decode-layer-features-wrong-name-test
  (let [pbf (tile-bytes [{:name "roads" :features [(point-feature-bytes 2048 2048)]
                           :keys [] :values [] :extent 4096}])
        result (mvt/decode-layer-features pbf {:z 0 :x 0 :y 0} "buildings")]
    (is (empty? (:features result)))))

(deftest decode-feature-tags-test
  (let [pbf (tile-bytes [{:name "roads"
                           :features [(point-feature-with-tags-bytes 2048 2048 [[0 0]])]
                           :keys ["highway"] :values ["primary"] :extent 4096}])
        result (mvt/decode-layer-features pbf {:z 0 :x 0 :y 0} "roads")
        properties (:properties (first (:features result)))]
    (is (= {"highway" "primary"} properties))))

(deftest normalize-bytes-test
  (testing "vector, byte-array and lazy-seq all normalize the same"
    (is (= [255 0 128] (mvt/normalize-bytes [-1 0 -128])))
    (is (= [255 0 128] (mvt/normalize-bytes #?(:clj (byte-array [-1 0 -128])
                                                :cljs [-1 0 -128]))))
    (is (= [255 0 128] (mvt/normalize-bytes (map identity [-1 0 -128]))))))
