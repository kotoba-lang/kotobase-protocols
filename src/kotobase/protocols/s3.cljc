(ns kotobase.protocols.s3
  "s3.kotobase.net — an S3-compatible object surface projected onto the
  protocol projection host (ADR-2607171700, KRP §3.4 Location).

  Mapping:
    bucket        → host collection [:kotobase.s3/objects <bucket>]
    object key    → doc key (may contain '/')
    object value  → {:bytes :content-type :etag :last-modified}
    every write   → audit event on :kotobase.protocols/audit

  Implemented subset (v0.1):
    PUT    /{bucket}/{key}          store object
    GET    /{bucket}/{key}          fetch object
    HEAD   /{bucket}/{key}          metadata only
    DELETE /{bucket}/{key}          delete (idempotent 204)
    GET    /{bucket}?list-type=2    ListObjectsV2 (Key/ETag/Size, prefix=)

  Deliberately out of scope here: SigV4 authentication (the deploy
  shell owns auth, exactly as CACAO verification lives in the
  kotobase.net Worker, not in the engine), multipart upload,
  versioning. ETags are kotobase.protocols.hash fingerprints — a
  checksum, NOT a CID (KRP §3.3)."
  (:require [clojure.string :as str]
            [kotobase.protocols.hash :as hash]
            [kotobase.protocols.http :as http]
            [kotobase.protocols.store :as st]))

(defn objects-coll [bucket] [:kotobase.s3/objects bucket])

(defn- audit! [store op bucket k]
  (st/append store :kotobase.protocols/audit
              {:surface :s3 :op op :bucket bucket :key k}))

(defn- xml-response [status body]
  (http/response status {"content-type" "application/xml"}
                 (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" body)))

(defn- error-xml [status code msg]
  (xml-response status
                (str "<Error><Code>" code "</Code><Message>"
                     (http/xml-escape msg) "</Message></Error>")))

(defn- list-objects [store bucket prefix]
  ;; DELETE writes a nil tombstone (the legacy host has no remove op), so
  ;; listing keeps only keys whose doc is still present.
  (let [entries (->> (st/list-keys store (objects-coll bucket))
                     (filter #(str/starts-with? % (or prefix "")))
                     sort
                     (keep (fn [k]
                             (when-let [o (st/get store (objects-coll bucket) k)]
                               [k o]))))]
    (xml-response
     200
     (str "<ListBucketResult>"
          "<Name>" (http/xml-escape bucket) "</Name>"
          "<Prefix>" (http/xml-escape (or prefix "")) "</Prefix>"
          "<KeyCount>" (count entries) "</KeyCount>"
          (apply str
                 (for [[k o] entries]
                   (str "<Contents>"
                        "<Key>" (http/xml-escape k) "</Key>"
                        "<ETag>&quot;" (:etag o) "&quot;</ETag>"
                        "<Size>" (count (:bytes o)) "</Size>"
                        "</Contents>")))
          "</ListBucketResult>"))))

(defn- object-headers [o]
  (cond-> {"content-type" (:content-type o)
           "etag" (str "\"" (:etag o) "\"")
           "content-length" (str (count (:bytes o)))}
    (:last-modified o) (assoc "last-modified" (:last-modified o))))

(defn handle
  "S3 surface handler. `ctx` is {:store operation-map, :now optional ISO string}."
  [{:keys [store now]} req]
  (let [[bucket & ks] (http/segments (:path req))
        k (when (seq ks) (str/join "/" ks))]
    (cond
      (nil? bucket)
      (error-xml 400 "InvalidRequest" "bucket missing")

      ;; bucket-level: ListObjectsV2
      (nil? k)
      (if (= :get (:method req))
        (list-objects store bucket (http/query-param req "prefix"))
        (error-xml 405 "MethodNotAllowed" "unsupported bucket operation"))

      :else
      (case (:method req)
        :put
        (let [body (or (:body req) "")
              o {:bytes body
                 :content-type (or (http/header req "content-type")
                                   "application/octet-stream")
                 :etag (hash/fingerprint body)
                 :last-modified now}]
          (st/put store (objects-coll bucket) k o)
          (audit! store :put-object bucket k)
          (http/response 200 {"etag" (str "\"" (:etag o) "\"")} nil))

        (:get :head)
        (if-let [o (st/get store (objects-coll bucket) k)]
          (http/response 200 (object-headers o)
                         (when (= :get (:method req)) (:bytes o)))
          (error-xml 404 "NoSuchKey" (str "no such key: " k)))

        :delete
        (do (st/put store (objects-coll bucket) k nil)
            (audit! store :delete-object bucket k)
            (http/response 204 {} nil))

        (error-xml 405 "MethodNotAllowed" "unsupported object operation")))))
