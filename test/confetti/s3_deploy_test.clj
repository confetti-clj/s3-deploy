(ns confetti.s3-deploy-test
  (:require [confetti.s3-deploy :as s3d]
            [clojure.java.io :as io]
            [clojure.test :as t]
            [schema.test]))

(t/use-fixtures :once schema.test/validate-schemas)

(t/deftest diff-test
  (let [defaults {:added {} :changed {} :removed {} :unchanged {}}
        v1-gzip {:etag "v1", :metadata {:content-encoding "gzip"}}
        v1-gzip-css {:etag "v1", :metadata {:content-type "text/css" :content-encoding "gzip"}}
        v2-gzip {:etag "v2", :metadata {:content-encoding "gzip"}}
        v1-html {:etag "v1", :metadata {:content-type "text/html"}}
        v2-html {:etag "v2", :metadata {:content-type "text/html"}}]
    ;; (t/is (= defaults (s3d/diff* {} {})))
    ;; (t/is (= (assoc defaults :added {"x.html" "v1"})
    ;;          (s3d/diff* {} {"x.html" "v1"})) "Upload first & single file")
    ;; (t/is (= (merge defaults {:changed {"x.html" "v2"} :removed {"x.html" "v1"}})
    ;;          (s3d/diff* {"x.html" "v1"} {"x.html" "v2"})) "Update existing file")
    ;; (t/is (= (merge defaults {:added {"x.css" "v1"} :removed {"x.html" "v1"}})
    ;;          (s3d/diff* {"x.html" "v1"} {"x.css" "v1"})) "Upload one, delete one")
    ;; (t/is (= (merge defaults {:added {"x.css" "v1"} :unchanged {"x.html" "v1"}})
    ;;          (s3d/diff* {"x.html" "v1"} {"x.css" "v1", "x.html" "v1"})) "Upload one, keep one")
    ;; --- NEW
    (t/testing "Diffing with empty remote"
      (t/is (= (assoc defaults :added {"x.css" v1-gzip, "x.html" v1-html})
               (s3d/diff* {} {"x.css" v1-gzip, "x.html" v1-html}))))
    (t/testing "Diffing with one file already present"
      (t/is (= (assoc defaults
                      :added {"x.html" v1-html}
                      :unchanged {"x.css" v1-gzip})
               (s3d/diff* {"x.css" v1-gzip} {"x.css" v1-gzip, "x.html" v1-html}))))
    (t/testing "Diffing without any change"
      (t/is (= (assoc defaults :unchanged {"x.css" v1-gzip})
               (s3d/diff* {"x.css" v1-gzip} {"x.css" v1-gzip}))))
    (t/testing "Diffing with change in content"
      (t/is (= (assoc defaults
                      :changed {"x.css" v2-html}
                      :removed {"x.css" v1-html})
               (s3d/diff* {"x.css" v1-html} {"x.css" v2-html}))))
    (t/testing "Diffing with change in metadata (not content)"
      (t/is (= (assoc defaults
                      :changed {"x.css" v1-gzip-css}
                      :removed {"x.css" v1-gzip})
               (s3d/diff* {"x.css" v1-gzip} {"x.css" v1-gzip-css}))))
    (t/testing "Diffing w/o change in metadata but only partial metadata supplied from client"
      (t/is (= (assoc defaults :unchanged {"x.css" v1-gzip})
               (s3d/diff* {"x.css" (assoc-in v1-gzip [:metadata :bogus] "test")}
                          {"x.css" v1-gzip}))))

    ))
  

(comment
  (t/run-tests 'confetti.s3-deploy-test)


  )
