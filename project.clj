(defproject overtone "0.1.5"
  :description "An audio/musical experiment."
  :url "http://project-overtone.org"
  :autodoc {:load-except-list [#"/test/" #"/classes/" #"/devices/"]
            :namespaces-to-document ["overtone.core" "overtone.gui"
                                     "overtone.music" "overtone.studio"]
            :trim-prefix "overtone.",}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [overtone/scsynth-jna "0.1.2-SNAPSHOT"]
;                 [overtone/scsynth-interop "0.2.0-SNAPSHOT"]
                 [overtone/osc-clj "0.3.0-SNAPSHOT"]
                 [overtone/byte-spec "0.2.0-SNAPSHOT"]
                 [overtone/midi-clj "0.2.0-SNAPSHOT"]
                 [org.clojars.overtone/vijual "0.2.1"]]

  :multi-deps {"1.3" [[org.clojure/clojure "1.3.0-beta1"]
                      [overtone/scsynth-jna "0.1.2-SNAPSHOT" :exclusions [org.clojure/clojure-contrib]]
                      [overtone/osc-clj "0.3.0-SNAPSHOT" :exclusions [org.clojure/clojure-contrib]]
                      [overtone/byte-spec "0.2.0-SNAPSHOT" :exclusions [org.clojure/clojure-contrib]]
                      [overtone/midi-clj "0.2.0-SNAPSHOT" :exclusions [org.clojure/clojure-contrib]]
                      [org.clojars.overtone/vijual "0.2.1" :exclusions [org.clojure/clojure-contrib]]
                      [org.clojure.contrib/core "1.3.0-alpha4"] 
                      [org.clojure.contrib/math "1.3.0-alpha4"]
                      [org.clojure.contrib/types "1.3.0-alpha4"]
                      [org.clojure.contrib/generic "1.3.0-alpha4"]
                      [org.clojure.contrib/def "1.3.0-alpha4"]
                      [org.clojure.contrib/fcase "1.3.0-alpha4"]]}
  
  :dev-dependencies [[marginalia "0.2.0"]
                     [lein-multi "1.1.0-SNAPSHOT"]]
  ;;:aot ["overtone.live"]
  :jvm-opts ["-Xms256m" "-Xmx1g" "-XX:+UseConcMarkSweepGC"])
