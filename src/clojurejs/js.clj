;; js.clj -- a naive Clojure (subset) to javascript translator

(ns clojurejs.js
  (:require [clojure.contrib.seq-utils :as seq-utils])
  (:use [clojure.contrib.duck-streams :exclude [spit]]
        clojure.contrib.java-utils clojure.contrib.str-utils))

(defn- sexp-reader [source]
  "Wrap `source' in a reader suitable to pass to `read'."
  (new java.io.PushbackReader (reader source)))

(defn- unzip [s]
  (let [parts (partition 2 s)]
    [(into (empty s) (map first parts))
     (into (empty s) (map second parts))]))

;; (= (unzip [:foo 1 :bar 2 :baz 3]) [[:foo :bar :baz] [1 2 3]])

(defn- re? [expr] (= (class expr) java.util.regex.Pattern))

(def *inline-if* false) 

(def *print-pretty* false)
(defmacro with-pretty-print [& body]
  `(binding [*print-pretty* true]
     ~@body))

(def *indent* 0)
(defmacro with-indent [[& increment] & body]
  `(binding [*indent* (+ *indent* (or ~increment 4))]
     ~@body))

(defn- newline-indent []
  (if *print-pretty*
    (do
      (newline)
      (print (apply str (repeat *indent* " "))))
    (print " ")))

(defmacro with-parens [[& [left right]] & body]
  `(do
     (print (or ~left "("))
     ~@body
     (print (or ~right ")"))))

(defn- jskey [x]
  (let [x (if (and (coll? x) (not (empty? x))) (first x) x)]
    (if (symbol? x) (name x) x)))

(defmulti emit "Emit a javascript expression." {:private true} jskey)

(defn- emit-delimited [delimiter args & [emitter]]
  (when-not (empty? args)
    ((or emitter emit) (first args))
    (doseq [arg (rest args)]
      (print delimiter)
      ((or emitter emit) arg))))

(defn- emit-map [expr]
  (with-parens ["{" "}"]
    (emit-delimited ","
                    (seq expr)
                    (fn [[key val]]
                      (emit key)
                      (print " : ")
                      (emit val)))))

(defn- emit-vector [expr]
  (with-parens ["[" "]"]
    (emit-delimited "," (seq expr))))

(defn- emit-re [expr]
  (print (str "/" (apply str (replace {\/ "\\/"} (str expr))) "/")))

(defn- emit-symbol [expr]
  (print (apply str (replace {\- "_" \* "__" \? "p" \! "f" \= "_eq"} (name expr)))))

(defn- emit-keyword [expr]
  (print "'")
  (emit-symbol expr)
  (print "'"))

(defn- unary-operator? [op]
  (and (symbol? op) (seq-utils/includes? #{"++" "--" "!"} (name op))))

(defn- emit-unary-operator [op arg]
  (print (name op))
  (emit arg))

(defn- infix-operator? [op]
  (and (symbol? op)
       (seq-utils/includes? #{"and" "or" "+" "-" "/" "*" "%"
                              ">" ">=" "<" "<=" "==" "===" "!=" "!=="
                              "instanceof"}
                            (name op))))

(defn- emit-infix-operator [op & args]
  (let [lisp->js {"and" "&&"
                  "or" "||"}
        js-op (get lisp->js (name op) (name op))]
    (with-parens []
      (emit-delimited (str " " js-op " ") args))))

(defn- emit-function-call [fun & args]
  (emit fun)
  (with-parens []
    (with-indent [] (emit-delimited ", " args))))

(defn- emit-method-call [recvr selector & args]
  (emit recvr)
  (emit selector)
  (with-parens []
    (with-indent [] (emit-delimited ", " args))))

(def *return-expr* false)
(defmacro with-return-expr [[& [new-val]] & body]
  `(binding [*return-expr* (if *return-expr*
                             (do
                               (print "return ")
                               false)
                             (or ~new-val false))]
     ~@body))  

(defn- emit-function-form [form]
  (binding [*inline-if* true]
    (let [[fun & args] form
          method? (fn [f] (and (symbol? f) (= \. (first (name f)))))
          invoke-method (fn [form] (let [[sel recvr & args] form]
                                     (apply emit-method-call recvr sel args)))
          invoke-fun (fn [fun & args]
                       (with-parens [] (emit fun))
                       (with-parens [] (emit-delimited "," args)))]
      (cond
       (unary-operator? fun) (apply emit-unary-operator form)
       (infix-operator? fun) (apply emit-infix-operator form)
       (method? fun) (invoke-method form)
       (coll? fun) (apply invoke-fun form)
       true (apply emit-function-call form)))))

