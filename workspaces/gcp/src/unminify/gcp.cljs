(ns unminify.gcp
  "GCP specific unminify and write to error reporting."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [goog.object]
            ;; [cognitect.transit :as transit]
            ["@google-cloud/storage" :as storage]
            ["@google-cloud/error-reporting" :as er]
            ["express$default" :as express]
            ["cors$default" :as cors]
            [unminify.core :as unminify]
            [promesa.core :as p]
            ["process" :as process]
            ["path" :as path]
            ["os" :as os]
            ["fs" :as fs]))

(defonce ^:dynamic *state* {})

(defn- download!
  "Downloads a `filename` from `bucket` and writes it to `destination`.
  Returns a promise."
  [{:keys [bucket file destination]}]
  (-> (storage/Storage.)
      (.bucket bucket)
      (.file file)
      (.download #js {:destination destination})))

(defn- gen-destination
  "Returns the destination file to save the source map to"
  []
  (let [dir (-> (os/tmpdir)
                (path/join "unminify")
                (fs/mkdtempSync))]
    {:dir dir
     :file (path/join dir "source.js.map")}))

(defn- remote-filename
  "Returns a string with the version embedded
  into the filename template. the filename should
  be some path like string with '${version}' in
  the string ie 'source-maps/v${version}.js'"
  [filename version]
  (let [filename (if (str/starts-with? filename "/")
                   (subs filename 1)
                   filename)]
    (str/replace filename "${version}" version)))


(defn- send-to-error-reporting!
  "Repots an error via error reporting."
  [stacktrace user userAgent service version url]
  (let [errors (er/ErrorReporting. #js {:reportMode "always"})
        error-msg (doto (.event errors)
                    (.setMessage stacktrace)
                    (.setUser user)
                    (.setUserAgent userAgent)
                    (.setServiceContext service version)
                    (.setUrl url))]
    (.report errors error-msg)))


(defn report-unminified!
  "Unminifies the `stacktrace` and reports it to error reporting."
  [{:keys [serviceContext context message filename bucket]}]
  (prn filename)
  (prn bucket)
  (let [
        {:keys [service version]} serviceContext
        {:keys [user httpRequest]} context
        {:keys [userAgent url]} httpRequest

        remote-file (remote-filename filename version)
        {:keys [dir file]} (gen-destination)]

    (p/let [_ (download! {:bucket bucket
                          :file remote-file
                          :destination file})
            stacktrace (unminify/unminify {:stacktrace message
                                           :source-map file})]
      (send-to-error-reporting!  stacktrace user userAgent service version url)
      (fs/rm dir #js {:force true :recursive true} identity))))


;;----------------------------------------------------------------------
;; -- HTTP API --
;;----------------------------------------------------------------------
(defn env-variables
  []
  (let [e (.-env process)]
    {:port (js/parseInt (goog.object/get e "PORT" "8080"))
     :error-endpoint (goog.object/get e "ERROR_ENDPOINT" "/error")
     :ping-endpoint (goog.object/get e "PING_ENDPOINT")
     :bucket (goog.object/get e "BUCKET")
     :filename (goog.object/get e "FILENAME")
     :cors-origins (goog.object/get e "CORS_ORIGINS")
     :cors-exposed-headers (goog.object/get e "CORS_EXPOSED_HEADERS")
     :cors-allowed-headers (goog.object/get e "CORS_ALLOWED_HEADERS")
     :cors-credentials? (= "true" (goog.object/get e "CORS_CREDENTIALS" "false"))
     :cors-max-age (js/parseInt (goog.object/get e "CORS_MAX_AGE" "600"))
     :debug? (= "true" (goog.object/get e "DEBUG" "false"))}))


(defn body-reader
  [req _res next]
  (let [buffers #js []]
    (.on req "data" #(.push buffers %))
    (.on req "end" (fn []
                     (->> buffers
                          js/Buffer.concat
                          str
                          (goog.object/set req "body"))
                     (next)))))


;; (defn- parse-transit
;;   [s]
;;   (transit/read (transit/reader :json) s))

(defn strip-byte-order-mark
  [s]
  (if (= \ufeff (first s))
    (subs s 1)
    s))

(defn- parse-json
  [s]
  (-> s js/JSON.parse (js->clj :keywordize-keys true)))

(defn body-parser
  [req _res next]
  (assert (clojure.string/starts-with? 
            (.header req "content-type")
            "application/json"))
  (let [
        ct (.header req "content-type")
        body (some-> (.-body req) strip-byte-order-mark)
        parsed (parse-json body)]
    (goog.object/set req "body" parsed)
    (next)))

(defn parse-cors-orgins
  [s]
  (if (str/blank? s)
    "*"
    (->> (str/split s ",")
         (mapv re-pattern)
         (into-array))))

(defn start-server!
  []
  (println "Starting server.")
  (let [{:keys [port
                ping-endpoint
                error-endpoint
                cors-origins
                cors-exposed-headers
                cors-allowed-headers
                cors-credentials?
                cors-max-age]} *state*
        app (express)
        cors-mw (-> {:origin (parse-cors-orgins cors-origins)
                     :methods ["GET" "POST"]
                     :allowedHeaders cors-allowed-headers
                     :exposedHeaders cors-exposed-headers
                     :credentials cors-credentials?
                     :maxAge cors-max-age
                     :optionsSuccessStatus 204}
                    clj->js
                    cors)]

    (doto app
      (.disable "x-powered-by")
      (.use body-reader)
      (.use body-parser)
      (.use cors-mw)
      (.options "*" cors-mw))

    (when ping-endpoint
      (.get app ping-endpoint
            (fn [_req res]
              (doto res
                (.json #js {"ping" "pong"})
                (.status 200)))))
    (.post app error-endpoint
           (fn [req res]
             (-> (.-body req)
                 (assoc :headers (.-headers req)
                        :remote-ip (-> req .-socket .-remoteAddress))
                 (merge (select-keys *state* [:bucket :filename :debug?]))
                 (report-unminified!)
                 (.then (fn [_]
                          (println 204 error-endpoint)
                          (doto res
                            (.status 204)
                            (.end))))
                 (.catch (fn [err]
                           (println 500 error-endpoint err)
                           (doto res
                             (.status 500)
                             (.end)))))))
    (.listen app port (fn [] (println "Server listening on port: " port)))))

(defn main
  []
  (.on js/process "unhandledRejection" (fn [reason _promise] (println "ERROR: unhandledRejection " reason)))
  (alter-var-root #'*state* (constantly (env-variables)))
  (println "state: " *state*)
  (assert (string? (:bucket *state*)) "BUCKET env not found")
  (assert (string? (:filename *state*)) "FILENAME env not found")
  (start-server!))

(main)
