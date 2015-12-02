(ns confetti.s3-deploy
  (:require [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.data :as data]
            [clojure.string :as string]
            [digest :as dig]
            [schema.core :as s]
            [pantomime.mime :as panto]
            [amazonica.aws.cloudfront :as cf]
            [amazonica.aws.s3 :as s3]))

(def FileMap
  "Schema for file-maps"
  {:s3-key                    s/Str
   :file                      java.io.File
   (s/optional-key :metadata) (s/maybe {s/Keyword s/Str})})

(defn validate-creds! [cred]
  (assert (and (string? (:access-key cred))
               (string? (:secret-key cred))) cred))

(defn ^:private relative-path [dir f]
  (string/replace (.getCanonicalPath f)
                  (re-pattern (str (.getCanonicalPath dir) "/"))
                  ""))

(defn get-bucket-objects [cred bucket-name]
  (validate-creds! cred)
  (:object-summaries (s3/list-objects cred :bucket-name bucket-name)))

(defn dir->file-maps
  "Create a file-map as it's expected by `diff*` from a local directory."
  [dir]
  (let [files (filter #(.isFile %) (file-seq dir))]
    (-> (fn [f] {:s3-key (relative-path dir f)
                 :file   (io/file (.getCanonicalPath f))})
        (mapv files))))

(defn ^:private bucket-objects->diff-set [bucket-objects]
  (let [->diff-item (fn [{:keys [key etag]}] [key etag])]
    (into {} (map ->diff-item) bucket-objects)))

(defn ^:private file-maps->diff-set [file-maps]
  (let [->diff-item (fn [{:keys [s3-key file]}] [s3-key (dig/md5 file)])]
    (into {} (map ->diff-item) file-maps)))

(defn diff*
  "Get the difference between objects in a S3 bucket and files on disk.
   If a file is changed the old version {key,md5} will be part of the
   `removed` key.

   - `bucket-objects` is expected to be a list bucket object
     summaries as they are returned by `amazonica.aws.s3/list-objects`.

   - `file-maps` is a seq of maps containing the following keys: s3-key, file."
  [bucket-objects file-maps]
  (s/validate [FileMap] file-maps)
  (let [on-s3?   #((set (map :key bucket-objects)) (key %))
        on-s3    (bucket-objects->diff-set bucket-objects)
        on-fs    (file-maps->diff-set file-maps)
        [removed a-or-c in-sync] (data/diff on-s3 on-fs)]
    {:added     (into {} (remove on-s3? a-or-c))
     :changed   (into {} (filter on-s3? a-or-c))
     :removed   removed
     :unchanged in-sync}))

(defn dedupe-diff
  "Remove all keys that are in the changed map from the removed map."
  [{:keys [changed removed] :as diff}]
  (let [changed-set   (-> changed keys set)
        truly-deleted (-> removed keys set (cset/difference changed-set))]
    (update diff :removed select-keys truly-deleted)))

(defn ->op
  "Given a diff and a key return the required operation to sync"
  [diff s3-key]
  (let [in?    (fn [k] (get-in diff [k s3-key]))]
    (cond (in? :added)     ::upload
          (in? :changed)   ::update
          (in? :removed)   ::delete
          (in? :unchanged) ::no-op

          :else (throw (ex-info "No operation found!"
                                {:s3-key s3-key :diff diff})))))

(defn calculate-ops
  "Generate set of operations to get in sync from a given diff"
  [bucket-objects file-maps]
  (s/validate [FileMap] file-maps)
  (let [deduped (dedupe-diff (diff* bucket-objects file-maps))
        fm->op  #(assoc % :op (->op deduped (:s3-key %)))
        rm->op  (fn [[k _]] {:s3-key k :op (->op deduped k)})]
    (into (mapv fm->op file-maps)
          (mapv rm->op (:removed deduped)))))

(defn upload [creds bucket key file metadata]
  (with-open [in (io/input-stream file)]
    (let [base-meta {:content-type   (panto/mime-type-of file)
                     :content-length (.length file)}]
      (s3/put-object creds bucket key in (merge base-meta metadata)))))

(defn sync!
  "Sync files described by `file-maps` to S3 `bucket-name`.
   The file-maps collection may be ordered in which case S3
   operations will be executed in the same order.

   `file-maps`need to have the following keys `:s3-key` & `:file`.
   Optionally a `:metadata` key can be supplied to add custom
   metadata to the uploaded S3 object.

   Changes to an objects metadata will only get updated if also
   the object itself changed, i.e. changes only to an objects
   metadata will not cause the remote object to get updated.

   Recognized options:
   - `report-fn` takes 2 arguments (type, data) will be called
     for each added and changed file being uploaded to S3.
   - `dry-run?` if truthy no side effects will be executed. 
   - if `prune?` is a truthy value `sync!` will delete files
     from the S3 bucket that are not in `file-map`."
  ([cred bucket-name file-maps]
   (sync! bucket-name file-maps {}))
  ([cred bucket-name file-maps {:keys [report-fn prune? dry-run?]}]
   (validate-creds! cred)
   (s/validate [FileMap] file-maps)
   (let [report*   (or report-fn (fn [_]))
         upload*   (partial upload cred bucket-name)
         objs      (get-bucket-objects cred bucket-name)
         ops       (calculate-ops objs file-maps)]
     (reduce (fn [stats {:keys [s3-key file metadata] :as op}]
               (cond (= ::upload (:op op))
                     (do (report* op)
                         (when-not dry-run?
                           (upload* s3-key file metadata))
                         (update stats :uploaded conj s3-key))

                     (= ::update (:op op))
                     (do (report* op)
                         (when-not dry-run?
                           (upload* s3-key file metadata))
                         (update stats :updated conj s3-key))

                     (= ::delete (:op op))
                     (if prune? 
                       (do (report* op)
                           (when-not dry-run?
                             ;; possible optimization: `s3/delete-objects`
                             (s3/delete-object cred bucket-name s3-key))
                           (update stats :deleted conj s3-key))
                       stats)

                     (= ::no-op (:op op))
                     (update stats :unchanged conj s3-key)

                     :else
                     (throw (ex-info "Unrecognized sync op" op))))

     {:uploaded #{}, :updated #{},
      :deleted #{}, :unchanged #{}}
     ops))))

(defn cloudfront-invalidate!
  "Create an invalidation for `paths` in `dist`."
  [cred distribution-id paths]
  (cf/create-invalidation
   cred
   :distribution-id distribution-id
   :invalidation-batch {:paths {:items    paths
                                :quantity (count paths)}
                        :caller-reference (str (java.util.UUID/randomUUID))}))

(comment
  (def bucket "my-website-fsd-com-confetti-static-sit-sitebucket-1wtc4vo9fmlc5")
  (def creds (read-string (slurp "aws-cred.edn")))
  (def bucket-objs (get-bucket-objects creds bucket))

  (clojure.pprint/pprint bucket-objs)

  (clojure.pprint/pprint
   (map #(s3/get-object-metadata creds bucket %) (map :key bucket-objs)))
  
  (bucket-objects->diff-set bucket-objs)

  (clojure.pprint/pprint
   (calculate-ops bucket-objs fm))

  (def fm (dir->file-maps d))

  (clojure.pprint/pprint
   (dir->file-maps d))

  (clojure.pprint/pprint
   (file-maps->diff-set (dir->file-maps d)))

  (sync! creds bucket (reverse (dir->file-maps d))
         {:dry-run?  true
          :report-fn confetti.report/s3-report})

  (invalidate! creds "E12QOFOQTRE6O6" ["/confetti/report.clj"])
  (cf/get-invalidation :distribution-id "E12QOFOQTRE6O6" :id "I27L7VP8XR8OE2")

  (def d (io/file "../confetti/src"))

  (clojure.pprint/pprint ; plain diff*
   (diff* bucket-objs (dir->file-maps d)))

  (clojure.pprint/pprint ; deduped
   (dedupe-diff (diff* bucket-objs (dir->file-maps d))))

  (clojure.pprint/pprint ; ops
   (calculate-ops bucket-objs (dir->file-maps d)))

  (file-maps->diff-set (dir->file-maps d))

  (first (file-maps->diff-set (dir->file-maps d)))

  (def d (io/file "/Users/martin/code/martinklepsch.org/target/public"))
  (def f (io/file d "about.html"))
  (take 3 (read-dir d))

  (relative-path d (second (file-seq d)))

  (.getPath d)

  (last (filter #(.isFile %) (file-seq (io/file "."))))

  (s3/put-object "www.martinklepsch.org" "foox-util.clj" f)

  )
