(ns expound.suggest
  (:require [clojure.spec.alpha :as s]
            [expound.problems :as problems]))

(defn convert [original replacement]
  (cond
    (and (qualified-symbol? original)
         (simple-symbol? replacement))
    (symbol (name original))

    (and (string? original)
         (simple-symbol? replacement))
    (symbol original)

    (and (keyword? original)
         (simple-keyword? replacement))
    (keyword (name original))

    (and (keyword? original)
         (string? replacement))
    (name original)

    (and (string? original)
         (simple-keyword? replacement))
    (keyword original)

    (and (simple-symbol? original)
         (simple-keyword? replacement))
    (keyword original)

    (and (symbol? original)
         (string? replacement))
    (name original)

    (and (symbol? original)
         (simple-keyword? replacement))
    (keyword (name original))

    ;;;;;;;;;;;;;;; defaults
    (keyword? replacement)
    :keyword

    (string? replacement)
    "string"

    (simple-symbol? replacement)
    'symbol

    (qualified-symbol? replacement)
    'ns/symbol

    ;;(neg-int? replacement)
    ;;-1

    ;;(pos-int? replacement)
    ;;1

    :else
    ::no-value))

(defn abs [x]
  (if (neg? x)
    (* -1 x)
    x))

(defn simplify [seed-vals]
  (if (every? number? seed-vals)
    (first (sort-by abs seed-vals))
    (first (sort-by pr-str seed-vals))))

(defn combine [args in replacement]
  (problems/assoc-in1
   args
   in
   replacement))

;; https://rosettacode.org/wiki/Levenshtein_distance#Iterative_version
(defn levenshtein [w1 w2]
  (letfn [(cell-value [same-char? prev-row cur-row col-idx]
            (min (inc (nth prev-row col-idx))
                 (inc (last cur-row))
                 (+ (nth prev-row (dec col-idx)) (if same-char?
                                                   0
                                                   1))))]
    (loop [row-idx  1
           max-rows (inc (count w2))
           prev-row (range (inc (count w1)))]
      (if (= row-idx max-rows)
        (last prev-row)
        (let [ch2           (nth w2 (dec row-idx))
              next-prev-row (reduce (fn [cur-row i]
                                      (let [same-char? (= (nth w1 (dec i)) ch2)]
                                        (conj cur-row (cell-value same-char?
                                                                  prev-row
                                                                  cur-row
                                                                  i))))
                                    [row-idx] (range 1 (count prev-row)))]
          (recur (inc row-idx) max-rows next-prev-row))))))

(defn step-failed? [suggestion]
  (some #(= ::no-value %)
        (tree-seq coll? seq suggestion)))

(s/def ::type #{::converted ::simplified ::init})
(s/def ::types (s/coll-of ::type))
(s/def ::form any?)
(s/def ::suggestion (s/keys
                     :req [::types ::form]))

;; Lower score is better
(defn score [spec init-form suggestion]
  (s/assert
   ::suggestion
   suggestion)
  (let [{:keys [::form]} suggestion
        failure-multiplier 100
        problem-depth-multiplier 1
        total-failure 1000000000]
    (if (step-failed? form)
      total-failure
      (let [problem-count (or (some->
                               (s/explain-data spec form)
                               ::s/problems
                               count) 0)
            problem-depth   (some->>
                             (s/explain-data spec form)
                             ::s/problems
                             (mapcat
                              :in)
                             (filter int?)
                             (apply +)
                             inc
                             ;; TODO - need to use real path record shere
)
            types-penalty (apply + (map #(case %
                                           ::converted 1
                                           ::simplified 2
                                           ::init 3)
                                        (::types suggestion)))]

        (if (pos? problem-count)
          (/ (* failure-multiplier problem-count)
             (* problem-depth-multiplier problem-depth))
          (+ (levenshtein (pr-str init-form) (pr-str form))
             types-penalty))))))

(defn safe-exercise [!cache spec n]
  (try
    (if-let [xs (get @!cache spec)]
      xs
      (let [xs (doall (s/exercise spec n))]
        (swap! !cache assoc spec xs)
        xs))
    (catch Exception e
      (if (= (.getMessage e)
             "Couldn't satisfy such-that predicate after 100 tries.")
        (safe-exercise !cache spec (dec n))
        (throw e)))))

(defn suggestions* [!cache spec suggestion]
  (s/assert ::suggestion suggestion)
  (let [form (::form suggestion)
        ed (problems/annotate (s/explain-data spec form))
        problems (:expound/problems ed)]
    (mapcat
     (fn [problem]
       (let [most-specific-spec (last (:expound/via problem))
             in (:expound/in problem)
             gen-values (if (set? most-specific-spec)
                          most-specific-spec
                          (map first (safe-exercise !cache most-specific-spec 10)))
             ;; TODO - this is a hack that won't work if we have nested specs
              ;; the generated spec could potentially be half-way up the "path" path
             seed-vals (map #(if-let [r (get-in (s/conform most-specific-spec %)
                                                (:path problem))]
                               r
                               %)
                            gen-values)]
         (into
          (map
           (fn [sugg]
             {::form  sugg
              ::types (conj (::types suggestion) ::converted)})
           (for [seed-val seed-vals]
             (combine form in (convert (:val problem) seed-val))))
          (map
           (fn [sugg]
             {::form  sugg
              ::types (conj (::types suggestion) ::simplified)})
           [(combine form in
                     (simplify seed-vals))]))))
     problems)))

(defn suggestions [spec init-form]
  (let [!cache (atom {})]
    (loop [i 10
           suggestions #{{::form  init-form
                          ::types '(::init)}}]
      (s/assert (s/coll-of ::suggestion) suggestions)
      (if (zero? i)
        (sort-by
         second
         (map #(vector
                %
                (score spec init-form %))
              ;; Don't depend on ordering of suggestions
              ;; TODO - remove
              (shuffle suggestions)))
        (let [invalid-forms (remove (fn [sg] (s/valid? spec (::form sg)))
                                    suggestions)]
          (recur
           (dec i)
           (into suggestions
                 (mapcat
                  (partial suggestions* !cache spec)
                  invalid-forms))))))))

(defn suggestion [spec form]
  (let [best-form (::form (ffirst (suggestions spec form)))]
    (if (s/valid? spec best-form)
      best-form
      ::no-suggestion)))

(defn valid-args [form]
  (if-let [spec (s/get-spec (first form))]
    (let [args-spec (:args spec)
          args (next form)]
      (list* (first form)
             (suggestion args-spec args)))
    ::no-spec-found))