var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker/git-worker.mjs
var json = /* @__PURE__ */ __name((value, status = 200, headers = {}) => new Response(JSON.stringify(value), {
  status,
  headers: { "content-type": "application/json; charset=utf-8", ...headers }
}), "json");
var text = /* @__PURE__ */ __name((value, status = 200, headers = {}) => new Response(value, {
  status,
  headers: { "content-type": "text/plain; charset=utf-8", ...headers }
}), "text");
var validRepo = /* @__PURE__ */ __name((s) => /^[A-Za-z0-9._-]+\/[A-Za-z0-9._-]+$/.test(s), "validRepo");
var validSha = /* @__PURE__ */ __name((s) => /^[0-9a-f]{40}$/.test(s), "validSha");
var validRef = /* @__PURE__ */ __name((s) => /^refs\/(heads|tags)\/[A-Za-z0-9._/-]+$/.test(s) && !s.includes("..") && !s.endsWith("/"), "validRef");
var validDid = /* @__PURE__ */ __name((s) => /^did:key:z[1-9A-HJ-NP-Za-km-z]{40,}$/.test(s), "validDid");
var enc = new TextEncoder();
var hex = /* @__PURE__ */ __name((bytes) => [...new Uint8Array(bytes)].map((x) => x.toString(16).padStart(2, "0")).join(""), "hex");
var b64urlBytes = /* @__PURE__ */ __name((s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=")), (c) => c.charCodeAt(0)), "b64urlBytes");
var unhex = /* @__PURE__ */ __name((s) => Uint8Array.from(s.match(/../g) || [], (x) => Number.parseInt(x, 16)), "unhex");
function decodeCbor(bytes) {
  let offset = 0;
  const readLength = /* @__PURE__ */ __name((additional) => {
    if (additional < 24) return additional;
    if (additional === 24) return bytes[offset++];
    if (additional === 25) return bytes[offset++] << 8 | bytes[offset++];
    if (additional === 26) return bytes[offset++] * 16777216 + (bytes[offset++] << 16) + (bytes[offset++] << 8) + bytes[offset++];
    throw new Error("unsupported CBOR length");
  }, "readLength");
  const read = /* @__PURE__ */ __name(() => {
    if (offset >= bytes.length) throw new Error("truncated CBOR");
    const first = bytes[offset++];
    const major = first >> 5;
    const length = readLength(first & 31);
    if (major === 0) return length;
    if (major === 2) {
      const value2 = bytes.slice(offset, offset + length);
      offset += length;
      return value2;
    }
    if (major === 3) {
      const value2 = new TextDecoder().decode(bytes.slice(offset, offset + length));
      offset += length;
      return value2;
    }
    if (major === 4) return Array.from({ length }, read);
    if (major === 5) {
      const value2 = {};
      for (let i = 0; i < length; i += 1) value2[read()] = read();
      return value2;
    }
    if (major === 7 && first === 244) return false;
    if (major === 7 && first === 245) return true;
    if (major === 7 && first === 246) return null;
    throw new Error("unsupported CBOR type");
  }, "read");
  const value = read();
  if (offset !== bytes.length) throw new Error("trailing CBOR bytes");
  return value;
}
__name(decodeCbor, "decodeCbor");
function siweMessage(payload) {
  const lines = [
    `${payload.domain} wants you to sign in with your Ethereum account:`,
    String(payload.iss || "").split(":").at(-1),
    "",
    `URI: ${payload.aud}`,
    `Version: ${payload.version}`,
    "Chain ID: 1",
    `Nonce: ${payload.nonce}`,
    `Issued At: ${payload.iat}`
  ];
  if (payload.exp) lines.push(`Expiration Time: ${payload.exp}`);
  if (payload.resources?.length) lines.push("Resources:", ...payload.resources.map((r) => `- ${r}`));
  return lines.join("\n");
}
__name(siweMessage, "siweMessage");
var covers = /* @__PURE__ */ __name((parent, child) => typeof parent === "string" && typeof child === "string" && (parent.endsWith("*") ? child.startsWith(parent.slice(0, -1)) : parent === child), "covers");
async function verifyCacao(value, now = Date.now()) {
  try {
    if (typeof value !== "string" || value.length > 16384) return null;
    const wire = decodeCbor(Uint8Array.from(atob(value), (c) => c.charCodeAt(0)));
    const payload = wire.p;
    const signature = wire.s?.s;
    if (!payload || !signature || !validDid(payload.iss) || !Array.isArray(payload.resources) || payload.resources.some((r) => typeof r !== "string" || r.includes("\n")) || !payload.nonce) return null;
    const iat = Date.parse(payload.iat);
    const exp = Date.parse(payload.exp);
    if (!Number.isFinite(iat) || iat > now + 3e5 || !Number.isFinite(exp) || now >= exp) return null;
    const pub = b58decode(payload.iss.slice("did:key:z".length));
    if (pub.length !== 34 || pub[0] !== 237 || pub[1] !== 1) return null;
    const key = await crypto.subtle.importKey("raw", pub.slice(2), { name: "Ed25519" }, false, ["verify"]);
    const sig = b64urlBytes(signature);
    if (sig.length !== 64 || !await crypto.subtle.verify("Ed25519", key, sig, enc.encode(siweMessage(payload)))) return null;
    return payload;
  } catch (_) {
    return null;
  }
}
__name(verifyCacao, "verifyCacao");
function b58decode(value) {
  let n = 0n;
  for (const ch of value) {
    const i = b58alphabet.indexOf(ch);
    if (i < 0) throw new Error("invalid base58");
    n = n * 58n + BigInt(i);
  }
  const out = [];
  while (n > 0n) {
    out.unshift(Number(n & 255n));
    n >>= 8n;
  }
  for (const ch of value) {
    if (ch !== "1") break;
    out.unshift(0);
  }
  return Uint8Array.from(out);
}
__name(b58decode, "b58decode");
async function verifyCacaoChain(chain, ownerDid, signerDid, wanted, now = Date.now()) {
  if (!Array.isArray(chain) || chain.length < 1 || chain.length > 8) return null;
  const payloads = [];
  for (const link of chain) {
    const payload = await verifyCacao(link, now);
    if (!payload) return null;
    payloads.push(payload);
  }
  if (payloads[0].iss !== ownerDid || payloads.at(-1).aud !== signerDid) return null;
  for (let i = 1; i < payloads.length; i += 1) {
    const parent = payloads[i - 1];
    const child = payloads[i];
    if (child.iss !== parent.aud || Date.parse(child.exp) > Date.parse(parent.exp) || child.resources.some((resource) => !parent.resources.some((grant) => covers(grant, resource)))) return null;
  }
  if (!payloads.at(-1).resources.some((grant) => covers(grant, wanted))) return null;
  return payloads;
}
__name(verifyCacaoChain, "verifyCacaoChain");
var b58alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
function base58(bytes) {
  let n = 0n;
  for (const b of bytes) n = (n << 8n) + BigInt(b);
  let out = "";
  while (n > 0n) {
    out = b58alphabet[Number(n % 58n)] + out;
    n /= 58n;
  }
  for (const b of bytes) {
    if (b !== 0) break;
    out = `1${out}`;
  }
  return out || "1";
}
__name(base58, "base58");
var didFromPublicKey = /* @__PURE__ */ __name((pk) => `did:key:z${base58(new Uint8Array([237, 1, ...pk]))}`, "didFromPublicKey");
function cborText(value) {
  const bytes = enc.encode(value);
  if (bytes.length < 24) return new Uint8Array([96 + bytes.length, ...bytes]);
  if (bytes.length < 256) return new Uint8Array([120, bytes.length, ...bytes]);
  return new Uint8Array([121, bytes.length >> 8, bytes.length & 255, ...bytes]);
}
__name(cborText, "cborText");
function sigrefPayload(sigref) {
  const entries = ["rid", "ref", "commit", "ts"].map((key) => [cborText(key), cborText(sigref[key])]);
  entries.sort((a, b) => {
    if (a[0].length !== b[0].length) return a[0].length - b[0].length;
    for (let i = 0; i < a[0].length; i += 1) if (a[0][i] !== b[0][i]) return a[0][i] - b[0][i];
    return 0;
  });
  const size = entries.reduce((n, [k, v]) => n + k.length + v.length, 1);
  const out = new Uint8Array(size);
  out[0] = 164;
  let offset = 1;
  for (const [k, v] of entries) {
    out.set(k, offset);
    offset += k.length;
    out.set(v, offset);
    offset += v.length;
  }
  return out;
}
__name(sigrefPayload, "sigrefPayload");
var digestHex = /* @__PURE__ */ __name(async (bytes, algorithm = "SHA-256") => hex(await crypto.subtle.digest(algorithm, bytes)), "digestHex");
function base32(bytes) {
  const alphabet = "abcdefghijklmnopqrstuvwxyz234567";
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of bytes) {
    value = value << 8 | byte;
    bits += 8;
    while (bits >= 5) {
      out += alphabet[value >>> bits - 5 & 31];
      bits -= 5;
    }
  }
  if (bits) out += alphabet[value << 5 - bits & 31];
  return out;
}
__name(base32, "base32");
async function rawCid(bytes) {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return `b${base32(new Uint8Array([1, 85, 18, 32, ...digest]))}`;
}
__name(rawCid, "rawCid");
async function deflate(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("deflate"));
  return new Response(stream).arrayBuffer();
}
__name(deflate, "deflate");
var audit = /* @__PURE__ */ __name((env, op, repo, subject, signer, evidence = {}) => env.GIT_DB.prepare(
  "INSERT INTO audit(id,op,repo,subject,signer_did,evidence_json,created_at) VALUES(?,?,?,?,?,?,?)"
).bind(crypto.randomUUID(), op, repo || null, subject || null, signer || null, JSON.stringify(evidence), (/* @__PURE__ */ new Date()).toISOString()).run(), "audit");
async function verifySignedRequest(request, env, body) {
  const did = request.headers.get("x-nekko-did") || "";
  const pub64 = request.headers.get("x-nekko-public-key") || "";
  const sig64 = request.headers.get("x-nekko-signature") || "";
  const timestamp = request.headers.get("x-nekko-timestamp") || "";
  const nonce = request.headers.get("x-nekko-nonce") || "";
  if (!validDid(did) || !pub64 || !sig64 || !nonce || nonce.length > 160) return null;
  const at = Date.parse(timestamp);
  if (!Number.isFinite(at) || Math.abs(Date.now() - at) > 3e5) return null;
  const pub = b64urlBytes(pub64);
  if (pub.length !== 32 || didFromPublicKey(pub) !== did) return null;
  const url = new URL(request.url);
  const bodyHash = await digestHex(body);
  const sigrefHeader = request.headers.get("x-nekko-sigref") || "";
  const projectionGraph = request.headers.get("x-kotobase-graph") || "";
  const projectionCommit = request.headers.get("x-kotobase-commit-cid") || "";
  const cacaoHash = await digestHex(enc.encode(request.headers.get("x-kotobase-cacao") || ""));
  const approvalsHeader = request.headers.get("x-nekko-approvals") || "";
  const approvalSuffix = approvalsHeader ? `
${await digestHex(enc.encode(approvalsHeader))}` : "";
  const authorizationHash = await digestHex(enc.encode(`${sigrefHeader}
${projectionGraph}
${projectionCommit}
${cacaoHash}${approvalSuffix}`));
  if (request.headers.get("x-nekko-authorization-hash") !== authorizationHash) return { error: "AuthorizationHashMismatch" };
  const message = `${request.method}
${url.pathname}${url.search}
${bodyHash}
${timestamp}
${nonce}
${authorizationHash}`;
  try {
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"]);
    if (!await crypto.subtle.verify("Ed25519", key, b64urlBytes(sig64), enc.encode(message))) return { error: "RequestSignatureMismatch" };
    await env.GIT_DB.prepare("INSERT INTO nonces(did,nonce,created_at) VALUES(?,?,?)").bind(did, nonce, (/* @__PURE__ */ new Date()).toISOString()).run();
    return { did, bodyHash };
  } catch (_) {
    return null;
  }
}
__name(verifySignedRequest, "verifySignedRequest");
async function verifySigref(request, signed, repo, ref, sha) {
  const encoded = request.headers.get("x-nekko-sigref") || "";
  try {
    const value = JSON.parse(new TextDecoder().decode(b64urlBytes(encoded)));
    if (value.rid !== `urn:kotobase:git:${repo}` || value.ref !== ref || value.commit !== `git:sha1:${sha}` || value.signer !== signed.did || !/^[0-9a-f]{128}$/.test(value.sig || "")) return null;
    const at = Date.parse(value.ts);
    if (!Number.isFinite(at) || Math.abs(Date.now() - at) > 3e5) return null;
    const pub = b64urlBytes(request.headers.get("x-nekko-public-key") || "");
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify("Ed25519", key, unhex(value.sig), sigrefPayload(value)) ? value : null;
  } catch (_) {
    return null;
  }
}
__name(verifySigref, "verifySigref");
async function verifyApprovalSigref(value, publicKey, repo, ref, sha) {
  try {
    if (value.rid !== `urn:kotobase:git:${repo}` || value.ref !== ref || value.commit !== `git:sha1:${sha}` || !validDid(value.signer) || !/^[0-9a-f]{128}$/.test(value.sig || "")) return false;
    const at = Date.parse(value.ts);
    if (!Number.isFinite(at) || Math.abs(Date.now() - at) > 3e5) return false;
    const pub = b64urlBytes(publicKey || "");
    if (pub.length !== 32 || didFromPublicKey(pub) !== value.signer) return false;
    const key = await crypto.subtle.importKey("raw", pub, { name: "Ed25519" }, false, ["verify"]);
    return crypto.subtle.verify("Ed25519", key, unhex(value.sig), sigrefPayload(value));
  } catch (_) {
    return false;
  }
}
__name(verifyApprovalSigref, "verifyApprovalSigref");
async function verifyQuorumApprovals(request, env, repo, ref, sha, ownerDid, minimum) {
  const encoded = request.headers.get("x-nekko-approvals") || "";
  if (!encoded) return minimum <= 1 ? { ok: true, signers: [] } : { ok: false, error: "QuorumApprovalsRequired" };
  try {
    const approvals = JSON.parse(new TextDecoder().decode(b64urlBytes(encoded)));
    if (!Array.isArray(approvals) || approvals.length > 16) return { ok: false, error: "InvalidQuorumApprovals" };
    const wanted = `kotoba-rad://urn:kotobase:git:${repo}/push/${ref}`;
    const accepted = [];
    const nonceRows = [];
    for (const approval of approvals) {
      const sigref = approval?.sigref;
      if (!sigref || accepted.includes(sigref.signer) || !await verifyApprovalSigref(sigref, approval.publicKey, repo, ref, sha)) continue;
      const payloads = await verifyCacaoChain(approval.chain, ownerDid, sigref.signer, wanted);
      if (!payloads) continue;
      accepted.push(sigref.signer);
      for (const payload of payloads) nonceRows.push([payload.iss, payload.nonce]);
    }
    if (accepted.length < minimum) return { ok: false, error: "QuorumNotReached", accepted: accepted.length, required: minimum };
    const uniqueNonces = [...new Map(nonceRows.map((row) => [`${row[0]}\0${row[1]}`, row])).values()];
    try {
      await env.GIT_DB.batch(uniqueNonces.map(([issuer, nonce]) => env.GIT_DB.prepare(
        "INSERT INTO cacao_nonces(issuer_did,nonce,created_at) VALUES(?,?,?)"
      ).bind(issuer, nonce, (/* @__PURE__ */ new Date()).toISOString())));
    } catch (_) {
      return { ok: false, error: "CacaoChainReplay" };
    }
    return { ok: true, signers: accepted };
  } catch (_) {
    return { ok: false, error: "InvalidQuorumApprovals" };
  }
}
__name(verifyQuorumApprovals, "verifyQuorumApprovals");
async function isAncestor(env, repo, ancestor, descendant) {
  if (ancestor === descendant) return true;
  const seen = /* @__PURE__ */ new Set();
  const pending = [descendant];
  while (pending.length && seen.size < 1e5) {
    const sha = pending.pop();
    if (seen.has(sha)) continue;
    seen.add(sha);
    const row = await env.GIT_DB.prepare("SELECT parents_json FROM objects WHERE repo=? AND sha=?").bind(repo, sha).first();
    if (!row) continue;
    for (const parent of JSON.parse(row.parents_json)) {
      if (parent === ancestor) return true;
      if (!seen.has(parent)) pending.push(parent);
    }
  }
  return false;
}
__name(isAncestor, "isAncestor");
async function verifyKotobaseProjection(repo, ref, sha, sigref, graph, cacao, did) {
  if (!/^b[a-z2-7]{20,}$/.test(graph || "")) return false;
  if (!cacao || cacao.length > 16384) return false;
  for (let attempt = 0; attempt < 5; attempt += 1) {
    try {
      const response = await fetch("https://kotobase.net/xrpc/ai.gftd.apps.kotobase.datomic.datoms", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "accept": "application/json",
          "authorization": `CACAO ${cacao}`,
          "x-kotoba-did": did
        },
        body: JSON.stringify({ graph, index: ":avet", components_edn: [":git.ref/sigref"], cacao_b64: cacao })
      });
      if (response.ok) {
        const result = await response.json();
        const datoms = result.datoms || [];
        if (datoms.some((datom) => datom.a === ":git.ref/sigref" && String(datom.v_edn || datom.v || "").includes(sha) && String(datom.v_edn || datom.v || "").includes(sigref.sig))) return true;
      }
    } catch (_) {
    }
    await new Promise((resolve) => setTimeout(resolve, 150 * (attempt + 1)));
  }
  return false;
}
__name(verifyKotobaseProjection, "verifyKotobaseProjection");
async function authority(env, repo, did, claim = false) {
  const row = await env.GIT_DB.prepare("SELECT owner_did FROM repos WHERE name=?").bind(repo).first();
  if (!row && claim) {
    await env.GIT_DB.prepare("INSERT INTO repos(name,owner_did,created_at) VALUES(?,?,?)").bind(repo, did, (/* @__PURE__ */ new Date()).toISOString()).run();
    return "owner";
  }
  if (row?.owner_did === did) return "owner";
  const delegated = await env.GIT_DB.prepare("SELECT active FROM delegates WHERE repo=? AND did=?").bind(repo, did).first();
  return delegated?.active === 1 ? "delegate" : null;
}
__name(authority, "authority");
async function signedWrite(request, env, url) {
  if (request.method !== "PUT") return json({ ok: false, error: "MethodNotAllowed" }, 405);
  const body = await request.arrayBuffer();
  const signed = await verifySignedRequest(request, env, body);
  if (!signed?.did) return json({ ok: false, error: signed?.error || "InvalidNekkoSignatureOrReplay" }, 401);
  const repo = url.searchParams.get("repo") || "";
  if (!validRepo(repo)) return json({ ok: false, error: "InvalidRepo" }, 400);
  const role = await authority(env, repo, signed.did, url.pathname === "/xrpc/kotobase.git.object.put");
  if (!role) return json({ ok: false, error: "NotAuthorizedForRepo" }, 403);
  const now = (/* @__PURE__ */ new Date()).toISOString();
  if (url.pathname === "/xrpc/kotobase.git.object.put") {
    const sha = url.searchParams.get("sha") || "";
    if (!validSha(sha)) return json({ ok: false, error: "InvalidSha" }, 400);
    if (body.byteLength > 100 * 1024 * 1024) return json({ ok: false, error: "ObjectTooLarge" }, 413);
    const parents = (url.searchParams.getAll("parent") || []).filter(validSha);
    if (parents.length !== url.searchParams.getAll("parent").length) return json({ ok: false, error: "InvalidParent" }, 400);
    const computedSha = await digestHex(body, "SHA-1");
    if (computedSha !== sha) return json({ ok: false, error: "GitObjectIntegrityFailed", expected: sha, computed: computedSha }, 422);
    const blockCid = await rawCid(body);
    const looseBytes = await deflate(body);
    await env.GIT_OBJECTS.put(`blocks/${blockCid}`, body, {
      customMetadata: { cid: blockCid, codec: "raw", sha1: sha, sha256: signed.bodyHash, signer: signed.did }
    });
    await env.GIT_OBJECTS.put(`${repo}/objects/${sha.slice(0, 2)}/${sha.slice(2)}`, looseBytes, {
      customMetadata: { sha1: sha, blockCid, cache: "git-loose", signer: signed.did }
    });
    await env.GIT_DB.prepare("INSERT OR REPLACE INTO objects(repo,sha,oid_sha256,size,parents_json,signer_did,created_at,block_cid) VALUES(?,?,?,?,?,?,?,?)").bind(repo, sha, signed.bodyHash, body.byteLength, JSON.stringify(parents), signed.did, now, blockCid).run();
    await audit(env, "put-object", repo, sha, signed.did, { sha256: signed.bodyHash, blockCid, size: body.byteLength, parents });
    return json({ ok: true, repo, sha, cid: blockCid }, 201);
  }
  if (url.pathname === "/xrpc/kotobase.git.ref.set") {
    const ref = url.searchParams.get("ref") || "";
    const sha = url.searchParams.get("sha") || "";
    const old = url.searchParams.get("old");
    if (!validRef(ref) || !validSha(sha) || old !== null && old !== "" && !validSha(old)) return json({ ok: false, error: "InvalidRefUpdate" }, 400);
    const sigref = await verifySigref(request, signed, repo, ref, sha);
    if (!sigref) return json({ ok: false, error: "InvalidNekkoSigref" }, 401);
    const policy = await env.GIT_DB.prepare("SELECT owner_did,min_signers FROM repos WHERE name=?").bind(repo).first();
    const quorum = await verifyQuorumApprovals(request, env, repo, ref, sha, policy.owner_did, policy.min_signers || 1);
    if (!quorum.ok) return json({ ok: false, ...quorum }, 403);
    const kotobaseGraph = request.headers.get("x-kotobase-graph") || "";
    const kotobaseCommit = request.headers.get("x-kotobase-commit-cid") || "";
    const kotobaseCacao = request.headers.get("x-kotobase-cacao") || "";
    if (!/^b[a-z2-7]{20,}$/.test(kotobaseCommit) || !await verifyKotobaseProjection(repo, ref, sha, sigref, kotobaseGraph, kotobaseCacao, signed.did))
      return json({ ok: false, error: "KotobaseProjectionNotVerified" }, 409);
    const object = await env.GIT_DB.prepare("SELECT parents_json FROM objects WHERE repo=? AND sha=?").bind(repo, sha).first();
    if (!object) return json({ ok: false, error: "UnknownObject" }, 409);
    const current = await env.GIT_DB.prepare("SELECT sha FROM refs WHERE repo=? AND ref=?").bind(repo, ref).first();
    const currentSha = current?.sha || null;
    if ((old || null) !== currentSha) return json({ ok: false, error: "CompareAndSetFailed", current: currentSha }, 409);
    if (currentSha && !await isAncestor(env, repo, currentSha, sha)) return json({ ok: false, error: "NonFastForward" }, 409);
    const event = {
      schema: 1,
      id: crypto.randomUUID(),
      type: ref === "refs/heads/main" ? "kotobase.git.ref.promoted" : "kotobase.git.canary.promoted",
      repo,
      ref,
      old: currentSha,
      sha,
      signerDid: signed.did,
      sigref,
      kotobaseGraph,
      kotobaseCommit,
      requestedAt: now,
      bounds: {
        maxFiles: 32,
        maxChangedLines: 1200,
        maxSpendUsd: "1.00",
        allowedPhases: ["observe", "propose", "test", "canary"]
      }
    };
    const evidence = {
      old: currentSha,
      sha,
      role,
      sigref,
      quorumSigners: quorum.signers,
      quorumRequired: policy.min_signers || 1,
      kotobaseGraph,
      kotobaseCommit,
      eventId: event.id
    };
    const statements = [
      env.GIT_DB.prepare("INSERT INTO refs(repo,ref,sha,updated_at,signer_did,kotobase_graph,kotobase_commit_cid) VALUES(?,?,?,?,?,?,?) ON CONFLICT(repo,ref) DO UPDATE SET sha=excluded.sha,updated_at=excluded.updated_at,signer_did=excluded.signer_did,kotobase_graph=excluded.kotobase_graph,kotobase_commit_cid=excluded.kotobase_commit_cid").bind(repo, ref, sha, now, signed.did, kotobaseGraph, kotobaseCommit),
      env.GIT_DB.prepare("INSERT INTO event_outbox(id,event_json,created_at) VALUES(?,?,?)").bind(event.id, JSON.stringify(event), now),
      env.GIT_DB.prepare("INSERT INTO audit(id,op,repo,subject,signer_did,evidence_json,created_at) VALUES(?,?,?,?,?,?,?)").bind(crypto.randomUUID(), "set-ref", repo, ref, signed.did, JSON.stringify(evidence), now)
    ];
    if (ref === "refs/heads/main") statements.push(env.GIT_DB.prepare("INSERT INTO heads(repo,ref,updated_at) VALUES(?,?,?) ON CONFLICT(repo) DO UPDATE SET ref=excluded.ref,updated_at=excluded.updated_at").bind(repo, ref, now));
    await env.GIT_DB.batch(statements);
    let eventQueued = false;
    try {
      await env.KAIZEN_EVENTS.send(event, { contentType: "json" });
      await env.GIT_DB.prepare("UPDATE event_outbox SET delivered_at=? WHERE id=?").bind((/* @__PURE__ */ new Date()).toISOString(), event.id).run();
      eventQueued = true;
    } catch (_) {
    }
    return json({ ok: true, repo, ref, old: currentSha, sha, kotobaseGraph, kotobaseCommit, eventId: event.id, eventQueued });
  }
  if (url.pathname === "/xrpc/kotobase.git.delegate.set") {
    if (role !== "owner") return json({ ok: false, error: "OwnerRequired" }, 403);
    const delegate = url.searchParams.get("did") || "";
    const active = url.searchParams.get("active") !== "false";
    if (!validDid(delegate)) return json({ ok: false, error: "InvalidDelegateDid" }, 400);
    await env.GIT_DB.prepare("INSERT INTO delegates(repo,did,active,updated_at) VALUES(?,?,?,?) ON CONFLICT(repo,did) DO UPDATE SET active=excluded.active,updated_at=excluded.updated_at").bind(repo, delegate, active ? 1 : 0, now).run();
    await audit(env, active ? "delegate-add" : "delegate-remove", repo, delegate, signed.did);
    return json({ ok: true, repo, did: delegate, active });
  }
  if (url.pathname === "/xrpc/kotobase.git.quorum.set") {
    if (role !== "owner") return json({ ok: false, error: "OwnerRequired" }, 403);
    const minimum = Number(url.searchParams.get("min"));
    if (!Number.isInteger(minimum) || minimum < 1 || minimum > 16)
      return json({ ok: false, error: "InvalidQuorum" }, 400);
    const available = await env.GIT_DB.prepare("SELECT COUNT(*) AS n FROM delegates WHERE repo=? AND active=1").bind(repo).first();
    if (minimum > Number(available?.n || 0) + 1) return json({ ok: false, error: "QuorumExceedsAuthorizedSigners" }, 409);
    await env.GIT_DB.prepare("UPDATE repos SET min_signers=? WHERE name=?").bind(minimum, repo).run();
    await audit(env, "quorum-set", repo, String(minimum), signed.did, { minimum });
    return json({ ok: true, repo, minSigners: minimum });
  }
  return json({ ok: false, error: "NotFound" }, 404);
}
__name(signedWrite, "signedWrite");
async function gitRead(request, env, url) {
  if (request.method !== "GET" && request.method !== "HEAD") return text("method not allowed", 405);
  const path = url.pathname.replace(/^\/+/, "");
  let m = path.match(/^([^/]+\/[^/]+)\/info\/refs$/);
  if (m) {
    const rows = await env.GIT_DB.prepare("SELECT ref,sha FROM refs WHERE repo=? ORDER BY ref").bind(m[1]).all();
    if (!rows.results.length) return text("repository not found", 404);
    return text(rows.results.map((r) => `${r.sha}	${r.ref}
`).join(""));
  }
  m = path.match(/^([^/]+\/[^/]+)\/HEAD$/);
  if (m) {
    const row = await env.GIT_DB.prepare("SELECT ref FROM heads WHERE repo=?").bind(m[1]).first();
    return row ? text(`ref: ${row.ref}
`) : text("repository not found", 404);
  }
  m = path.match(/^([^/]+\/[^/]+)\/objects\/([0-9a-f]{2})\/([0-9a-f]{38})$/);
  if (m) {
    const sha = `${m[2]}${m[3]}`;
    let value = await env.GIT_OBJECTS.get(`${m[1]}/objects/${m[2]}/${m[3]}`);
    if (!value) {
      const row = await env.GIT_DB.prepare("SELECT block_cid FROM objects WHERE repo=? AND sha=?").bind(m[1], sha).first();
      const block = row?.block_cid ? await env.GIT_OBJECTS.get(`blocks/${row.block_cid}`) : null;
      if (block) {
        const raw = await block.arrayBuffer();
        const loose = await deflate(raw);
        await env.GIT_OBJECTS.put(
          `${m[1]}/objects/${m[2]}/${m[3]}`,
          loose,
          { customMetadata: { sha1: sha, blockCid: row.block_cid, cache: "git-loose" } }
        );
        value = await env.GIT_OBJECTS.get(`${m[1]}/objects/${m[2]}/${m[3]}`);
      }
    }
    return value ? new Response(request.method === "HEAD" ? null : value.body, { status: 200, headers: { "content-type": "application/x-git-loose-object", etag: value.httpEtag } }) : text("object not found", 404);
  }
  return text("not found", 404);
}
__name(gitRead, "gitRead");
async function refMetadata(env, url) {
  const repo = url.searchParams.get("repo") || "";
  const ref = url.searchParams.get("ref") || "";
  if (!validRepo(repo) || !validRef(ref)) return json({ ok: false, error: "InvalidRef" }, 400);
  const row = await env.GIT_DB.prepare("SELECT sha,kotobase_graph,kotobase_commit_cid,updated_at FROM refs WHERE repo=? AND ref=?").bind(repo, ref).first();
  return row ? json({
    ok: true,
    repo,
    ref,
    sha: row.sha,
    kotobaseGraph: row.kotobase_graph,
    kotobaseCommit: row.kotobase_commit_cid,
    updatedAt: row.updated_at
  }) : json({ ok: false, error: "RefNotFound" }, 404);
}
__name(refMetadata, "refMetadata");
async function flushOutbox(env) {
  const pending = await env.GIT_DB.prepare("SELECT id,event_json FROM event_outbox WHERE delivered_at IS NULL ORDER BY created_at LIMIT 50").all();
  for (const row of pending.results) {
    try {
      await env.KAIZEN_EVENTS.send(JSON.parse(row.event_json), { contentType: "json" });
      await env.GIT_DB.prepare("UPDATE event_outbox SET delivered_at=? WHERE id=? AND delivered_at IS NULL").bind((/* @__PURE__ */ new Date()).toISOString(), row.id).run();
    } catch (_) {
      break;
    }
  }
}
__name(flushOutbox, "flushOutbox");
var git_worker_default = { async fetch(request, env) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") {
    return json({
      ok: true,
      service: "kotobase-git",
      version: env.SERVICE_VERSION,
      refs: "kotobase-datom-authority+d1-cas",
      objects: "kotobase-protocol-blocks-cidv1+r2-loose-cache",
      auth: "nekko-sigref+delegated-cacao-chain+distinct-signer-quorum",
      protocol: "git-dumb-http+remote-helper"
    }, 200, { "cache-control": "no-store" });
  }
  if (request.method === "GET" && url.pathname === "/xrpc/kotobase.git.ref.get") return refMetadata(env, url);
  if (url.pathname.startsWith("/xrpc/kotobase.git.")) return signedWrite(request, env, url);
  return gitRead(request, env, url);
}, async scheduled(_controller, env, ctx) {
  ctx.waitUntil(flushOutbox(env));
} };
export {
  decodeCbor,
  git_worker_default as default,
  rawCid,
  verifyCacao,
  verifyCacaoChain
};
//# sourceMappingURL=git-worker.js.map
