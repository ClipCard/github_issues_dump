(defproject github-issues-dump "1.0.0"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"
            :key "mit"
            :year 2015
            :distribution :repo}
  :main github-issues-dump.core
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.json "0.2.5"]
                 [org.clojure/tools.cli "0.2.4"]
                 [clj-time "0.4.1"]
                 [http-kit "2.1.18"]])
