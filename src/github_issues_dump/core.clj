(ns github-issues-dump.core
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [cli]]
            [github-issues-dump.api :as api]))

(defn dump-issues [token organization]
  (let [issues (api/issues token organization)]
    (doseq [[page body] issues
            :let [filename (if organization
                               (format "issues/orgs/%s/issues/%s.json" organization page)
                               (format "issues/%s.json" page))]]
      (println "Writing file: " filename)
      (io/make-parents filename)
      (spit filename body))))

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

    (dump-issues token organization)))