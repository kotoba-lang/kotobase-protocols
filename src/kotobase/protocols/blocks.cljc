(ns kotobase.protocols.blocks
  "The shared content-addressed block space every surface reads from.

  One IStore collection, `:kotobase.protocols/blocks`, keyed by CID
  string, holding {:bytes <string> :content-type <string>}. The IPFS
  gateway serves it, AT-Protocol sync.getBlob serves it, and S3 object
  bodies may be promoted into it later — one block space, four
  projections (ADR-2607171700, KRP §3.3 Content identity).

  v0.1 stores bodies as strings and trusts the caller-supplied CID
  (computed upstream by kotobase-client / kotoba multiformats). This
  library never mints CIDs itself — see kotobase.protocols.hash for
  why its fingerprints are not CIDs."
  (:require [kotobase.store :as st]))

(def coll :kotobase.protocols/blocks)

(defn put-block!
  "Store `bytes` under caller-computed `cid`. Idempotent (content-addressed:
  same cid ⇒ same bytes). Returns the block map."
  [store cid {:keys [bytes content-type] :as block}]
  {:pre [(string? cid) (string? bytes)]}
  (st/-put store coll cid
           {:bytes bytes
            :content-type (or content-type "application/octet-stream")})
  (st/-append store :kotobase.protocols/audit
              {:surface :blocks :op :put :cid cid :size (count bytes)})
  block)

(defn get-block [store cid] (st/-get store coll cid))

(defn list-cids [store] (st/-list store coll))
