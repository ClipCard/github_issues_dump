(ns github-issues-dump.api
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [org.httpkit.client :as http]))

(defn- error-message [response]
  (get-in response [:body :message]))

(defn- auth-failure
  [response]
  (when (= 401 (:status response))
    (throw (ex-info "Authorization Failure" {:response response}))))

(defn bad-req [response]
  (when (#{400 404} (:status response))
    (throw (ex-info "Bad Request" response))))

(defn rate-limit-ms
  "GitHub gives us 'the time at which the current rate limit window
  resets in UTC epoch seconds', as a String. We produce a number of
  milliseconds from current time, as a number."
  [x-ratelimit-reset]
  (let [end-ms (* 1000 (Long. x-ratelimit-reset))
        now-ms (coerce/to-long (time/now))]
    (max 0 (- end-ms now-ms))))

(defn- service-rate-limit
  [{:keys [headers status] :as response}]
  (when (and (= 403 status)
             (= "0" (:x-ratelimit-remaining headers)))
    (throw (ex-info "Rate Limited"
            {:rate-limit-ms (rate-limit-ms (:x-ratelimit-reset headers))
             :message (error-message response)}))))

(defn- service-unexpected-cond
  [response]
  (when (>= (:status response) 500)
    (throw (ex-info "Unexpected condition"
            {:response response :message (error-message response)}))))

(defn- response-error
  [response]
  (when (>= (:status response) 400)
    (or (auth-failure response)
        (bad-req response)
        (service-rate-limit response)
        (service-unexpected-cond response))))

(defn github-issues-endpoint [& [organization]]
  (if organization
      (format "https://api.github.com/orgs/%s/issues" organization)
      "https://api.github.com/issues"))

(def query-params
  {:per_page 100
   :filter "all"
   :state "all"
   :sort "created"
   :direction "asc"})

(defn json->map
  "Convert JSON body to a map."
  [{:keys [body] :as response}]
  (when-not (string/blank? body)
    (json/read-str body :key-fn keyword)))

(defn http-get [url options]
  (let [response @(http/get url options)
        error (:error response)]
    (if error
        (throw error)
        response)))

(defn get-json [token url & [query-params]]
  (let [headers {"Accept" "application/vnd.github.raw+json"}
        options (merge {:oauth-token token
                        :headers headers}
                  (when query-params {:query-params query-params}))
        response (http-get url options)
        body (json->map response)
        parsed-response (assoc response :body body)]
    (or (response-error parsed-response)
        response)))

(defn issues*
  [token & [organization page-link]]
  (let [url (or page-link (github-issues-endpoint organization))
        query-params* (when-not page-link query-params)]
    (get-json token url query-params*)))

(defn parse-link [link]
  (let [[_ url] (re-find #"<(.*)>" link)
        [_ rel] (re-find #"rel=\W(.*)\W" link)]
    [(keyword rel) url]))

(defn parse-links
  "Takes the content of the link header from a github resp, returns a map of links"
  [link-body]
  (->> (string/split link-body #",")
       (map parse-link)
       (into {})))

(defn next-page
  [{:keys [link]}]
  (when link
    (let [link-map (parse-links link)]
      (:next link-map))))

(defn page-number [page-link]
  (let [[_ page-num] (re-find #"(?:&|\?)page=(\d+)" (or page-link ""))]
    (if page-num
        (Integer. page-num)
        1)))

(defn issues [token & [organization page-link]]
  (let [response (issues* token organization page-link)
        {:keys [headers body]} response
        page-num (page-number page-link)
        results [[page-num body]]
        next-page-link (next-page headers)]
    (if next-page-link
        (lazy-cat results (issues token organization next-page-link))
        results)))
