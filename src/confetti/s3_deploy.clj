(ns confetti.s3-deploy
  (:require [clojure.java.io :as io]
            [clojure.set :as s]
            [clojure.data :as data]
            [clojure.string :as string]
            [digest :as dig]
            [amazonica.aws.s3 :as s3]))

(defn relative-path [dir f]
  (string/replace (.getPath f) (re-pattern (str (.getPath dir) "/")) ""))

(defn get-bucket-objects [cred bucket-name]
  (:object-summaries (s3/list-objects cred :bucket-name bucket-name)))

(defn dir->file-map
  "Create a file-map as it's expected by `diff*` from
   a local directory."
  [dir]
  (into {}
        (for [f (filter #(.isFile %) (file-seq dir))]
          [(relative-path dir f) f])))

(defn ^:private bucket-objects->diff-set [bucket-objects]
  (let [prepare (comp (map #(select-keys % [:key :etag]))
                      (map (fn [{:keys [key etag]}] [key etag])))]
    (into {} prepare bucket-objects)))

(defn ^:private file-map->diff-set [file-map]
  (into {} (map (fn [[k f]] [k (dig/md5 f)])) file-map))

(defn diff*
  "Get the difference between objects in a S3 bucket and
   files on disk.

   - `bucket-objects` is expected to be a list bucket object
     summaries as they are returned by `amazonica.aws.s3/list-objects`.

   - `file-map` is expected to be a map of `{s3-key java.io.File}`,
     where s3-key is the desired location in the S3 bucket."
  [bucket-objects file-map]
  (let [on-s3?   #((set (map :key bucket-objects)) (key %))
        on-s3    (bucket-objects->diff-set bucket-objects)
        on-fs    (file-map->diff-set file-map)
        [removed a-or-c in-sync] (data/diff on-s3 on-fs)]
    {:added     (into {} (remove on-s3? a-or-c))
     :changed   (into {} (filter on-s3? a-or-c))
     :removed   removed
     :unchanged in-sync}))

(defn sync!
  "Sync files in `file-map` to S3 bucket `bucket-name`.

   - `report-fn` takes 2 arguments (type, data) will be called
     for each added and changed file being uploaded to S3.
   - if `prune?` is a truthy value `sync!` will delete files
     from the S3 bucket that are not in `file-map`."
  ([cred bucket-name file-map]
   (sync! bucket-name file-map {}))
  ([cred bucket-name file-map {:keys [report-fn prune? dry-run?]}]
   (assert (and (string? (:access-key cred))
                (string? (:secret-key cred))) cred)
   (let [report* (or report-fn (fn [_]))
         {:keys [added changed removed] :as diff}
         (diff* (get-bucket-objects cred bucket-name) file-map)]
     (doseq [k (keys added)
             :let [f (get file-map k)]]
       (report* {:type ::added :s3-key k :file f})
       (when-not dry-run?
         (s3/put-object cred bucket-name k f)))
     (when prune?
       (doseq [k (keys removed)]
         (report* {:type ::removed :s3-key k})
         #_"TODO DELETION"))
     (doseq [k (keys changed)
             :let [f (get file-map k)]]
       (report* {:type ::changed :s3-key k :file f})
       (when-not dry-run?
         (s3/put-object cred bucket-name k f))))))

(comment
  (def bucket "www.martinklepsch.org")
  (def bucket-objs (:object-summaries (s3/list-objects :bucket-name bucket)))

  (first bucket-objs)

  (sync! {:bucket-name bucket
          :file-map    (dir->file-map d)
          :report-fn   (fn [t d] (println t (if-let [f (:file d)] (.getPath f) (:s3-key d))))})

  (:removed (diff* bucket-objs (dir->file-map d)))

  (count (file-map->diff-set (dir->file-map d)))

  (first (file-map->diff-set (dir->file-map d)))

  (def d (io/file "/Users/martin/code/martinklepsch.org/target/public"))
  (def f (io/file d "about.html"))
  (take 3 (read-dir d))

  (relative-path d (second (file-seq d)))

  (.getPath d)

  (last (filter #(.isFile %) (file-seq (io/file "."))))

  (s3/put-object "www.martinklepsch.org" "foox-util.clj" f)

  )
