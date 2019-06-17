(ns hato.middleware
  "Adapted from https://www.github.com/dakrone/clj-http"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.util
    Base64)
   (java.io
    InputStream
    ByteArrayInputStream
    ByteArrayOutputStream BufferedInputStream)
   (java.net
    URLDecoder
    URLEncoder
    URL)
   (java.util.zip
    GZIPInputStream InflaterInputStream ZipException Inflater)))

;; Cheshire is an optional dependency, so we check for it at compile time.
(def json-enabled?
  (try
    (require
     'cheshire.core)
    true
    (catch Throwable _ false)))

;; Transit is an optional dependency, so check at compile time.
(def transit-enabled?
  (try
    (require
     'cognitect.transit)
    true
    (catch Throwable _ false)))

(defn transit-opts-by-type
  "Returns the Transit options by type."
  [type opts]
  {:pre [transit-enabled?]}
  (cond
    (empty? opts)
    opts
    (contains? opts type)
    (clojure.core/get opts type)
    :else
    {}))

(def transit-read-opts
  (partial transit-opts-by-type :decode))

(def transit-write-opts
  (partial transit-opts-by-type :encode))

(defn ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [^InputStream in type & [opts]]
  {:pre [transit-enabled?]}
  (when (pos? (.available in))
    (let [reader (ns-resolve 'cognitect.transit 'reader)
          read (ns-resolve 'cognitect.transit 'read)]
      (read (reader in type (transit-read-opts opts))))))

(defn ^:dynamic transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  {:pre [transit-enabled?]}
  (let [output (ByteArrayOutputStream.)
        writer (ns-resolve 'cognitect.transit 'writer)
        write (ns-resolve 'cognitect.transit 'write)]
    (write (writer output type (transit-write-opts opts)) out)
    (.toByteArray output)))

(defn ^:dynamic json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

(defn ^:dynamic json-decode
  "Resolve and apply cheshire's json decoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

(defn ^:dynamic json-decode-strict
  "Resolve and apply cheshire's json decoding dynamically (with lazy parsing disabled)."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

;;;

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn opt
  "Checks the request parameters for a keyword boolean option, with or without the ?

  Returns false if either of the values are false, or the value of
  (or key1 key2) otherwise (truthy)"
  [req param]
  (let [param-? (keyword (str (name param) "?"))
        v1 (clojure.core/get req param)
        v2 (clojure.core/get req param-?)]
    (if (false? v1)
      false
      (if (false? v2)
        false
        (or v1 v2)))))

(defn url-encode
  "Returns a UTF-8 URL encoded version of the given string."
  [^String unencoded & [^String encoding]]
  (URLEncoder/encode unencoded (or encoding "UTF-8")))


;;;


(defn url-encode-illegal-characters
  "Takes a raw url path or query and url-encodes any illegal characters.
  Minimizes ambiguity by encoding space to %20."
  [path-or-query]
  (when path-or-query
    (-> path-or-query
        (str/replace " " "%20")
        (str/replace
         #"[^a-zA-Z0-9\.\-\_\~\!\$\&\'\(\)\*\+\,\;\=\:\@\/\%\?]"
         url-encode))))

(defn parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)]
    {:scheme       (keyword (.getProtocol url-parsed))
     :server-name  (.getHost url-parsed)
     :server-port  (when-pos (.getPort url-parsed))
     :uri          (url-encode-illegal-characters (.getPath url-parsed))
     :url          url
     :user-info    (when-let [user-info (.getUserInfo url-parsed)]
                     (URLDecoder/decode user-info))
     :query-string (url-encode-illegal-characters (.getQuery url-parsed))}))


;; Statuses for which hato will not throw an exception


(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307})

(defn- exceptions-response
  [req {:keys [status] :as resp}]
  (if (unexceptional-status? status)
    resp
    (cond
      (false? (opt req :throw-exceptions))
      resp

      :else
      (throw (ex-info (str "status: " status) resp)))))

