(ns kotobase.protocols.ipfs
  "ipfs gateway projection over the shared block space
  (ADR-2607171700, KRP §3.3 Content).

  GET /ipfs/{cid} serves the block stored by kotobase.protocols.blocks.
  The HTTP surface is READ-ONLY by design — the same blast-radius
  principle that split gftdcojp/net-kotobase-ipfs out of net-kotobase
  (ADR-2607072000): a public gateway only ever needs read access.
  Writes go through the library fn `blocks/put-block!` inside an
  authenticated deploy shell.

  AUTHORITY NOTE: the public `ipfs.kotobase.net` route is owned by
  gftdcojp/net-kotobase-ipfs (ADR-2607072000). This handler is the
  portable, self-hosted/local-first equivalent (fleet peers, browser
  workers, tests); serving it on that hostname would need that repo to
  adopt it — non-authoritative until then.

  Content-Type is sanitized through an allowlist (same convention as
  net-kotobase's archive-serving path): anything else degrades to
  application/octet-stream so a stored block can never become an
  executable HTML/SVG origin by accident."
  (:require [kotobase.protocols.blocks :as blocks]
            [kotobase.protocols.http :as http]))

(def content-type-allowlist
  #{"text/plain" "text/plain; charset=utf-8"
    "application/json" "application/cbor"
    "application/vnd.ipld.raw" "application/vnd.ipld.car"
    "application/vnd.ipld.dag-cbor" "application/vnd.ipld.dag-json"
    "application/octet-stream"
    "image/png" "image/jpeg" "image/gif" "image/webp"
    "video/mp4" "audio/mpeg" "model/gltf-binary"})

(defn sanitize-content-type [ct]
  (if (contains? content-type-allowlist ct) ct "application/octet-stream"))

(defn handle
  "IPFS gateway handler. Serves GET/HEAD /ipfs/{cid} from ctx :store."
  [{:keys [store]} req]
  (let [segs (http/segments (:path req))]
    (cond
      (not (#{:get :head} (:method req)))
      (http/method-not-allowed)

      (and (= "ipfs" (first segs)) (= 2 (count segs)))
      (let [cid (second segs)]
        (if-let [b (blocks/get-block store cid)]
          (cond-> (http/response 200
                                 {"content-type" (sanitize-content-type (:content-type b))
                                  "etag" (str "\"" cid "\"")
                                  "x-ipfs-path" (str "/ipfs/" cid)
                                  "cache-control" "public, max-age=29030400, immutable"}
                                 (when (= :get (:method req)) (:bytes b)))
            (= "base64" (:encoding b)) (assoc :body-encoding :base64))
          (http/not-found (str "block not found: " cid))))

      (= "ipns" (first segs))
      (http/text 501 "ipns not implemented (mutable heads resolve via KRP signed heads)")

      :else (http/not-found))))
