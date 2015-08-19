(ns github-issues-dump.api
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]
            [org.httpkit.client :as http]))

(defn- error-message [response]
  (get-in response [:parsed-body :message]))

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

(def base-url "https://api.github.com")

(defn get-resource [token url-path & [query-params]]
  (let [url (format (str base-url "%s") (string/replace url-path base-url ""))
        headers {"Accept" "application/vnd.github.raw+json"}
        options (merge {:oauth-token token
                        :headers headers}
                  (when query-params {:query-params query-params}))
        response (http-get url options)
        body (json->map response)
        parsed-response (assoc response :parsed-body body)]
    (or (response-error parsed-response)
        parsed-response)))

;; Pagination

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

(defn page-number [url]
  (let [[_ page-num] (re-find #"(?:&|\?)page=(\d+)" (or url ""))]
    (if page-num
        (Integer. page-num)
        1)))

(defn all-results [body-fn token url & [query-params]]
  (let [response (get-resource token url query-params)
        {:keys [headers]} response
        body (body-fn response)
        page-num (-> response :opts :url page-number)
        results [[page-num body]]
        next-page-link (next-page headers)]
    (if next-page-link
        (lazy-cat results (all-results body-fn token next-page-link))
        results)))

;; Resources

(def base-query
  {:per_page 100
   :sort "created"
   :direction "asc"})

(defn issues [token & [organization]]
  (let [url (if organization
                (format "/orgs/%s/issues" organization)
                "/issues")
        query-params (assoc base-query :filter "all" :state "all")]
    (all-results :body token url query-params)))

(defn repositories [token & [organization]]
  (let [url (if organization
                (format "/orgs/%s/repos" organization)
                "/user/repos")
        query-params (assoc base-query :visibility "all")]
    (mapcat second (all-results :parsed-body token url query-params))))

(defn issue-comments* [token repository]
  (let [owner (get-in repository [:owner :login])
        repo-name (:name repository)
        url (format "/repos/%s/%s/issues/comments" owner repo-name)]
    [[owner repo-name] (all-results :body token url base-query)]))

(defn issue-comments [token repos]
  (map #(issue-comments* token %) repos))

(defn review-comments* [token repository]
  (let [owner (get-in repository [:owner :login])
        repo-name (:name repository)
        url (format "/repos/%s/%s/pulls/comments" owner repo-name)]
    [[owner repo-name] (all-results :body token url base-query)]))

(defn review-comments [token repos]
  (map #(review-comments* token %) repos))
