(ns kotobase.protocols.git
  "git.kotobase.net — git dumb-HTTP READ surface over kotobase
  (ADR-2607171700).

  DIRECTION GUARD: ADR-2607072300 (actor public data) stands — a git
  journal in the actor's own repo stays the primary source and
  kotobase a derived index. This namespace is the other, non-competing
  concern: kotobase acting as an object/ref STORE that can serve git's
  dumb HTTP protocol for repos whose objects were pushed into it (via
  the library fns below, gated upstream by kotoba-rad signing —
  identity/authorization only, per ADR-2607072200).

  Mapping:
    refs    → host collection [:kotobase.git/refs <repo>]   refname → sha
    objects → host collection [:kotobase.git/objects <repo>] sha → bytes
    HEAD    → refs doc under key \"HEAD\", value \"ref: refs/heads/<b>\"

  Dumb-HTTP endpoints (read-only; git clients can fetch over this):
    GET /{org}/{repo}/info/refs               \"<sha>\\t<refname>\\n\" lines
    GET /{org}/{repo}/HEAD                    symref line
    GET /{org}/{repo}/objects/{2-hex}/{38-hex} loose object bytes

  v0.1 stores loose-object bytes as opaque strings supplied by the
  writer and does NOT verify sha1(bytes) == key; verification and the
  smart protocol (upload-pack) are declared follow-ups in the ADR."
  (:require [clojure.string :as str]
            [kotobase.protocols.http :as http]
            [kotobase.protocols.store :as st]))

(defn refs-coll [repo] [:kotobase.git/refs repo])
(defn objects-coll [repo] [:kotobase.git/objects repo])

;; ------------------------------------------------------------ write (library)

(defn put-object!
  "Store loose-object `bytes` for `sha` (40 hex chars) in `repo`."
  [store repo sha bytes]
  {:pre [(re-matches #"[0-9a-f]{40}" sha)]}
  (st/put store (objects-coll repo) sha bytes)
  (st/append store :kotobase.protocols/audit
              {:surface :git :op :put-object :repo repo :sha sha})
  sha)

(defn set-ref!
  "Point `refname` (e.g. \"refs/heads/main\") at `sha` in `repo`."
  [store repo refname sha]
  (st/put store (refs-coll repo) refname sha)
  (st/append store :kotobase.protocols/audit
              {:surface :git :op :set-ref :repo repo :ref refname :sha sha})
  sha)

(defn set-head!
  "Make HEAD a symref to `branch-ref` (e.g. \"refs/heads/main\")."
  [store repo branch-ref]
  (st/put store (refs-coll repo) "HEAD" (str "ref: " branch-ref)))

;; -------------------------------------------------------------- read (HTTP)

(defn- info-refs [store repo]
  (let [refnames (->> (st/list-keys store (refs-coll repo))
                      (remove #(= "HEAD" %))
                      sort)]
    (if (empty? refnames)
      (http/not-found (str "repository not found: " repo))
      (http/text 200
                 (apply str
                        (for [r refnames]
                          (str (st/get store (refs-coll repo) r) "\t" r "\n")))))))

(defn- head-line [store repo]
  (if-let [h (st/get store (refs-coll repo) "HEAD")]
    (http/text 200 (str h "\n"))
    (http/not-found (str "repository not found: " repo))))

(defn- loose-object [store repo d2 d38]
  (let [sha (str d2 d38)]
    (if-let [bytes (st/get store (objects-coll repo) sha)]
      (http/response 200 {"content-type" "application/x-git-loose-object"} bytes)
      (http/not-found (str "object not found: " sha)))))

(defn handle
  "git dumb-HTTP handler (GET only). Repo path = every segment before
  the protocol suffix, joined with '/', so org/repo nesting works."
  [{:keys [store]} req]
  (let [segs (http/segments (:path req))
        n (count segs)]
    (if-not (= :get (:method req))
      (http/method-not-allowed)
      (cond
        (and (>= n 3) (= ["info" "refs"] (subvec segs (- n 2))))
        (info-refs store (str/join "/" (subvec segs 0 (- n 2))))

        (and (>= n 2) (= "HEAD" (peek segs)))
        (head-line store (str/join "/" (subvec segs 0 (dec n))))

        (and (>= n 4) (= "objects" (segs (- n 3)))
             (re-matches #"[0-9a-f]{2}" (segs (- n 2)))
             (re-matches #"[0-9a-f]{38}" (segs (- n 1))))
        (loose-object store
                      (str/join "/" (subvec segs 0 (- n 3)))
                      (segs (- n 2)) (segs (- n 1)))

        :else (http/not-found)))))
