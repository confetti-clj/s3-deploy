<img src="https://cloud.githubusercontent.com/assets/97496/11431670/0ef1bb58-949d-11e5-83f7-d07cf1dd89c7.png" alt="confetti logo" align="right" />

# confetti/s3-deploy

Simple utility functions to diff and sync local files with S3 buckets. *(100LOC)*

[](dependency)
```clojure
[confetti/s3-deploy "0.1.0-SNAPSHOT"] ;; latest release
```
[](/dependency)

## Goals

- a simple data-driven API to sync local files to an S3 bucket
- useful reporting capabilities to inform users about the sync process
- sync metadata and provide versatile ways to specify it **(TBD)**
- allow some ordering of uploads to get "fake-transactionality"

## Walkthrough

To get a diff between an S3 bucket and local files use `confetti.s3-deploy/diff*`. To actually sync files use `confetti.s3-deploy/sync!`.

---

`(confetti.s3-deploy/diff* bucket-objects file-maps)`

Get the difference between objects in a S3 bucket and files on disk.

- `bucket-objects` is expected to be a list bucket object summaries as they are returned by `amazonica.aws.s3/list-objects`.
- `file-map` is expected to be a map of `{s3-key java.io.File}`, where s3-key is the desired location in the S3 bucket.

---

`(confetti.s3-deploy/sync! cred bucket-name file-maps opts)`

Sync files in `file-maps` to S3 bucket `bucket-name`. A single `file-map` should look like `{:s3-key "file.txt" :file java.io.File}`.
The ordering of items in `file-maps` is respected when syncing files. Deleted files are always synced last.

Optional `opts` argument is a map with the following keys:

- `report-fn` takes 2 arguments (type, data) will be called for each added and changed file being uploaded to S3.
- if `prune?` is a truthy value `sync!` will delete files from the S3 bucket that are not in `file-map`.

--- 

 `file-maps`

A `file-map` is a simple map construct that makes the API of this
library very minimal and versatile at the same time. `file-map`s
have the following structure: `{s3-key java.io.File}`. An example
implementation to get a `file-map` for a given directory can be found
below and is provided as `(confetti.s3-deploy/dir->file-map java.io.File`).

```clojure
(defn relative-path [dir f]
  (string/replace (.getPath f) (re-pattern (str (.getPath dir) "/")) ""))

(defn dir->file-map
  "Create a file-map as it's expected by `diff*` from a local directory."
  [dir]
  (into {}
        (for [f (filter #(.isFile %) (file-seq d))]
          [(relative-path dir f) f])))
```

## License

TBD
