# kotobase-protocols

[![CI](https://github.com/kotoba-lang/kotobase-protocols/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kotobase-protocols/actions/workflows/ci.yml)

**Storage / protocol surfaces projected onto [kotobase](https://github.com/kotoba-lang/kotobase)**
— one datom/document/block plane, five wire protocols
(ADR-2607171700 + ADR-2607172210 in `com-junkawasaki/root`; addressing
contract: the Kotoba Resource Protocol,
`90-docs/protocols/kotoba-resource-protocol.edn`).

Every surface is a **pure cljc handler** over an injected plain operation map.
`kotobase.protocols.store` supplies the source-local deterministic memory host;
there is no kotobase/IStore dependency. No handler performs network I/O or host JSON,
no crypto dependencies live here; deploy shells (Cloudflare Worker, browser
worker, fleet peer) own transport and authentication, exactly as
`kotobase-cljc-worker` / `kotobase-browser-worker` rebind the kotobase engine.

| Surface | Namespace | Protocol subset (v0.1) | KRP identity |
|---|---|---|---|
| `s3.<apex>` | `kotobase.protocols.s3` | PUT/GET/HEAD/DELETE object, ListObjectsV2 | §3.4 Location |
| `ipfs.<apex>` | `kotobase.protocols.ipfs` | GET/HEAD `/ipfs/{cid}` (read-only gateway) | §3.3 Content |
| `pinning.<apex>` | `kotobase.protocols.ipfs-pinning` | [IPFS Pinning Service API](https://ipfs.github.io/pinning-services-api-spec/): `POST/GET /pins`, `GET/DELETE /pins/{requestid}` | §3.3 Content |
| `atproto.<apex>` | `kotobase.protocols.atproto` | XRPC `com.atproto.repo.{get,put,delete,list}Record*`, `sync.getBlob` | §3.2 Record |
| `git.<apex>` | `kotobase.protocols.git` | dumb-HTTP fetch plus signed `git-remote-kotobase` push | §3.1/§3.4 |

`kotobase.protocols.router` dispatches by subdomain (`s3.kotobase.net`, …)
with a single-origin path-prefix fallback (`/s3/*`, `/xrpc/*`, `/ipfs/*`,
`/pins*`, `/git/*`), apex injectable for self-hosted peers.

### IPFS Pinning Service API (`pinning.<apex>` / `ipfs-pinning.cljc`)

Extends the same shared block space `ipfs.cljc` already serves read-only
(ADR-2607172210 — this stays inside `kotobase-protocols` rather than
becoming a 4th IPFS-adjacent repo, since three homes for this exact block
space already exist: this repo's `ipfs.cljc` gateway, the general
`kotoba-lang/ipfs` implementation library, and the public product surface
`gftdcojp/net-kotobase-ipfs`). Mounted on its own `pinning` subdomain rather
than folded into `ipfs`'s — see the routing note at the top of `router.cljc`
for why: `ipfs.cljc` is documented READ-ONLY BY DESIGN, and a write-capable
API deserves its own host/auth boundary rather than blurring that
invariant.

**Honest limitation, read before using**: v0.1 has **no background pinning
daemon**. `POST /pins` decides the outcome synchronously, at request time,
by checking whether the block is already present locally
(`blocks/get-block`):

- block present  → `status: "pinned"` immediately.
- block missing  → `status: "failed"` immediately, with an `info.reason`
  explaining why — **not** `"queued"`. The `origins` field is accepted and
  stored (API-shape compatible) but never dialed; there is no peer fetch,
  no retry, no background worker that could ever complete a queued
  request. If you need a pin to succeed, `blocks/put-block!` the CID first
  (inside an authenticated deploy shell, same as `ipfs.cljc`'s own write
  path), then `POST /pins`.

`DELETE /pins/{requestid}` removes the pin *request* only — the underlying
block is untouched, since multiple pin requests may reference the same
block (same content-addressed dedup principle as `blocks.cljc`).

Every recognized protocol subdomain also answers `GET /health` with a
no-store EDN status document. This is the deploy-shell readiness boundary;
it proves routing and handler availability, not backing-store durability.

```clojure
(require '[kotobase.protocols.store :as local]
         '[kotobase.protocols.router :as router])

(def ctx {:store (local/memory-store) :apex "kotobase.net"})

(router/handle ctx {:method :put :host "s3.kotobase.net"
                    :path "/media/hello.txt" :body "hello"})
;; => {:status 200 :headers {"etag" "\"…\""} :body nil}

(router/handle ctx {:method :get :host "s3.kotobase.net"
                    :path "/media/hello.txt"})
;; => {:status 200 … :body "hello"}
```

## Scope guards (read before extending)

- **`ipfs.kotobase.net` (the public route) is owned by
  `gftdcojp/net-kotobase-ipfs`** (ADR-2607072000). This repo's gateway is the
  portable/self-hosted equivalent; it is non-authoritative for that hostname.
- **The atproto surface is the tenant data-plane, not a social PDS.** The
  aozora.app consolidation (ADR-2607062200) is untouched: no `app.bsky.*`,
  no firehose, no handle resolution.
- **git-journal primacy stands** (ADR-2607072300): an actor repo's git
  history remains the primary source of its public data; this repo makes
  kotobase *servable over git's protocol*, it does not invert that ADR.
- **ETags/fingerprints are not CIDs** (KRP §3.3). Real content addressing
  (blake3/CIDv1 via kotoba multiformats) is a declared follow-up; blocks are
  stored under caller-computed CIDs.

## Develop / test

First-class runtime is **nbb/cljs** (repo-wide runtime priority):

```bash
git clone https://github.com/kotoba-lang/kotobase .deps/kotobase
nbb --classpath "src:test:.deps/kotobase/src" bin/run_tests.cljs
```

The `:test` alias in `deps.edn` is the JVM **compat** suite only.

## Cloudflare deploy shell

`worker/git-worker.mjs` is the host-only transport/persistence adapter for
`git.kotobase.net`. D1 performs compare-and-set ref transactions and R2 stores
verified Git loose objects by content identity. Before D1 adopts a ref, the
helper commits its signed-ref datoms to the actor's CACAO-authorized Kotobase
graph and the Worker independently reads them back with the same short-lived
capability. D1 is the compare-and-set serving mirror, not the only authority.
`bin/git-remote-kotobase.mjs`
provides the Git remote-helper protocol, so ordinary `git push` uploads the
reachable object graph and advances a ref. Every write has a replay-protected
Ed25519 request signature; every ref update additionally carries the exact
RID/ref/commit/timestamp signed-ref tuple defined by `nekko.sigref`. Request
signatures bind the sigref, graph CID, Kotobase commit CID and a CACAO digest;
the capability itself is never persisted in audit records. Server-side
ancestry traversal enforces bonsai fast-forward policy across multi-commit
pushes. Force and delete updates are rejected by both the helper and authority.

Set `KOTOBASE_GIT_PRIVATE_KEY` to an Ed25519 PKCS8 PEM (literal newlines or
escaped `\\n`) or base64 DER and make this package's `bin/` available on PATH:

```bash
git remote add origin kotobase::https://git.kotobase.net/org/repo
git push origin main:refs/heads/main
```

Deployments must use the superproject resource guard.

## License

Apache-2.0