(defn wrap-exceptions
  "Middleware that throws response as an ExceptionInfo if the response has
  unsuccessful status code. :throw-exceptions set to false in the request
  disables this middleware."
  [client]
  (fn
    ([req]
     (exceptions-response req (client req)))
    ([req response raise]
     (client req #(response (exceptions-response req %)) raise))))

;; Multimethods for coercing body type to the :as key
(defmulti coerce-response-body (fn [req _] (:as req)))

(defn coerce-json-body
  [{:keys [coerce]} {:keys [body status] :as resp} keyword? strict?]
  (let [charset (or (-> resp :content-type-params :charset)
                    "UTF-8")
        ; TODO consider using stream
        decode-func (if strict? json-decode-strict json-decode)]
    (if json-enabled?
      (cond
        (= coerce :always)
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (unexceptional-status? status)
             (or (nil? coerce) (= coerce :unexceptional)))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (not (unexceptional-status? status)) (= coerce :exceptional))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        :else (assoc resp :body (String. ^"[B" body charset)))

      (assoc resp :body (String. ^"[B" body charset)))))

(defn coerce-clojure-body
  [_ {:keys [body] :as resp}]
  (let [charset (or (-> resp :content-type-params :charset) "UTF-8")]
    (assoc resp :body (edn/read-string (String. ^"[B" body charset)))))

(defn coerce-transit-body
  [{:keys [transit-opts]} {:keys [body] :as resp} type]
  (if transit-enabled?
    (with-open [bs (ByteArrayInputStream. body)]
      (assoc resp :body (parse-transit bs type transit-opts)))

    resp))

(defmethod coerce-response-body :json [req resp]
  (coerce-json-body req resp true false))

(defmethod coerce-response-body :json-strict [req resp]
  (coerce-json-body req resp true true))

(defmethod coerce-response-body :json-strict-string-keys [req resp]
  (coerce-json-body req resp false true))

(defmethod coerce-response-body :json-string-keys [req resp]
  (coerce-json-body req resp false false))

(defmethod coerce-response-body :clojure [req resp]
  (coerce-clojure-body req resp))

(defmethod coerce-response-body :transit+json [req resp]
  (coerce-transit-body req resp :json))

(defmethod coerce-response-body :transit+msgpack [req resp]
  (coerce-transit-body req resp :msgpack))

(defmethod coerce-response-body :byte-array [_ resp]
  resp)

(defmethod coerce-response-body :stream [_ resp]
  resp)

(defmethod coerce-response-body :default
  [_ {:keys [body] :as resp}]
  (assoc resp :body (String. body "UTF-8")))

(defn- output-coercion-response
  [req {:keys [body] :as resp}]
  (if body
    (coerce-response-body req resp)
    resp))

(defn wrap-output-coercion
  "Middleware converting a response body from a byte-array to a different object.
  Defaults to a String if no :as key is specified, the `coerce-response-body`
  multimethod may be extended to add additional coercions."
  [client]
  (fn
    ([req]
     (output-coercion-response req (client req)))
    ([req respond raise]
     (client req
             #(respond (output-coercion-response req %))
             raise))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn- content-type-request
  [{:keys [content-type character-encoding] :as req}]
  (if content-type
    (let [ctv (content-type-value content-type)
          ct (if character-encoding
               (str ctv "; charset=" character-encoding)
               ctv)]
      (update-in req [:headers] assoc "content-type" ct))
    req))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [client]
  (fn
    ([req]
     (client (content-type-request req)))
    ([req respond raise]
     (client (content-type-request req) respond raise))))

(defn- accept-request
  [{:keys [accept] :as req}]
  (if accept
    (-> req (dissoc :accept)
        (assoc-in [:headers "accept"]
                  (content-type-value accept)))
    req))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [client]
  (fn
    ([req]
     (client (accept-request req)))
    ([req respond raise]
     (client (accept-request req) respond raise))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn- accept-encoding-request
  [{:keys [accept-encoding] :as req}]
  (if accept-encoding
    (-> req
        (dissoc :accept-encoding)
        (assoc-in [:headers "accept-encoding"]
                  (accept-encoding-value accept-encoding)))
    req))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [client]
  (fn
    ([req]
     (client (accept-encoding-request req)))
    ([req respond raise]
     (client (accept-encoding-request req) respond raise))))

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
   (when-let [found (when content-type
                      (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
     (second found))
   "UTF-8"))

(defn- multi-param-suffix [index multi-param-style]
  (case multi-param-style
    :indexed (str "[" index "]")
    :array "[]"
    ""))

(defn generate-query-string-with-encoding [params encoding multi-param-style]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map-indexed
                         #(str (URLEncoder/encode (name k) encoding)
                               (multi-param-suffix %1 multi-param-style)
                               "="
                               (URLEncoder/encode (str %2) encoding)) v)
                        [(str (URLEncoder/encode (name k) encoding)
                              "="
                              (URLEncoder/encode (str v) encoding))]))
                    params)))

