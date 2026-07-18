(ns kotobase.protocols.router
  "Host-based dispatch for the kotobase protocol surfaces
  (ADR-2607171700):

    s3.<apex>       → kotobase.protocols.s3
    ipfs.<apex>     → kotobase.protocols.ipfs
    atproto.<apex>  → kotobase.protocols.atproto
    git.<apex>      → kotobase.protocols.git

  plus a single-origin fallback for deploys that only own one
  hostname: /ipfs/* and /xrpc/* dispatch by their protocol-inherent
  prefixes, /s3/* and /git/* by stripped mount prefixes.

  The apex defaults to \"kotobase.net\" but is injectable — the same
  router serves a self-hosted mesh peer on any domain. This module
  plans routes; actually answering on *.kotobase.net DNS requires the
  deploy-shell ADR + credentials described in ADR-2607171700
  (:not-decided), and ipfs.kotobase.net stays owned by
  gftdcojp/net-kotobase-ipfs (ADR-2607072000)."
  (:require [clojure.string :as str]
            [kotobase.protocols.atproto :as atproto]
            [kotobase.protocols.git :as git]
            [kotobase.protocols.http :as http]
            [kotobase.protocols.ipfs :as ipfs]
            [kotobase.protocols.s3 :as s3]))

(def surfaces
  {"s3" s3/handle
   "ipfs" ipfs/handle
   "atproto" atproto/handle
   "git" git/handle})

(defn surface-of
  "\"s3.kotobase.net\" + apex \"kotobase.net\" → \"s3\"; nil when host
  is not a single label in front of the apex."
  [host apex]
  (when (and host (str/ends-with? host (str "." apex)))
    (let [label (subs host 0 (- (count host) (inc (count apex))))]
      (when (and (seq label) (not (str/includes? label ".")))
        label))))

(defn- strip-prefix [req prefix]
  (assoc req :path (subs (:path req) (count prefix))))

(defn handle
  "Route `req` to its protocol surface. ctx: {:store ... :now ...
  :apex \"kotobase.net\"}."
  [{:keys [apex] :or {apex "kotobase.net"} :as ctx} req]
  (let [path (or (:path req) "")
        surface (surface-of (:host req) apex)]
    (if (and (= :get (:method req)) (= "/health" path) (contains? surfaces surface))
      (http/response 200
                     {"content-type" "application/edn; charset=utf-8"
                      "cache-control" "no-store"}
                     (pr-str {:ok true :service (keyword (str "kotobase.protocols/" surface))
                              :surface (keyword surface) :apex apex}))
      (if-let [handler (get surfaces surface)]
        (handler ctx req)
      (cond
        (str/starts-with? path "/ipfs/") (ipfs/handle ctx req)
        (str/starts-with? path "/ipns/") (ipfs/handle ctx req)
        (str/starts-with? path "/xrpc/") (atproto/handle ctx req)
        (str/starts-with? path "/s3/")   (s3/handle ctx (strip-prefix req "/s3"))
        (str/starts-with? path "/git/")  (git/handle ctx (strip-prefix req "/git"))
        :else (http/not-found "no protocol surface for this host/path"))))))