(defn emit-statement [expr]
  (binding [*inline-if* false]
    (if (and (coll? expr) (= 'defmacro (first expr))) ; cracks are showing
      (emit expr)
      (do
        (newline-indent)
        (emit expr)
        (print ";")))))

(defn emit-statements [exprs]
  (doseq [expr exprs]
    (emit-statement expr)))

(defn emit-statements-with-return [exprs]
  (doseq [expr (butlast exprs)]
    (emit-statement expr))
  (binding [*return-expr* true]
    (emit-statement (last exprs))))

(defmethod emit "def" [[_ name value]]
  (print "var ")
  (emit-symbol name)
  (print " = ")
  (emit value))

(defmethod emit "defn" [[_ name args & body]]
  (emit-symbol name)
  (print " = function(")
  (binding [*return-expr* false] (emit-delimited ", " args))
  (print ") {")
  (with-indent []
    (emit-statements-with-return body))
  (newline-indent)
  (print "}"))

(def *macros* (ref {}))
(defn- macro? [n] (and (symbol? n) (contains? @*macros* (name n))))
(defn- get-macro [n] (and (symbol? n) (get @*macros* (name n))))

(defmethod emit "defmacro" [[_ mname args & body]]
  (dosync
   (alter *macros*
          conj
          {(name mname) (eval `(clojure.core/fn ~args ~@body))}))
  nil)

(defn- emit-macro-expansion [form]
  (let [[mac-name & args] form
        mac (get-macro mac-name)
        macex (apply mac args)]
    (emit macex)))

(defmethod emit "fn" [[_ args & body]]
  (newline-indent)
  (print "function (")
  (emit-delimited ", " args)
  (print ") {")
  (with-indent []
    (emit-statements-with-return body))
  (newline-indent)
  (print "}"))

(def *in-block-exp* false)
(defmacro with-block [& body]
  `(binding [*in-block-exp* true]
     ~@body))

(defmethod emit "if" [[_ test consequent & [alternate]]]
  (let [emit-inline-if (fn []
                         (with-return-expr []
                           (with-parens []
                             (emit test)
                             (print " ? ")
                             (emit consequent)
                             (print " : ")
                             (emit alternate))))
        emit-block-if (fn []
                        (print "if (")
                        (binding [*return-expr* false]
                          (emit test))
                        (print ") {")
                        (with-block
                          (with-indent []
                            (emit-statement consequent)))
                        (newline-indent)
                        (print "}")
                        (when alternate
                          (print " else {")
                          (with-block
                            (with-indent []
                              (emit-statement alternate)))
                          (newline-indent)
                          (print "}")))]
    (if (and *inline-if* consequent alternate)
      (emit-inline-if)
      (emit-block-if))))

(defmethod emit "do" [[_ & exprs]]
  (let [thunk (fn [] (doseq [expr exprs] (emit-statement expr)))]
    (binding [*return-expr* false]
      (if (not *in-block-exp*)
        (with-parens [] (thunk))
        (thunk)))))

(defn- emit-var-bindings [bindings]
  (emit-delimited ","
                  (partition 2 bindings)
                  (fn [[vname val]]
                    (if *in-block-exp* (newline-indent))
                    (emit vname)
                    (print " = ")
                    (binding [*inline-if* true]
                      (emit val)))))

(defmethod emit "let" [[_ bindings & exprs]]
  (with-return-expr []
    (print "(function () {")
    (with-indent []
      (newline-indent)
      (print "var ")
      (with-indent []
        (with-block (emit-var-bindings bindings))
        (print ";"))
      (emit-statements-with-return exprs))
    (newline-indent)
    (print " })()")))

(defmethod emit "new" [[_ class & args]]
  (with-return-expr []
    (print "new ")
    (emit class)
    (with-parens [] (emit-delimited "," args))))

(defmethod emit "return" [[_ value]]
  (print "return ")
  (emit value))

(defmethod emit 'nil [_]
  (with-return-expr []
    (print "null")))

(defmethod emit "get" [[_ map key]]
  (with-return-expr []
    (emit map)
    (print "[")
    (emit key)
    (print "]")))

(defmethod emit "length" [[_ expr]]
  (with-return-expr []
    (emit expr)
    (print ".length")))

(defmethod emit "set!" [[_ & apairs]]
  (binding [*return-expr* false]
    (let [apairs (partition 2 apairs)]
      (emit-delimited " = " (first apairs))
      (doseq [apair (rest apairs)]
        (print ";")
        (newline-indent)
        (emit-delimited " = " apair)))))

(defmethod emit "try" [[_ expr & clauses]]
  (print "try {")
  (with-indent []
    (with-block
      (emit-statement expr)))
  (newline-indent)
  (print "}")
  (doseq [[clause & body] clauses]
    (case clause
      'catch (let [[evar expr] body]
               (with-block
                 (print " catch (")
                 (emit-symbol evar)
                 (print ") {")
                 (with-indent [] (emit-statement expr))
                 (newline-indent)
                 (print "}")))
      'finally (with-block
                 (print " finally {")
                 (with-indent [] (doseq [expr body] (emit-statement expr)))
                 (newline-indent)
                 (print "}")))))

(def *loop-vars* nil)
(defmethod emit "loop" [[_ bindings & body]]
  (with-return-expr []
    (print "(function () {")
    (with-indent []
      (newline-indent)
      (print "for (var ")
      (binding [*return-expr* false
                *in-block-exp* false]
        (emit-var-bindings bindings))
      (print "; true;) {")
      (with-indent []
        (binding [*loop-vars* (first (unzip bindings))]
          (with-return-expr [true]
            (emit-statements body)))
        (newline-indent)
        (print "break;"))
      (newline-indent)
      (print "}"))
    (newline-indent)
    (print "})()")))

(defmethod emit "recur" [[_ & args]]
  (binding [*return-expr* false]
    (emit-statements (map (fn [lvar val] `(set! ~lvar ~val)) *loop-vars* args)))
  (newline-indent)
  (print "continue"))

(defmethod emit "dokeys" [[_ [lvar hash] & body]]
  (binding [*return-expr* false]
    (print "for (var ")
    (emit lvar)
    (print " in ")
    (emit hash)
    (print ") {")
    (with-indent []
      (emit-statements body))
    (newline-indent)
    (print "}")))

(defmethod emit "inline" [[_ js]]
  (print js))

(defmethod emit :default [expr]
  (if (and (coll? expr) (macro? (first expr)))
    (emit-macro-expansion expr)
    (with-return-expr []
      (cond
       (map? expr) (emit-map expr)
       (vector? expr) (emit-vector expr)
       (re? expr) (emit-re expr)
       (keyword? expr) (emit-keyword expr)
       (string? expr) (pr expr)
       (symbol? expr) (emit-symbol expr)
       (coll? expr) (emit-function-form expr)
       true (print expr)))))

(defn js-emit [expr] (emit expr))

(defmacro js [& exprs]
  "Translate the Clojure subset `exprs' to a string of javascript
code."
  (let [exprs# `(quote ~exprs)]
    `(with-out-str
       (if (< 1 (count ~exprs#))
         (emit-statements ~exprs#)
         (js-emit (first ~exprs#))))))

(defmacro js-let [bindings & exprs]
  "Bind Clojure environment values to named vars of a cljs block, and
translate the Clojure subset `exprs' to a string of javascript code."
  (let [form# 'fn
        [formals# actuals#] (unzip bindings)]
    `(with-out-str
       (emit-statement (list '(~form# ~(vec formals#) ~@exprs) ~@actuals#)))))

;; (print (js ((fn [a] (return (+ a 1))) 1)))

(defmacro script [& forms]
  "Similar to the (js ...) form, but wraps the javascript in a
 [:script ] element, which can be passed to hiccup.core/html."
  `[:script {:type "text/javascript"}
    (js ~@forms)])

(defmacro script-let [bindings & forms]
  "Similar to the (js-let ...) form, but wraps the javascript in a
 [:script ] element, which can be passed to hiccup.core/html."
  `[:script {:type "text/javascript"}
    (js-let ~bindings ~@forms)])

(defmacro jq [& forms]
  "Similar to the (js ...) form, but wraps the javascript in a
 [:script ] element which is invoked on a jQuery document.ready
 event."
  (let [fnform# 'fn]
    `[:script {:type "text/javascript"}
      (js (.ready ($ document) (~fnform# [] ~@forms)))]))

(defmacro jq-let [bindings & forms]
  "Similar to the (js-let ...) form, but wraps the javascript in a
 [:script ] element which is invoked on a jQuery document.ready
 event."
  (let [fnform# 'fn
        [formals# actuals#] (unzip bindings)]
    `[:script {:type "text/javascript"}
      "$(document).ready(function () {"
      (js-let ~bindings ~@forms)
      "});"]))

(defn tojs [& scripts]
  "Load and translate the list of cljs scripts into javascript, and
return as a string. Useful for translating an entire cljs script file."
  (with-out-str
    (doseq [f scripts]
      (with-open [in (sexp-reader f)]
        (loop [expr (read in false :eof)]
          (when (not= expr :eof)
            (if-let [s (emit-statement expr)]
              (print s))
            (recur (read in false :eof))))))))