(defn generate-query-string [params & [content-type multi-param-style]]
  (let [encoding (detect-charset content-type)]
    (generate-query-string-with-encoding params encoding multi-param-style)))

(defn- query-params-request
  [{:keys [query-params content-type multi-param-style]
    :or   {content-type :x-www-form-urlencoded}
    :as   req}]
  (if query-params
    (-> req (dissoc :query-params)
        (update-in [:query-string]
                   (fn [old-query-string new-query-string]
                     (if-not (empty? old-query-string)
                       (str old-query-string "&" new-query-string)
                       new-query-string))
                   (generate-query-string
                    query-params
                    (content-type-value content-type)
                    multi-param-style)))
    req))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [client]
  (fn
    ([req]
     (client (query-params-request req)))
    ([req respond raise]
     (client (query-params-request req) respond raise))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes basic-auth "UTF-8")))))

(defn- basic-auth-request
  [req]
  (if-let [basic-auth (:basic-auth req)]
    (-> req (dissoc :basic-auth)
        (assoc-in [:headers "authorization"]
                  (basic-auth-value basic-auth)))
    req))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (basic-auth-request req)))
    ([req respond raise]
     (client (basic-auth-request req) respond raise))))

(defn- oauth-request
  [req]
  (if-let [oauth-token (:oauth-token req)]
    (-> req (dissoc :oauth-token)
        (assoc-in [:headers "authorization"]
                  (str "Bearer " oauth-token)))
    req))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (oauth-request req)))
    ([req respond raise]
     (client (oauth-request req) respond raise))))

(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn- user-info-request
  [req]
  (if-let [[user password] (parse-user-info (:user-info req))]
    (assoc req :basic-auth [user password])
    req))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [client]
  (fn
    ([req]
     (client (user-info-request req)))
    ([req respond raise]
     (client (user-info-request req) respond raise))))

(defn- method-request
  [req]
  (if-let [m (:method req)]
    (-> req (dissoc :method)
        (assoc :request-method m))
    req))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [client]
  (fn
    ([req]
     (client (method-request req)))
    ([req respond raise]
     (client (method-request req) respond raise))))

(defmulti coerce-form-params
  (fn [req] (keyword (content-type-value (:content-type req)))))

(defmethod coerce-form-params :application/edn
  [{:keys [form-params]}]
  (pr-str form-params))

(defn- coerce-transit-form-params [type {:keys [form-params transit-opts]}]
  (when-not transit-enabled?
    (throw (ex-info (format (str "Can't encode form params as "
                                 "\"application/transit+%s\". "
                                 "Transit dependency not loaded.")
                            (name type))
                    {:type         :transit-not-loaded
                     :form-params  form-params
                     :transit-opts transit-opts
                     :transit-type type})))
  (transit-encode form-params type transit-opts))

(defmethod coerce-form-params :application/transit+json [req]
  (coerce-transit-form-params :json req))

(defmethod coerce-form-params :application/transit+msgpack [req]
  (coerce-transit-form-params :msgpack req))

(defmethod coerce-form-params :application/json
  [{:keys [form-params json-opts]}]
  (when-not json-enabled?
    (throw (ex-info (str "Can't encode form params as \"application/json\". "
                         "Cheshire dependency not loaded.")
                    {:type        :cheshire-not-loaded
                     :form-params form-params
                     :json-opts   json-opts})))
  (json-encode form-params json-opts))

(defmethod coerce-form-params :default [{:keys [content-type
                                                multi-param-style
                                                form-params
                                                form-param-encoding]}]
  (if form-param-encoding
    (generate-query-string-with-encoding form-params
                                         form-param-encoding multi-param-style)
    (generate-query-string form-params
                           (content-type-value content-type)
                           multi-param-style)))

(defn- form-params-request
  [{:keys [form-params content-type request-method]
    :or   {content-type :x-www-form-urlencoded}
    :as   req}]
  (if (and form-params (#{:post :put :patch :delete} request-method))
    (-> req
        (dissoc :form-params)
        (assoc :content-type (content-type-value content-type)
               :body (coerce-form-params req)))
    req))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [client]
  (fn
    ([req]
     (client (form-params-request req)))
    ([req respond raise]
     (client (form-params-request req) respond raise))))

(defn- url-request
  [req]
  (if-let [url (:url req)]
    (-> req (dissoc :url) (merge (parse-url url)))
    req))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [client]
  (fn
    ([req]
     (client (url-request req)))
    ([req respond raise]
     (client (url-request req) respond raise))))

