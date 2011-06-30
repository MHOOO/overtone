(ns
    ^{:doc "UGens, or Unit Generators, are the functions that act as DSP nodes in the synthesizer definitions used by SuperCollider.  We generate the UGen functions based on hand written metadata about each ugen (ugen directory). (Eventually we hope to get this information dynamically from the server.)"
      :author "Jeff Rose & Christophe McKeon"}
  overtone.sc.ugen
  (:refer-clojure :exclude (deftype))

  (:use
   clojure.pprint
   overtone.sc.ugen.defaults
   [overtone util]
   [overtone.sc buffer bus]
   [overtone.sc.ugen special-ops common categories]
   [clojure.contrib.types :only (deftype)]
   [clojure.contrib.generic :only (root-type)])
  (:require
   overtone.sc.core
   [overtone.sc.ugen.doc :as doc]
   [clojure.set :as set]
   [clojure.contrib.generic.arithmetic :as ga]
   [clojure.contrib.generic.comparison :as gc]
   [clojure.contrib.generic.math-functions :as gm]))

(def UGEN-SPEC-EXPANSION-MODES
  {:not-expanded false
   :array false
   :append-sequence false
   :append-sequence-set-num-outs false
   :num-outs false
   :action false
   :as-ar true ;; This should still expand right?
   :standard true
   })

(def INF Float/POSITIVE_INFINITY)

(defn normalize-ugen-name
  "Normalizes SuperCollider and overtone-style names to squeezed lower-case."
  [n]
  (.replaceAll (.toLowerCase (str n)) "[-|_]" ""))

(defn overtone-ugen-name
  "A basic camelCase to with-dash name converter tuned to convert SuperCollider
  names to Overtone names. Most likely needs improvement."
  [n]
  (let [n (.replaceAll n "([a-z])([A-Z])" "$1-$2")
        n (.replaceAll n "([A-Z])([A-Z][a-z])" "$1-$2")
        n (.replaceAll n "_" "-")
        n (.toLowerCase n)]
    n))

(defn- derived? [spec]
  (contains? spec :extends))

(defn- derive-ugen-specs
  "Merge the ugen spec maps to give children their parent's attributes.

  Recursively reduces the specs to support arbitrary levels of derivation."
  ([specs] (derive-ugen-specs specs {} 0))
  ([children adults depth]
     ;; Make sure a bogus UGen doesn't spin us off into infinity... ;-)
     {:pre [(< depth 8)]}

     (let [[adults children]
           (reduce (fn [[full-specs new-children] spec]
                     (if (derived? spec)
                       (if (contains? full-specs (:extends spec))
                         [(assoc full-specs (:name spec)
                                 (merge (get full-specs (:extends spec)) spec))
                          new-children]
                         [full-specs (conj new-children spec)])
                       [(assoc full-specs (:name spec) spec) new-children]))
                   [adults []]
                   children)]
       (if (empty? children)
         (vals adults)
         (recur children adults (inc depth))))))

(defn- with-rates
  "Add the default ugen rates to any ugen that doesn't explicitly set it."
  [spec]
  (assoc spec :rates (get spec :rates UGEN-DEFAULT-RATES)))

(defn- with-default-rate
  "Calculates the default rate which will be used when the rate isn't explicitly
  used in the fn name (i.e. ugen:kr) or if :ir is available in the rate options"
  [spec]
  (let [rates (:rates spec)
        rate (cond
              (contains? spec :default-rate) (:default-rate spec)
              (= 1 (count rates)) (first rates)
              :default (first (filter rates
                                      UGEN-DEFAULT-RATE-PRECEDENCE)))
        rate (if (or (= :ir rate) (:auto-rate spec))
               :auto
               rate)]
    (assoc spec :default-rate rate)))

(defn- with-categories
  "Adds a :categories attribute to a ugen-spec for later use in documentation
  GUI and REPL interaction."
  [spec]
  (assoc spec :categories (get UGEN-CATEGORIES (overtone-ugen-name (:name spec)) [])))

