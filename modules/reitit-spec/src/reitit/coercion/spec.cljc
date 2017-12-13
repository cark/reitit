(ns reitit.coercion.spec
  (:require [clojure.spec.alpha :as s]
            [spec-tools.core :as st #?@(:cljs [:refer [Spec]])]
            [spec-tools.data-spec :as ds]
            [spec-tools.conform :as conform]
            [spec-tools.swagger.core :as swagger]
            [reitit.coercion :as coercion])
  #?(:clj
     (:import (spec_tools.core Spec))))

(def string-conforming
  (st/type-conforming
    (merge
      conform/string-type-conforming
      conform/strip-extra-keys-type-conforming)))

(def json-conforming
  (st/type-conforming
    (merge
      conform/json-type-conforming
      conform/strip-extra-keys-type-conforming)))

(def default-conforming
  ::default)

(defprotocol IntoSpec
  (into-spec [this name]))

(extend-protocol IntoSpec

  #?(:clj  clojure.lang.PersistentArrayMap
     :cljs cljs.core.PersistentArrayMap)
  (into-spec [this name]
    (ds/spec name this))

  #?(:clj  clojure.lang.PersistentHashMap
     :cljs cljs.core.PersistentHashMap)
  (into-spec [this name]
    (ds/spec name this))

  Spec
  (into-spec [this _] this)

  #?(:clj  Object
     :cljs default)
  (into-spec [this _]
    (st/create-spec {:spec this})))

(defn stringify-pred [pred]
  (str (if (seq? pred) (seq pred) pred)))

(defmulti coerce-response? identity :default ::default)
(defmethod coerce-response? ::default [_] true)

(def default-options
  {:coerce-response? coerce-response?
   :conforming {:body {:default default-conforming
                       :formats {"application/json" json-conforming}}
                :string {:default string-conforming}
                :response {:default default-conforming}}})

(defn create [{:keys [conforming coerce-response?] :as opts}]
  ^{:type ::coercion/coercion}
  (reify coercion/Coercion
    (-get-name [_] :spec)
    (-get-options [_] opts)
    (-get-apidocs [this _ {:keys [parameters responses] :as info}]
      (cond-> (dissoc info :parameters :responses)
              parameters (assoc
                           ::swagger/parameters
                           (into
                             (empty parameters)
                             (for [[k v] parameters]
                               [k (coercion/-compile-model this v nil)])))
              responses (assoc
                          ::swagger/responses
                          (into
                            (empty responses)
                            (for [[k response] responses]
                              [k (update response :schema #(coercion/-compile-model this % nil))])))))
    (-compile-model [_ model name]
      (into-spec model (or name (gensym "spec"))))
    (-open-model [_ spec] spec)
    (-encode-error [_ error]
      (-> error
          (update :spec (comp str s/form))
          (update :problems (partial mapv #(update % :pred stringify-pred)))))
    (-request-coercer [this type spec]
      (let [spec (coercion/-compile-model this spec nil)
            {:keys [formats default]} (conforming type)]
        (fn [value format]
          (if-let [conforming (or (get formats format) default)]
            (let [conformed (st/conform spec value conforming)]
              (if (s/invalid? conformed)
                (let [problems (st/explain-data spec value conforming)]
                  (coercion/map->CoercionError
                    {:spec spec
                     :problems (::s/problems problems)}))
                (s/unform spec conformed)))
            value))))
    (-response-coercer [this spec]
      (if (coerce-response? spec)
        (coercion/-request-coercer this :response spec)))))

(def coercion (create default-options))
