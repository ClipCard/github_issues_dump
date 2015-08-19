(ns github-issues-dump.core
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [cli]]
            [github-issues-dump.api :as api]))

(defn spit* [filename body]
  (println "Writing file: " filename)
  (io/make-parents filename)
  (spit filename body))

(defn dump-issues [token organization]
  (let [issues (api/issues token organization)]
    (doseq [[page body] issues
            :let [filename (if organization
                               (format "dump/%s/issues/%s.json" organization page)
                               (format "dump/issues/%s.json" page))]]
      (spit* filename body))))

(defn dump-comments* [filename-pattern comments]
  (doseq [[[owner repo] pages] comments]
    (doseq [[page body] pages
            :let [filename (format filename-pattern owner repo page)]]
    (spit* filename body))))

(defn dump-comments [token organization]
  (let [repos (api/repositories token organization)
        issue-comments (api/issue-comments token repos)
        review-comments (api/review-comments token repos)]
    (dump-comments* "dump/%s/%s/issues/comments/%s.json" issue-comments)
    (dump-comments* "dump/%s/%s/pulls/comments/%s.json" review-comments)))

(def cli-args
  [["-h" "--help" "Show help" :flag true :default false]
   ["-t" "--token"
    "GitHub authorization token - create one at https://github.com/settings/tokens"]
   ["-o" "--organization" "Optional organization limits the scope of issues captured"]])

(defn -main [& args]
  (let [[options _ banner] (apply cli args cli-args)
        {:keys [help organization token]} options]

    (when help
      (println banner)
      (System/exit 0))

    (dump-issues token organization)
    (dump-comments token organization)))