(defn- with-expands
  "Sets the :expands? attribute for ugen-spec arguments, which will inform the
  automatic channel expansion system when to expand argument."
  [spec]
  (assoc spec :args
         (map (fn [arg]
                (let [expands? (if (:array arg)
                                 false
                                 (get UGEN-SPEC-EXPANSION-MODES
                                      (get arg :mode :standard)))]
                  (assoc arg :expands? expands?)))
              (:args spec))))

(defn- with-fn-names
  "Generates all the function names for this ugen and adds a :fn-names map
  that maps function names to rates, representing the output rate.

  All available rates get an explicit function name of the form <fn-name>:<rate>
  like this:
  * (env-gen:ar ...)
  * (env-gen:kr ...)

  UGens will also have a base-name without a rate suffix that uses the default
  rate of :auto, which will use the rate of the fastest argument ugen, or else
  the default rate according to the ugen rate precedence order:

  [:ir :dr :ar :kr]

  or a :default-rate attribute can override the default precedence order."
  [spec]
  (let [rates (:rates spec)
        rate-vec (vec rates)
        base-name (overtone-ugen-name (:name spec))
        base-rate (:default-rate spec)
        name-rates (zipmap (map #(str base-name %) rate-vec)
                           rate-vec)]
    (assoc spec
      :fn-names (assoc name-rates base-name base-rate))))

(defn- args-with-specs
  "Creates a list of (arg-value, arg-spec-item) pairs."
  [args spec property]
  {:pre [(keyword? property)]}
  (partition 2 (interleave args (map property (:args spec)))))

(defn- map-ugen-args
  "Perform argument mappings for ugen args that take a keyword but need to be
  looked up in a map supplied in the spec. (e.g. envelope actions)"
  [spec ugen]
  (let [args (:args ugen)
        args-specs (args-with-specs args spec :map)
        mapped-args (map (fn [[arg map-val]] (if (and (map? map-val)
                                                     (keyword? arg))
                                              (arg map-val)
                                              arg))
                         args-specs)]
    (assoc ugen :args mapped-args)))

(defn- append-seq-args
  "Handles argument modes :append-sequence and :append-sequence-set-num-outs,
  where some ugens take a seq for one argument which needs to be appended to the
  end of the argument list when sent to SC."
  [spec ugen]
  (let [args-specs     (args-with-specs (:args ugen) spec :mode)
        pred          #(or (= :append-sequence (second %))
                           (= :append-sequence-set-num-outs (second %)))
        normal-args    (map first (remove pred args-specs))
        to-append      (filter pred args-specs)
        to-append-args (map first to-append)
        args           (flatten (concat normal-args to-append-args))
        ugen           (assoc ugen :args args)]
    (if-let [n-outs-arg (first (filter #(= :append-sequence-set-num-outs (second %))
                                       to-append))]
      (assoc ugen :n-outputs (count (flatten [(first n-outs-arg)])))
      ugen)))

