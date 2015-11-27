(ns confetti.s3-deploy
  (:require [clojure.java.io :as io]
            [clojure.set :as s]
            [clojure.data :as data]
            [clojure.string :as string]
            [digest :as dig]
            [amazonica.aws.s3 :as s3]))

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
  (let [on-s3?   #((set (map :key bucket-objects)) (key %))
        on-s3    (bucket-objects->diff-set bucket-objects)
        on-fs    (file-maps->diff-set file-maps)
        [removed a-or-c in-sync] (data/diff on-s3 on-fs)]
    {:added     (into {} (remove on-s3? a-or-c))
     :changed   (into {} (filter on-s3? a-or-c))
     :removed   removed
     :unchanged in-sync}))

(defn ->op
  "Given a diff and a key return the required operation to sync

   NOTE: If diff would not return overlapping `changed` and `removed`
   values this function would be much simpler."
  [diff s3-key]
  (let [added?     (-> (:added diff) keys set)
        changed?   (-> (:changed diff) keys set)
        removed?   (-> (:removed diff) keys set (s/difference changed?))
        unchanged? (-> (:unchanged diff) keys set)]
    (cond (added? s3-key)     ::upload
          (changed? s3-key)   ::update
          (removed? s3-key)   ::delete
          (unchanged? s3-key) ::no-op

          :else (throw (ex-info "No op found!" {:s3-key s3-key :diff diff})))))

(defn calculate-ops
  "Generate set of operations to get in sync from a given diff"
  [bucket-objects file-maps]
  (let [diff    (diff* bucket-objects file-maps)
        deleted (:removed diff)
        fm->op  #(assoc % :op (->op diff (:s3-key %)))
        del->op (fn [[k _]] {:s3-key k :op (->op diff k)})]
    (into (mapv fm->op file-maps)
          (mapv del->op deleted))))

(defn sync!
  "Sync files described by `file-maps` to S3 `bucket-name`.
   The file-maps collection may be odered in which case S3
   operations will be executed in the same order.

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
   (let [report*   (or report-fn (fn [_]))
         objs      (get-bucket-objects cred bucket-name)
         ops       (calculate-ops objs file-maps)]
     (reduce (fn [stats {:keys [s3-key file] :as op}]
               (cond (= ::upload (:op op))
                     (do (report* op)
                         (when-not dry-run?
                           (s3/put-object cred bucket-name s3-key file))
                         (update stats :uploaded conj s3-key))

                     (= ::update (:op op))
                     (do (report* op)
                         (when-not dry-run?
                           (s3/put-object cred bucket-name s3-key file))
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

  (def d (io/file "../confetti/src"))

  (clojure.pprint/pprint
   (diff* bucket-objs (dir->file-maps d)))
  (clojure.pprint/pprint
   (diff->ops (diff* bucket-objs (dir->file-maps d))))

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