(defn- request-timing-response
  [resp start]
  (assoc resp :request-time (- (System/currentTimeMillis) start)))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in milliseconds)
  of the request into the :request-time key in the response."
  [client]
  (fn
    ([req]
     (let [start (System/currentTimeMillis)
           resp (client req)]
       (request-timing-response resp start)))
    ([req respond raise]
     (let [start (System/currentTimeMillis)]
       (client req
               #(respond (request-timing-response % start))
               raise)))))

(defn gunzip
  "Returns a gunzip'd version of the given byte array or input stream."
  [b]
  (when b
    (cond
      (instance? InputStream b)
      (GZIPInputStream. b)

      :else
      (with-open [xin (GZIPInputStream. (io/input-stream b))
                  xout (ByteArrayOutputStream.)]
        (io/copy xin xout)
        (.toByteArray xout)))))

(defn inflate
  "Returns a zlib inflate'd version of the given byte array or InputStream."
  [b]
  (when b
    ;; This weirdness is because HTTP servers lie about what kind of deflation
    ;; they're using, so we try one way, then if that doesn't work, reset and
    ;; try the other way
    (let [stream (BufferedInputStream. (if (instance? InputStream b)
                                         b
                                         (ByteArrayInputStream. b)))
          _ (.mark stream 512)
          iis (InflaterInputStream. stream)
          readable? (try (.read iis) true
                         (catch ZipException _ false))
          _ (.reset stream)
          iis' (if readable?
                 (InflaterInputStream. stream)
                 (InflaterInputStream. stream (Inflater. true)))]

      (if (instance? InputStream b)
        iis'
        (with-open [xin iis'
                    xout (ByteArrayOutputStream.)]
          (io/copy xin xout)
          (.toByteArray xout))))))

;; Multimethods for Content-Encoding dispatch automatically
;; decompressing response bodies
(defmulti decompress-body
  (fn [resp] (get-in resp [:headers "content-encoding"])))

(defmethod decompress-body "gzip"
  [resp]
  (-> resp
      (update :body gunzip)
      (assoc :orig-content-encoding (get-in resp [:headers "content-encoding"]))
      (update :headers #(dissoc % "content-encoding"))))

(defmethod decompress-body "deflate"
  [resp]
  (-> resp
      (update :body inflate)
      (assoc :orig-content-encoding (get-in resp [:headers "content-encoding"]))
      (update :headers #(dissoc % "content-encoding"))))

(defmethod decompress-body :default [resp]
  (assoc resp
         :orig-content-encoding
         (get-in resp [:headers "content-encoding"])))

(defn- decompression-request
  [req]
  (if (false? (opt req :decompress-body))
    req
    (update-in req [:headers "accept-encoding"]
               #(str/join ", " (remove nil? [% "gzip, deflate"])))))

(defn- decompression-response
  [req resp]
  (if (false? (opt req :decompress-body))
    resp
    (decompress-body resp)))

(defn wrap-decompression
  "Middleware handling automatic decompression of responses from web servers. If
  :decompress-body is set to false, does not automatically set `Accept-Encoding`
  header or decompress body."
  [client]
  (fn
    ([req]
     (decompression-response req (client (decompression-request req))))
    ([req respond raise]
     (client (decompression-request req)
             #(respond (decompression-response req %))
             raise))))

(def default-middleware
  "The default list of middleware hato uses for wrapping requests."
  [wrap-request-timing
   ;wrap-header-map

   wrap-query-params
   wrap-basic-auth
   wrap-oauth
   wrap-user-info
   wrap-url

   wrap-decompression
   ;wrap-input-coercion ; handled by hato.client
   ;; put this before output-coercion, so additional charset
   ;; headers can be used if desired
   ;wrap-additional-header-parsing TODO
   wrap-output-coercion
   wrap-exceptions
   wrap-accept
   wrap-accept-encoding
   wrap-content-type
   wrap-form-params
   ;wrap-nested-params TODO
   ;wrap-flatten-nested-params TODO
   wrap-method])

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [request]
  (reduce (fn [request middleware]
            (middleware request))
          request
          default-middleware))