(defn add-default-args [spec ugen]
  (let [args (:args ugen)
        arg-names (map #(keyword (:name %)) (:args spec))
        default-map (zipmap arg-names
                            (map :default (:args spec)))]
    (assoc ugen :args (arg-lister args arg-names default-map))))

(defn- with-num-outs-mode [spec ugen]
  (let [args-specs (args-with-specs (:args ugen) spec :mode)
        [args n-outs] (reduce (fn [[args n-outs] [arg mode]]
                                (if (= :num-outs mode)
                                  [args arg]
                                  [(conj args arg) n-outs]))
                              [[] (:n-outputs ugen)]
                              args-specs)]
    (assoc ugen
      :n-outputs n-outs
      :args args)))

(defn- with-floated-args [spec ugen]
  (assoc ugen :args (floatify (:args ugen))))

(def UGEN-RATE-SPEED {:ir 0
                      :dr 1
                      :kr 2
                      :ar 3})

(declare ugen?)

(defn- op-rate
  "Lookup the rate of an input ugen, otherwise use IR because the operand
  must be a constant float."
  [arg]
  (if (ugen? arg)
    (:rate arg)
    (get RATES :ir)))

(defn- ugen-arg-rates [ugen]
  (map REVERSE-RATES (map :rate (filter ugen? (:args ugen)))))

(defn real-ugen-name
  [ugen]
  (overtone-ugen-name
    (case (:name ugen)
      "UnaryOpUGen"
      (get REVERSE-UNARY-OPS (:special ugen))

      "BinaryOpUGen"
      (get REVERSE-BINARY-OPS (:special ugen))

      (:name ugen))))

(defn- check-arg-rates [spec ugen]
  (let [cur-rate (REVERSE-RATES (:rate ugen))
        ugen-args (filter ugen? (:args ugen))]
    (if-let [bad-input (some
                     (fn [ug]
                       (if (< (UGEN-RATE-SPEED cur-rate)
                              (UGEN-RATE-SPEED (get REVERSE-RATES (:rate ug))))
                         ug false))
                     ugen-args)]
      (let [ugen-name (real-ugen-name ugen)
            in-name (real-ugen-name bad-input)
            cur-rate-name (get HUMAN-RATES cur-rate)
            in-rate-name (get HUMAN-RATES (:rate-name bad-input))]
        ; Special case the a2k ugen
        (if (and (= "A2K" (:name ugen))
                 (= :ar (:rate-name bad-input)))
          ugen
          (throw (Exception.
                   (format "Invalid ugen rate.  The %s ugen is %s rate, but it has a %s input ugen running at the faster %s rate.  Besides the a2k ugen, all ugens must be the same speed or faster than their inputs."
                           ugen-name cur-rate-name
                           in-name in-rate-name)))))
      ugen)))

(defn- auto-rate-setter [spec ugen]
  (if (= :auto (:rate ugen))
    (let [arg-rates (ugen-arg-rates ugen)
          fastest-rate (first (reverse (sort-by UGEN-RATE-SPEED arg-rates)))
          new-rate (get RATES (or fastest-rate :ir))]
      (assoc ugen :rate new-rate :rate-name (REVERSE-RATES new-rate)))
    ugen))

(defn- buffer->id
  "Returns a function that converts any buffer arguments to their :id property
  value."
  [ugen]
  (update-in ugen [:args]
             (fn [args]
               (map #(if (buffer? %) (:id %) %) args))))

(defn- bus->id
  "Returns a function that converts any bus arguments to their :id property
  value."
  [ugen]
  (update-in ugen [:args]
             (fn [args]
               (map #(if (bus? %) (:id %) %) args))))

(defn- with-init-fn
  "Creates the final argument initialization function which is applied to
  arguments at runtime to do things like re-ordering and automatic filling in
  of arguments. Typically appending input arrays as the last argument and
  filling in the number of in or out channels for those ugens that need it.

  If an init function is already present it will get called after doing the
  mapping and mode transformations."
  [spec]
  (let [defaulter (partial add-default-args spec)
        mapper    (partial map-ugen-args spec)
        initer    (if (contains? spec :init) (:init spec) identity)
        n-outputer (partial with-num-outs-mode spec)
        floater   (partial with-floated-args spec)
        appender  (partial append-seq-args spec)
        auto-rater (partial auto-rate-setter spec)
        rate-checker (partial check-arg-rates spec)]
    (assoc spec :init
           (fn [ugen]
             (-> ugen
                 defaulter
                 mapper
                 initer
                 n-outputer
                 floater
                 buffer->id
                 bus->id
                 appender
                 auto-rater
                 rate-checker)))))

(defn- decorate-ugen-spec
  "Interpret a ugen-spec and add in additional, computed meta-data."
  [spec]
  (-> spec
      (with-rates)
      (with-categories)
      (with-expands)
      (with-init-fn)
      (with-default-rate)
      (with-fn-names)
      (doc/with-arg-defaults)
      (doc/with-full-doc)))

(defn- specs-from-namespaces [namespaces]
  (reduce (fn [mem ns]
            (let [full-ns (symbol (str "overtone.sc.ugen." ns))
                  _ (require [full-ns :only '[specs specs-collide]])
                  specs (var-get (ns-resolve full-ns 'specs))]

              ;; TODO: Currently colliders must be loaded before specs in order
              ;; for this to run properly, because some ugens in specs derive
              ;; from the 'index' ugen in colliders.  Maybe the derivation
              ;; process should get smarter...
              (if-let [colliders (ns-resolve full-ns 'specs-collide)]
                (concat mem (var-get colliders) specs)
                (concat mem specs))))
          []
          namespaces))

(defn- load-ugen-specs [namespaces]
  "Perform the derivations and setup defaults for rates, names
  argument initialization functions, and channel expansion flags."
  (let [specs (specs-from-namespaces namespaces)
        derived (derive-ugen-specs specs)]
    (map decorate-ugen-spec derived)))

(def UGEN-NAMESPACES
  '[basicops buf-io compander delay envgen fft2 fft-unpacking grain
    io machine-listening misc osc beq-suite chaos control demand
    ff-osc fft info noise pan trig line input filter random mda stk])



(def UGEN-SPECS (load-ugen-specs UGEN-NAMESPACES))
(def UGEN-SPEC-MAP (zipmap
                    (map #(normalize-ugen-name (:name %)) UGEN-SPECS)
                    UGEN-SPECS))

(defn get-ugen [word]
  (get UGEN-SPEC-MAP (normalize-ugen-name word)))

(defn find-ugen [regexp]
  (map #(second %)
       (filter (fn [[k v]] (re-find (re-pattern (normalize-ugen-name regexp))
                                   (str k)))
               UGEN-SPEC-MAP)))

(defn inf!
  "users use this to tag infinite sequences for use as
   expanded arguments. needed or else multichannel
   expansion will blow up"
  [sq]
  (with-meta sq
    (merge (meta sq) {:infinite-sequence true})))

(defn- inf? [obj]
  (:infinite-sequence (meta obj)))

;; Does it really make sense to cycle over the values of a collection when
;; doing expansion?  I don't think maps should be allowed as arguments, unless
;; this is a strategy at having named arguments, but then the ordering wouldn't
;; make sense.
;;(defn- cycle-vals [coll]
;;  (cycle (if (map? coll) (vals coll) coll)))

(defn- expandable? [arg]
  (and (coll? arg)
       (not (map? arg))))

(defn- multichannel-expand
  "Does sc style multichannel expansion.
  * does not expand seqs flagged infinite
  * note that this returns a list even in the case of a single expansion

  Takes expand-flags, a seq of boolean values representing whether a given arg
  should be expanded. Each nth expand-flag boolean corresponds to each nth arg
  where arg is a seq of arguments passed into the ugen fn.
  "
  [expand-flags args]
  (if (zero? (count args))
    [[]]
    (let [gc-seqs (fn [[gcount seqs flags] arg]
                    (cond
                     ;; Infinite seqs can be used to generate values for expansion
                     (inf? arg) [gcount
                                 (conj seqs arg)
                                 (next flags)]

                     ;; Regular, non-infinite and non-map collections get expanded
                     (and (expandable? arg)
                          (first flags)) [(max gcount (count arg))
                                          (conj seqs (cycle arg))
                                          (next flags)]

                          :else ;; Basic values get used for all expansions
                          [gcount
                           (conj seqs (repeat arg))
                           (next flags)]))
          [greatest-count seqs] (reduce gc-seqs [1 [] expand-flags] args)]
      (take greatest-count (parallel-seqs seqs)))))

(defn make-expanding
  "Takes a function and returns a multi-channel-expanding version of the
  function."
  [f expand-flags]
  (fn [& args]
    (let [expanded (mapply f (multichannel-expand expand-flags args))]
      (if (= (count expanded) 1)
        (first expanded)
        expanded))))

;; TODO: Finish me!
;; Need to execute the check predicates to do things like verify argument rates, etc...
(defn check-ugen-args [spec rate special args]
  (if (vector? (:check spec))
    (doseq [check (:check spec)]
      (check rate special args))))

(defrecord UGen [id name rate rate-name special args n-outputs])
(derive UGen ::ugen)

(defrecord ControlProxy [name value rate rate-name])
(derive ControlProxy ::ugen)

(defn control-proxy
  [name value]
  (ControlProxy. name value (:kr RATES) :kr))

(defrecord UGenOutputProxy [ugen rate rate-name index])
(derive UGenOutputProxy ::ugen)

(defn output-proxy [ugen index]
  (UGenOutputProxy. ugen (:rate ugen) (REVERSE-RATES (:rate ugen)) index))

(def ^{:dynamic true} *ugens* nil)
(def ^{:dynamic true} *constants* nil)

(defn ugen [spec rate special args]
  ;;(check-ugen-args spec rate special args)
  (let [rate (or (get RATES rate) rate)
        ug (UGen.
            (next-id :ugen)
            (:name spec)
            rate
            (REVERSE-RATES rate)
            special
            args
            (or (:num-outs spec) 1))
        ug (if (contains? spec :init) ((:init spec) ug) ug)]
    (when (and *ugens* *constants*)
      (set! *ugens* (conj *ugens* ug))
      (doseq [const (filter number? (:args ug))]
        (set! *constants* (conj *constants* const))))

    (if (> (:n-outputs ug) 1)
      (map-indexed (fn [idx _] (output-proxy ug idx)) (range (:n-outputs ug)))
      ug)))

(defn ugen? [obj] (isa? (type obj) ::ugen))
(defn control-proxy? [obj] (= ControlProxy (type obj)))
(defn output-proxy? [obj] (= UGenOutputProxy (type obj)))

(defn- ugen-base-fn [spec rate special]
  (fn [& args]
    (ugen spec rate special args)))

(defn- make-ugen-fn
  "Returns a function representing the given ugen that will fill in default
  arguments, rates, etc."
  [spec rate special]
  (let [expand-flags (map #(:expands? %) (:args spec))]
    (make-expanding (ugen-base-fn spec rate special) expand-flags)))

;; TODO: Figure out the complete list of control types
;; This is used to determine the controls we list in the synthdef, so we need
;; all ugens which should be specified as external controls.
(def CONTROLS #{"control"})

(defn control-ugen [rate n-outputs]
  (with-meta {:id (next-id :ugen)
              :name "Control"
              :rate (rate RATES)
              :rate-name (REVERSE-RATES (rate RATES))
              :special 0
              :args nil
              :n-outputs n-outputs
              :outputs (repeat n-outputs {:rate (rate RATES)})
              :n-inputs 0
              :inputs []}
    {:type :ugen}))

(defn control? [obj]
  (isa? (type obj) ::control))


(defn- overload-ugen-op [ns ugen-name ugen-fn]
  (let [original-fn (ns-resolve ns ugen-name)]
    (ns-unmap ns ugen-name)
    (intern ns ugen-name (fn [& args]
                           (if (some #(or (ugen? %) (not (number? %))) args)
                             (apply ugen-fn args)
                             (apply original-fn args))))))

(defn- def-ugen
  "Create and intern a set of functions for a given ugen-spec.
    * base name function using default rate and no suffix (e.g. env-gen )
    * base-name plus rate suffix functions for each rate (e.g. env-gen:ar,
      env-gen:kr)
  "
  [to-ns spec special]
  (let [metadata {:doc (:full-doc spec)
                  :arglists (list (vec (map #(symbol (:name %))
                                            (:args spec))))}
        ugen-fns (map (fn [[uname rate]] [(with-meta (symbol uname) metadata)
                                         (make-ugen-fn spec rate special)])
                      (:fn-names spec))]
    (doseq [[ugen-name ugen-fn] ugen-fns]
      (intern to-ns ugen-name ugen-fn))))

(defn- def-unary-op
  [to-ns op-name special]
  (let [spec (get UGEN-SPEC-MAP "unaryopugen")
        ugen-name (symbol (overtone-ugen-name op-name))
        ugen-name (with-meta ugen-name {:doc (:full-doc spec)})
        ugen-fn (fn [arg]
                  (ugen spec (op-rate arg) special (list arg)))
        ugen-fn (make-expanding ugen-fn [true])]
    (if (ns-resolve to-ns ugen-name)
      (overload-ugen-op to-ns ugen-name ugen-fn)
      (intern to-ns ugen-name ugen-fn))))

(defn- def-binary-op
  [to-ns op-name special]
  (let [spec (get UGEN-SPEC-MAP "binaryopugen")
        ugen-name (symbol (overtone-ugen-name op-name))
        ugen-name (with-meta ugen-name {:doc (:full-doc spec)})
        ugen-fn (fn [a b]
                  (ugen spec (max (op-rate a) (op-rate b)) special (list a b)))
        ugen-fn (make-expanding ugen-fn [true true])]
    (if (ns-resolve to-ns ugen-name)
      (overload-ugen-op to-ns ugen-name ugen-fn)
      (intern to-ns ugen-name ugen-fn))))

;; We define this uniquely because it has to be smart about its rate.
;; TODO: I think this should probably be handled by one of the ugen modes
;; that is currently not yet implemented...
(def mul-add
  (make-expanding
   (fn [in mul add]
     (ugen {:name "MulAdd",
            :args [{:name "in"}
                   {:name "mul", :default 1.0}
                   {:name "add", :default 0.0}]
            :doc "Multiply and add, equivalent to (+ add (* mul in))"}
           (op-rate in) 0 (list in mul add)))
   [true true true]))

(load "ugen/generic_ops")

(defn intern-ugens
  "Iterate over all UGen meta-data, generate the corresponding functions and
  intern them in the current or otherwise specified namespace."
  [& [to-ns]]
  (let [to-ns (or to-ns *ns*)]
    (doseq [ugen (filter #(not (or (= "UnaryOpUGen" (:name %))
                                   (= "BinaryOpUGen" (:name %))))
                         UGEN-SPECS)]
      (def-ugen to-ns ugen 0))
    (doseq [[op-name special] UNARY-OPS]
      (def-unary-op to-ns op-name special))
    (doseq [[op-name special] BINARY-OPS]
      (def-binary-op to-ns op-name special))
    ;;(intern to-ns 'mul-add (make-expanding mul-add [true true true]))
    ;;(refer 'overtone.sc.ugen.extra)
    ))

(defn intern-ugens-collide
  "Intern the ugens that collide with built-in clojure functions."
  [& [to-ns]]
  (let [to-ns (or to-ns *ns*)
        generics #{"+" "-" "*" "/"}]
    (doseq [op generics]
      (let [func (var-get (resolve (symbol "clojure.contrib.generic.arithmetic" op)))]
        (ns-unmap to-ns (symbol op))
        (intern to-ns (symbol op) (make-expanding func [true true]))))

    ;; intern div-meth so we can access the division operator since / is special cased
    (intern to-ns 'div-meth
            (make-expanding (var-get (resolve
                                      (symbol "clojure.contrib.generic.arithmetic" "/")))
                            [true true]))

    (doseq [[op-name special] UNARY-OPS-COLLIDE]
      (def-unary-op to-ns op-name special))
    (doseq [[op-name special] BINARY-OPS-COLLIDE]
      (def-binary-op to-ns op-name special))))


;; We refer all the ugen functions here so they can be access by other parts
;; of the Overtone system using a fixed namespace.  For example, to automatically
;; stick an Out ugen on synths that don't explicitly use one.
(defonce _ugens (intern-ugens))
(defonce _colliders (intern-ugens-collide (create-ns 'overtone.ugen-collide)))

(defmacro with-ugens [& body]
  `(let [~'+ overtone.ugen-collide/+
         ~'- overtone.ugen-collide/-
         ~'* overtone.ugen-collide/*
         ~'/ overtone.ugen-collide/div-meth
         ~'>= overtone.ugen-collide/>=
         ~'<= overtone.ugen-collide/<=
         ~'rand overtone.ugen-collide/rand
         ~'mod overtone.ugen-collide/mod
         ~'bit-not overtone.ugen-collide/bit-not]
     ~@body))

(load "ugen/extra")
