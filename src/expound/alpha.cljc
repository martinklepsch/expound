(ns expound.alpha
  "Drop-in replacement for clojure.spec.alpha, with
  human-readable `explain` function"
  (:require [clojure.data]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.walk :as walk]
            #?(:cljs [goog.string])
            [clojure.pprint :as pprint])
  (:refer-clojure :exclude [format]))

;;;;;; specs   ;;;;;;

(s/def ::singleton (s/coll-of any? :count 1))

;;;;;; private ;;;;;;

(def header-size 35)
(def section-size 25)
(def indent-level 2)

(def headers {:problem/missing-key   "Spec failed"
              :problem/not-in-set    "Spec failed"
              :problem/missing-spec  "Missing spec"
              :problem/regex-failure "Syntax error"
              :problem/unknown       "Spec failed"})

#?(:cljs
   (defn format [fmt & args]
     (apply goog.string/format fmt args))
   :clj (def format clojure.core/format))

(defn pprint-str
  "Returns the pretty-printed string"
  [x]
  (pprint/write x :stream nil))

(defn no-trailing-whitespace
  "Given an potentially multi-line string, returns that string with all
  trailing whitespace removed."
  [s]
  (->> s
       string/split-lines
       (map string/trimr)
       (string/join "\n")))

(defn indent
  "Given an potentially multi-line string, returns that string indented by
   'indent-level' spaces. Optionally, can indent first line and other lines
   different amounts."
  ([s]
   (indent indent-level s))
  ([indent-level s]
   (indent indent-level indent-level s))
  ([first-line-indent rest-lines-indent s]
   (let [[line & lines] (string/split-lines (str s))]
     (string/join "\n"
                  (into [(str (apply str (repeat first-line-indent " ")) line)]
                        (map #(str (apply str (repeat rest-lines-indent " ")) %) lines))))))

(defn prefix-path?
  "True if partial-path is a prefix of full-path."
  [partial-path full-path]
  (and (< (count partial-path) (count full-path))
       (= partial-path
          (subvec full-path 0 (count partial-path)))))

(defrecord KeyPathSegment [key])

(defrecord KeyValuePathSegment [idx])

(defn kps? [x]
  (instance? KeyPathSegment x))

(defn kvps? [x]
  (instance? KeyValuePathSegment x))

(def mapv-indexed (comp vec map-indexed))

(defn walk-with-path
  "Recursively walks data structure. Passes both the path and current
   value to 'inner' and 'outer' functions"
  ([inner outer path form]
   (cond
     (list? form)   (outer path (apply list (map-indexed (fn [idx x] (inner (conj path idx) x)) form)))
     (vector? form) (outer path (mapv-indexed (fn [idx x] (inner (conj path idx) x)) form))
     (record? form) (outer path form)
     (map? form)    (outer path (reduce (fn [m [idx [k v]]]
                                          (conj m
                                                (outer
                                                 (conj path (->KeyValuePathSegment idx))
                                                 [(outer (conj path (->KeyPathSegment k)) k) (inner (conj path k) v)])))
                                        {}
                                        (map vector (range) (seq form))))
     :else          (outer path form))))

(defn postwalk-with-path
  ([f form] (postwalk-with-path f [] form))
  ([f path form]
   (walk-with-path (partial postwalk-with-path f) f path form)))

(defn kps-path?
  "True if path points to a key"
  [x]
  (and (vector? x)
       (kps? (last x))))

(defn kvps-path?
  "True if path points to a key/value pair"
  [x]
  (and (vector? x)
       (some kvps? x)))

(defn summary-form
  "Given a form and a path to highlight, returns a data structure that marks
   the highlighted and irrelevant data"
  [form highlighted-path]
  (postwalk-with-path
   (fn [path x]
     (cond
       (= ::irrelevant x)
       ::irrelevant

       (= ::relevant x)
       ::relevant

       (and (kvps-path? path) (= path highlighted-path))
       [::kv-relevant ::kv-relevant]

       (= path highlighted-path)
       ::relevant

       (prefix-path? path highlighted-path)
       x

       (kps-path? path)
       x

       (kvps-path? path)
       x

       :else
       ::irrelevant))
   form))

;; FIXME - this function is not intuitive.
(defn highlight-line
  [prefix replacement]
  (let [max-width (apply max (map #(count (str %)) (string/split-lines replacement)))]
    (indent (count (str prefix))
            (apply str (repeat max-width "^")))))

(defn value-in
  "Similar to get-in, but works with paths that reference map keys"
  [form in]
  (let [[k & rst] in]
    (cond
      (empty? in)
      form

      (and (map? form) (kps? k))
      (:key k)

      (and (map? form) (kvps? k))
      (nth (seq form) (:idx k))

      (associative? form)
      (recur (get form k) rst)

      (int? k)
      (recur (nth form k) rst))))

;; TODO - perhaps a more useful API would be an API on 'problems'?
;; - group problems
;; - print out data structure given problem
;; - categorize problem
(defn highlighted-form
  "Given a form and a path into that form, returns a pretty printed
   string that highlights the value at the path."
  [form path]
  (let [value-at-path (value-in form path)
        relevant (str "(" ::relevant "|(" ::kv-relevant "\\s+" ::kv-relevant "))")
        regex (re-pattern (str "(.*)" relevant ".*"))
        s (binding [*print-namespace-maps* false] (pprint-str (walk/prewalk-replace {::irrelevant '...} (summary-form form path))))
        [line prefix & _more] (re-find regex s)
        highlighted-line (-> line
                             (string/replace (re-pattern relevant) (indent 0 (count prefix) (pprint-str value-at-path)))
                             (str "\n" (highlight-line prefix (pprint-str value-at-path))))]
    ;;highlighted-line
    (no-trailing-whitespace (string/replace s line highlighted-line))))

(defn value-in-context
  "Given a form and a path into that form, returns a string
   that helps the user understand where that path is located
   in the form"
  [form path]
  (let [val (value-in form path)]
    (if (= form val)
      (binding [*print-namespace-maps* false] (pr-str val))
      (highlighted-form form path))))

(defn spec-str [spec]
  (if (keyword? spec)
    (format
     "%s:\n%s"
     spec
     (indent (pprint-str (s/form spec))))
    (pprint-str (s/form spec))))

(defn specs
  "Given a collection of problems, returns the specs for those problems, with duplicates removed"
  [problems]
  (->> problems
       (map :via)
       flatten
       distinct))

(defn specs-str [problems]
  (->> problems
       specs
       reverse
       (map spec-str)
       (string/join "\n")))

(defn named? [x]
  #?(:clj (instance? clojure.lang.Named x)
     :cljs (implements? cljs.core.INamed x)))

(defn pr-pred [pred]
  (if (named? pred)
    (name pred)
    (pr-str pred)))

(defn preds [preds]
  (string/join "\n\nor\n\n" (map (comp indent pr-pred) preds)))

(defn insufficient-input [val path problem]
  (format
   "%s

should have additional elements. The next element is named `%s` and satisfies

%s"
   (indent (value-in-context val path))
   (pr-str (first (:path problem)))
   (indent (pr-pred (:pred problem)))))

(defn extra-input [val path]
  (format
   "Value has extra input

%s"
   (indent (value-in-context val path))))

(defn missing-key [form]
  #?(:cljs (let [[contains _arg key-keyword] form]
             (s/assert #{'contains?} contains)
             key-keyword)
     ;; FIXME - this duplicates the structure of how
     ;; spec builds the 'contains?' function. Extract this into spec
     ;; and use conform instead of this ad-hoc validation.
     :clj (let [[fn _ [contains _arg key-keyword] & rst] form]
            (s/assert #{'clojure.core/contains?} contains)
            key-keyword)))

(defn label
  ([size]
   (apply str (repeat size "-")))
  ([size s]
   (let [prefix (str "-- " s " ")
         chars-left (- size (count prefix))]
     (str prefix (apply str (repeat chars-left "-"))))))

(def header-label (partial label header-size))
(def section-label (partial label section-size))

(defn relevant-specs [problems]
  (format
   "%s

%s"
   (section-label "Relevant specs")
   (specs-str problems)))

(defn multi-spec-parts [spec]
  (let [[_multi-spec mm retag]  (s/form spec)]
    {:mm mm :retag retag}))

(defn missing-spec? [problem]
  (= "no method" (:reason problem)))

(defn not-in-set? [problem]
  (set? (:pred problem)))

(defn missing-key? [problem]
  #?(:cljs
     (let [pred (:pred problem)]
       (and (list? pred)
            (map? (:val problem))
            (= 'contains? (first pred))))
     :clj
     (let [pred (:pred problem)]
       (and (seq? pred)
            (map? (:val problem))
            (let [[fn _ [contains _] & rst] pred]
              (and
               (= 'clojure.core/fn fn)
               (= 'clojure.core/contains? contains)))))))

(defn regex-failure? [problem]
  (contains? #{"Insufficient input" "Extra input"} (:reason problem)))

(defn no-method [val path problem]
  (let [sp (s/spec (last (:via problem)))
        {:keys [mm retag]} (multi-spec-parts sp)]
    (format
     "Cannot find spec for

 %s

 Spec multimethod:      `%s`
 Dispatch function:     `%s`
 Dispatch value:        `%s`
 "
     (indent (value-in-context val path))
     (pr-str mm)
     (pr-str retag)
     (pr-str (if retag (retag (value-in val path)) nil)))))

(defmulti problem-group-str (fn [type _val _path _problems] type))

(defmethod problem-group-str :problem/missing-key [_type val path problems]
  (assert (apply = (map :val problems)) (str "All values should be the same, but they are " problems))
  (format
   "%s

%s

should contain keys: %s

%s"
   (header-label "Spec failed")
   (indent (value-in-context val path))
   (string/join "," (map #(str "`" (missing-key (:pred %)) "`") problems))
   (relevant-specs problems)))

(defmethod problem-group-str :problem/not-in-set [_type val path problems]
  (assert (apply = (map :val problems)) (str "All values should be the same, but they are " problems))
  (s/assert ::singleton problems)
  (format
   "%s

%s

should be one of: %s

%s"
   (header-label "Spec failed")
   (indent (value-in-context val path))
   (string/join "," (map #(str "`" % "`") (:pred (first problems))))
   (relevant-specs problems)))

(defmethod problem-group-str :problem/missing-spec [_type val path problems]
  (s/assert ::singleton problems)
  (format
   "%s

%s

%s"
   (header-label "Missing spec")
   (no-method val path (first problems))
   (relevant-specs problems)))

(defmethod problem-group-str :problem/regex-failure [_type val path problems]
  (s/assert ::singleton problems)
  (let [problem (first problems)]
    (format
     "%s

%s

%s"
     (header-label "Syntax error")
     (case (:reason problem)
       "Insufficient input" (insufficient-input val path problem)
       "Extra input" (extra-input val path))
     (relevant-specs problems))))

(defmethod problem-group-str :problem/unknown [_type val path problems]
  (assert (apply = (map :val problems)) (str "All values should be the same, but they are " problems))
  (format
   "%s

%s

should satisfy

%s

%s"
   (header-label "Spec failed")
   (indent (value-in-context val path))
   (preds (map :pred problems))
   (relevant-specs problems)))

(defn problem-type [problem]
  (cond
    (not-in-set? problem)
    :problem/not-in-set

    (missing-key? problem)
    :problem/missing-key

    (missing-spec? problem)
    :problem/missing-spec

    (regex-failure? problem)
    :problem/regex-failure

    :else
    :problem/unknown))

(defn leaf-problems
  "Given a collection of problems, returns only those problems with data on the 'leaves' of the data"
  [problems]
  (let [paths-to-data (into #{} (map :in1 problems))]
    (remove
     (fn [problem]
       (some
        (fn [path]
          (prefix-path? (:in1 problem) path))
        paths-to-data))
     problems)))

(defn path+problem-type->problems
  "Returns problems grouped by path (i.e. the 'in' key) then and then problem-type"
  [problems]
  (group-by (juxt :in1 problem-type) problems))

(defn in-with-kps [form in in1]
  (let [[k & rst] in
        [idx & rst2] rst]
    (cond
      (empty? in)
      in1

      ;; detect a `:in` path that points at a key in a map-of spec
      (and (map? form)
           (= 0 idx)
           (empty? rst2)
           (or (not (associative? (get form k)))
               (not (contains? (get form k) idx))))
      (conj in1 (->KeyPathSegment k))

      ;; detect a `:in` path that points at a value in a map-of spec
      (and (map? form)
           (= 1 idx)
           (empty? rst2)
           (or (not (associative? (get form k)))
               (not (contains? (get form k) idx))))
      (recur (get form k) rst2 (conj in1 k))

      ;; detech a `:in` path that points to a key/value pair in a coll-of spec
      (and (map? form) (int? k) (empty? rst))
      (conj in1 (->KeyValuePathSegment k))

      (associative? form)
      (recur (get form k) rst (conj in1 k))

      (int? k)
      (recur (nth form k) rst (conj in1 k)))))

(defn adjust-in [form problem]
  (assoc problem :in1 (in-with-kps form (:in problem) [])))

(defn compare-path-segment [x y]
  (cond
    (and (int? x) (kvps? y))
    (compare x (:idx y))

    (and (kvps? x) (int? y))
    (compare (:idx x) y)

    (and (kps? x) (not (kps? y)))
    -1

    (and (not (kps? x)) (kps? y))
    1

    (and (vector? x) (vector? y))
    (first (filter #(not= 0 %) (map compare-path-segment x y)))

    :else
    (compare x y)))

(defn compare-paths [path1 path2]
  (first (filter #(not= 0 %) (map compare-path-segment path1 path2))))

(defn safe-sort-by
  "Same as sort-by, but if an error is raised, returns the original unsorted collection"
  [key-fn comp coll]
  (try
    (sort-by key-fn comp coll)
    (catch #?(:cljs :default
              :clj Exception) e coll)))

;;;;;; public ;;;;;;

(defn expound-str
  "Given a spec and a value that fails to conform, returns a human-readable explanation as a string."
  [spec form]
  (let [problems (::s/problems (s/explain-data spec form))
        _ (doseq [problem problems]
            (s/assert (s/nilable #{"Insufficient input" "Extra input" "no method"}) (:reason problem)))
        leaf-problems (leaf-problems (map (partial adjust-in form) (::s/problems (s/explain-data spec form))))

        _ (assert (every? :in1 leaf-problems) leaf-problems)
        ;; We attempt to sort the problems by path, but it's not feasible to sort in
        ;; all cases, since paths could contain arbitrary user-defined data structures.
        ;; If there is an error, we just give up on sorting.
        grouped-problems (safe-sort-by first compare-paths
                                       (path+problem-type->problems leaf-problems))]
    (if (empty? problems)
      "Success!\n"
      (let [problems-str (string/join "\n\n" (for [[[in1 type] problems] grouped-problems]
                                               (problem-group-str type form in1 problems)))]
        (no-trailing-whitespace
         (format
          "%s

%s
Detected %s %s"
          problems-str
          (section-label)
          (count grouped-problems)
          (if (= 1 (count grouped-problems)) "error" "errors")))))))

(defn expound [spec form]
  (println (expound-str spec form)))
