<img src="https://cloud.githubusercontent.com/assets/97496/11431670/0ef1bb58-949d-11e5-83f7-d07cf1dd89c7.png" alt="confetti logo" align="right" />

# confetti/s3-deploy

[goals](#goals) | [usage](#usage) | [changes](#changes)

Simple utility functions to diff and sync local files with S3 buckets.

[](dependency)
```clojure
[confetti/s3-deploy "0.1.2"] ;; latest release
```
[](/dependency)

## Goals

- a simple data-driven API to sync local files to an S3 bucket
- useful reporting capabilities to inform users about the sync process
- allow some ordering of uploads to get "fake-transactionality"
- allow specification of custom metadata

## Usage

Most functions that are part of the public API of this library operate
on simple maps like the following, furthermore called `file-maps`:
```clojure
{:s3-key   "desired/destination/file.txt"
 :file     #object[java.io.File "file.txt.gz"]
 :metadata {:content-encoding "gzip"}}
```
By using `file-maps` we decouple the structure of the filesystem from
the structure we ultimately want to achieve in our target S3 bucket.

> By default the `:content-type` metadata is derived from the extension
> of the value you provided as `:s3-key`.

Syncing is possible via `confetti.s3-deploy/sync!`:
```clojure
(confetti.s3-deploy/sync! creds bucket-name file-maps)
```
To generate `file-maps` from a directory this library ships a tiny
helper `dir->file-maps` that will generate file-maps:
```clojure
(dir->file-maps (io/file "src"))
;;=> [{:s3-key "confetti/s3_deploy.clj",
;;     :file   #object[java.io.File 0x4795c68f "/Users/martin/code/confetti-s3-deploy/src/confetti/s3_deploy.clj"]}]
```
Depending on your use case you will want to build your own `file-maps`
generating function. Lower level functions are available as well:
```clojure
(confetti.s3-deploy/diff* bucket-objects file-maps)
```
Can be used to get a diff between a buckets objects and a given collection
of `file-maps`.
```clojure
(confetti.s3-deploy/calculate-ops bucket-objects file-maps)
```
Will return a vector of operations needed to get the bucket in sync with
the supplied `file-maps`.

For more details check [the implementation](https://github.com/confetti-clj/s3-deploy/blob/master/src/confetti/s3_deploy.clj).

## Changes

#### 0.1.2

- improve implementation of `relative-path` function so that it works properly on Windows. ([#16](https://github.com/confetti-clj/s3-deploy/pull/16))
