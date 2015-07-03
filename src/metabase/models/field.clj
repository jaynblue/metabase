(ns metabase.models.field
  (:require [korma.core :refer :all]
            [metabase.api.common :refer [check]]
            [metabase.db :refer :all]
            (metabase.models [common :as common]
                             [database :refer [Database]]
                             [field-values :refer [field-should-have-field-values? create-field-values create-field-values-if-needed]]
                             [hydrate :refer [hydrate]]
                             [foreign-key :refer [ForeignKey]])
            [metabase.util :as u]))

(def ^:const special-types
  "Possible values for `Field` `:special_type`."
  #{:avatar
    :category
    :city
    :country
    :desc
    :fk
    :id
    :image
    :json
    :latitude
    :longitude
    :name
    :number
    :state
    :timestamp_milliseconds
    :timestamp_seconds
    :url
    :zip_code})

(def ^:const special-type->name
  "User-facing names for the `Field` special types."
  {:avatar            "Avatar Image URL"
   :category          "Category"
   :city              "City"
   :country           "Country"
   :desc              "Description"
   :fk                "Foreign Key"
   :id                "Entity Key"
   :image             "Image URL"
   :json              "Field containing JSON"
   :latitude          "Latitude"
   :longitude         "Longitude"
   :name              "Entity Name"
   :number            "Number"
   :state             "State"
   :timestamp_seconds "Timestamp - seconds since 1970"
   :url               "URL"
   :zip_code          "Zip Code"})

(def ^:const base-types
  "Possible values for `Field` `:base_type`."
  #{:BigIntegerField
    :BooleanField
    :CharField
    :DateField
    :DateTimeField
    :DecimalField
    :DictionaryField
    :FloatField
    :IntegerField
    :TextField
    :TimeField
    :UnknownField})

(def ^:const field-types
  "Possible values for `Field.field_type`."
  #{:metric      ; A number that can be added, graphed, etc.
    :dimension   ; A high or low-cardinality numerical string value that is meant to be used as a grouping
    :info        ; Non-numerical value that is not meant to be used
    :sensitive}) ; A Fields that should *never* be shown *anywhere*

(defentity Field
  (table :metabase_field)
  timestamped
  (types {:base_type    :keyword
          :field_type   :keyword
          :special_type :keyword})
  (assoc :hydration-keys #{:destination
                           :field
                           :origin}))

(defn field->fk-field
  "Attempts to follow a `ForeignKey` from the the given `Field` to a destination `Field`.

   Only evaluates if the given field has :special_type `fk`, otherwise does nothing."
  [{:keys [id special_type] :as field}]
  (when (= :fk special_type)
    (let [dest-id (sel :one :field [ForeignKey :destination_id] :origin_id id)]
      (sel :one Field :id dest-id))))

(defn unflatten-nested-fields
  "Take a sequence of both top-level and nested FIELDS, and return a sequence of top-level `Fields`
   with nested `Fields` moved into sequences keyed by `:children` in their parents.

     (unflatten-nested-fields [{:id 1, :parent_id nil}, {:id 2, :parent_id 1}])
       -> [{:id 1, :parent_id nil, :children [{:id 2, :parent_id 1, :children nil}]}]

   You may optionally specify a different PARENT-ID-KEY; the default is `:parent_id`."
  ([fields]
   (unflatten-nested-fields fields :parent_id))
  ([fields parent-id-key]
   (let [parent-id->fields (group-by parent-id-key fields)
         resolve-children  (fn resolve-children [field]
                             (assoc field :children (map resolve-children
                                                         (parent-id->fields (:id field)))))]
     (map resolve-children (parent-id->fields nil)))))

(defn- qualified-name
  "Return a name like `table.field` for FIELD. If FIELD is a nested field, recursively return a name
   like `table.parent.field`."
  [{:keys [table parent], :as field}]
  {:pre [(delay? table)]}
  (str (if parent
         (qualified-name @parent)
         (:name @table))
       "." (:name field)))

(defmethod post-select Field [_ {:keys [table_id] :as field}]
  (u/assoc* field
    :table               (delay (sel :one 'metabase.models.table/Table :id table_id))
    :db                  (delay @(:db @(:table <>)))
    :target              (delay (field->fk-field field))
    :can_read            (delay @(:can_read @(:table <>)))
    :can_write           (delay @(:can_write @(:table <>)))
    :human_readable_name (when (name :field)
                           (delay (common/name->human-readable-name (:name field))))
    :parent              (when (:parent_id field)
                           (delay (sel :one Field :id (:parent_id field))))
    :qualified-name      (delay (qualified-name <>))))

(defmethod pre-insert Field [_ field]
  (let [defaults {:active          true
                  :preview_display true
                  :field_type      :info
                  :position        0}]
    (merge defaults field)))

(defmethod post-insert Field [_ field]
  (when (field-should-have-field-values? field)
    (future (create-field-values field)))
  field)

(defmethod post-update Field [_ {:keys [id] :as field}]
  ;; if base_type or special_type were affected then we should asynchronously create corresponding FieldValues objects if need be
  (when (or (contains? field :base_type)
            (contains? field :field_type)
            (contains? field :special_type))
    (future (create-field-values-if-needed (sel :one [Field :id :table_id :base_type :special_type :field_type] :id id)))))

(defmethod pre-cascade-delete Field [_ {:keys [id]}]
  (cascade-delete Field :parent_id id)
  (cascade-delete ForeignKey (where (or (= :origin_id id)
                                        (= :destination_id id))))
  (cascade-delete 'metabase.models.field-values/FieldValues :field_id id))
