(ns shelving.trivial-edn
  "A naive EDN backed shelving unit."
  {:authors ["Reid \"arrdem\" McKenzie <me@arrdem.com>"]
   :license "Eclipse Public License 1.0"
   :added   "0.1.0"}
  (:require [shelving.core :as sh]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.set :refer [superset?]]))

;; Configs open into shelves
(defmethod sh/open ::config [{:keys [path schema load] :as s}]
  (when-not load
    (binding [*out* *err*]
      (println "Warning: opening connection without trying to load existing data set!")))
  (let [state           (-> (if (and load
                                     (.exists (io/file path)))
                              (-> path
                                  io/reader
                                  java.io.PushbackReader.
                                  edn/read
                                  ((fn [v] (assert (map? v)) v)))
                              {})
                            atom)
        conn            (-> s
                            (assoc :type ::shelf)
                            (assoc ::state state))
        persisted-specs (-> state deref :schema)
        our-specs       (sh/schema->specs schema)]
    ;; Check the persisted schema
    (sh/check-specs! persisted-specs our-specs)
    
    ;; Install the schema updates in a new db 
    (swap! state update :schema merge our-specs)

    ;; Validate any loaded data
    (doseq [spec (sh/enumerate-specs conn)
            id   (sh/enumerate-spec conn spec)
            :let [record (sh/get conn spec id)]]
      (if-not (s/valid? spec record)
        (throw (ex-info "Failed to validate record!"
                        {:spec        spec
                         :record-id   id
                         :record      record
                         :explanation (s/explain spec record)}))))

    conn))

;; EDN shelves don't have write batching and are always open.
(defmethod sh/open ::shelf [s] s)

(defmethod sh/schema ::shelf [{:keys [schema]}]
  schema)

;; EDN shelves don't have write batching and don't have to be closed.
(defmethod sh/flush ::shelf [{:keys [shelving.trivial-edn/state path]}]
  (let [file (io/file path)]
    (if-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (with-open [writer (io/writer file)]
      (binding [*out*                               writer
                clojure.core/*print-namespace-maps* false
                clojure.core/*print-readably*       true]
        (pr @state)))))

(defmethod sh/close ::shelf [s]
  (sh/flush s))

(defmethod sh/get ::shelf
  ([conn spec record-id]
   (sh/get conn spec record-id nil))
  ([{:keys [shelving.trivial-edn/state]} spec record-id not-found]
   (-> @state (get :records) (get spec) (get record-id not-found))))

(declare ^:private put*)

(defn- rel-invalidate-record*
  "Relation implementation detail.

  Returns a new rel map, with the given record-id's entries purged."
  [[from-spec to-spec :as rel-id] rel-map record-id]
  (let [tos (-> rel-map (get from-spec) (get record-id))]
    (as-> rel-map %
      (update % from-spec dissoc record-id)
      (update % :pairs #(remove (comp #{record-id} first) %))
      (reduce (fn [% to]
                (update-in % [to-spec to] #(remove #{record-id} %)))
              % tos))))

(defn- rel-invalidate-record
  "Relation implementation detail.

  Removes all pairs containing the given `record-id` from all rel tables."
  [rels schema spec record-id]
  (->> (when (sh/is-record? schema spec)
         (for [[[from-spec to-spec :as rel-id] rel-map] rels
               :when                                    (or (= spec from-spec)
                                                            (= spec to-spec))]
           [rel-id (rel-invalidate-record* rel-id rel-map record-id)]))
       (into {})
       (merge rels)))

(defn- rel-index-record
  "Relation implementation detail.

  Updates all rel tables to reflect the new record with the given ID."
  [state schema spec record record-id]
  (let [rels-to-update (for [[[from-spec to-spec :as rel-id] rel-spec] (:rels schema)
                             :when                                     (= spec from-spec)]
                         [rel-id rel-spec])]
    (reduce (fn [state [[from-spec to-spec :as rel-id] {:keys [to-fn] :as rel}]]
              (if (sh/is-alias? schema rel-id)
                state
                (let [{:keys [id-fn] :as to-schema} (get-in schema [:specs to-spec])
                      to-record                     (to-fn record)
                      to-id                         (id-fn to-record)]
                  (-> state
                      (put* schema to-spec to-id to-record)
                      (update-in [:rels rel-id :pairs] conj [record-id to-id])
                      (update-in [:rels rel-id to-spec to-id] conj record-id)
                      (update-in [:rels rel-id from-spec record-id] conj to-id)))))
            state rels-to-update)))

;; FIXME (arrdem 2018-02-04):
;;   Can I optimize this in the case of inserting a value? Possibilities:
;;   - May not need to do the insert at all
;;   - Index building could be batched
(defn- put*
  "Implementation detail.

  Backs `#'sh/put`, providing the actual recursive write logic."
  [state schema spec record-id record]
  (assert (uuid? record-id))
  (assert (sh/has-spec? schema spec))
  (assert (s/valid? spec record))
  (-> state
      (update :rels rel-invalidate-record schema spec record-id)
      (assoc-in [:records spec record-id] record)
      (rel-index-record schema spec record record-id)))

(defn- put-record*
  ([{:keys [schema] :as conn} spec val]
   (put-record* conn spec (sh/id-for-record schema spec val) val))
  ([{:keys [shelving.trivial-edn/state schema] :as conn} spec id val]
   (swap! state put* schema spec id val)
   id))

(defn- put-val* [{:keys [schema] :as conn} spec val]
  (put-record* conn spec (sh/id-for-record schema spec val) val))

(defmethod sh/put ::shelf
  ([{:keys [schema flush-after-write] :as conn} spec val]
   (let [id (if (sh/is-value? schema spec)
              (put-val* conn spec val)
              (put-record* conn spec val))]
     (when flush-after-write
       (sh/flush conn))
     id))
  ([{:keys [schema flush-after-write] :as conn} spec id val]
   (let [id (if (sh/is-record? schema spec)
              (put-record* conn spec id val)
              (throw (UnsupportedOperationException.
                      (format "Cannot perform an upsert to value spec %s" spec))))]
     (when flush-after-write
       (sh/flush conn))
     id)))

(defmethod sh/enumerate-spec ::shelf [{:keys [shelving.trivial-edn/state]} spec]
  (some-> @state (get :records) (get spec) keys))

(defmethod sh/count-spec ::shelf [{:keys [shelving.trivial-edn/state]} spec]
  (or (some-> @state (get :records) (get spec) keys count) 0))

(defmethod sh/enumerate-rel ::shelf [{:keys [shelving.trivial-edn/state]} rel]
  (some-> @state (get :rels) (get rel) (get :pairs) seq))

(defmethod sh/count-rel ::shelf [{:keys [shelving.trivial-edn/state schema]} rel]
  (or (some-> @state (get :rels) (get (sh/resolve-alias schema rel)) :pairs count) 0))

(defmethod sh/relate-by-id ::shelf [{:keys [shelving.trivial-edn/state schema]} [from-spec to-spec :as rel] id]
  (some-> @state (get :rels) (get (sh/resolve-alias schema rel)) (get from-spec) (get id) seq))

(defn ->TrivialEdnShelf
  "Configures a (trivial) EDN shelf which can be opened for reading and writing."
  {:categories #{::sh/basic}
   :added      "0.0.0"
   :stability  :stability/stable}
  [schema path & {:keys [flush-after-write load]
                  :or   {flush-after-write false
                         load              true}}]
  {:type              ::config
   :schema            schema
   :path              path
   :flush-after-write flush-after-write
   :load              true})
