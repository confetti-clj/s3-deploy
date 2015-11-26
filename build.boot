(set-env!
 :source-paths   #{"src"}
 :dependencies '[[adzerk/bootlaces "0.1.11" :scope "test"]
                 [amazonica/amazonica "0.3.33"]
                 [digest "1.4.4"]])

(require '[adzerk.bootlaces :refer [bootlaces! build-jar push-snapshot push-release]])

(def +version+ "0.1.0-SNAPSHOT")
(bootlaces! +version+)

(task-options!
 pom {:project     'confetti/s3-deploy
      :version     +version+
      :description "Push things to S3, but be lazy about it."
      :url         "https://github.com/confetti/s3-deploy"
      :scm         {:url "https://github.com/confetti/s3-deploy"}})
