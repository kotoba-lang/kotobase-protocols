(ns kotobase.protocols.router-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.protocols.blocks :as blocks]
            [kotobase.protocols.json :as json]
            [kotobase.protocols.router :as router]))

(defn- ctx [] {:store (local/local-store) :now "2026-07-17T00:00:00Z"})

(deftest surface-of
  (is (= "s3" (router/surface-of "s3.kotobase.net" "kotobase.net")))
  (is (= "atproto" (router/surface-of "atproto.kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "a.b.kotobase.net" "kotobase.net")))
  (is (nil? (router/surface-of "s3.example.net" "kotobase.net")))
  (is (nil? (router/surface-of nil "kotobase.net"))))

(deftest host-based-dispatch
  (let [c (ctx)]
    (testing "s3 subdomain round-trip"
      (is (= 200 (:status (router/handle c {:method :put :host "s3.kotobase.net"
                                            :path "/bkt/k" :body "v"}))))
      (is (= "v" (:body (router/handle c {:method :get :host "s3.kotobase.net"
                                          :path "/bkt/k"})))))
    (testing "ipfs subdomain"
      (blocks/put-block! (:store c) "bafyexample" {:bytes "x" :content-type "text/plain"})
      (is (= "x" (:body (router/handle c {:method :get :host "ipfs.kotobase.net"
                                          :path "/ipfs/bafyexample"})))))
    (testing "atproto subdomain"
      (is (= 501 (:status (router/handle c {:method :get :host "atproto.kotobase.net"
                                            :path "/xrpc/does.not.exist"})))))
    (testing "git subdomain"
      (is (= 404 (:status (router/handle c {:method :get :host "git.kotobase.net"
                                            :path "/nope/info/refs"})))))
    (testing "pinning subdomain (write surface, distinct from read-only ipfs)"
      (blocks/put-block! (:store c) "bafypinme" {:bytes "x" :content-type "text/plain"})
      (let [res (router/handle c {:method :post :host "pinning.kotobase.net"
                                  :path "/pins" :body (json/encode {"cid" "bafypinme"})})]
        (is (= 202 (:status res)))
        (is (= "pinned" (get (json/parse (:body res)) "status")))))))

(deftest single-origin-fallback
  (let [c (ctx)]
    (is (= 200 (:status (router/handle c {:method :put :host "peer.local"
                                          :path "/s3/bkt/k" :body "v"})))
        "/s3/* mounts the S3 surface with the prefix stripped")
    (is (= "v" (:body (router/handle c {:method :get :host "peer.local"
                                        :path "/s3/bkt/k"}))))
    (is (= 501 (:status (router/handle c {:method :get :host "peer.local"
                                          :path "/xrpc/does.not.exist"}))))
    (testing "/pins is the spec-native path, mounted unstripped"
      (blocks/put-block! (:store c) "bafypeerpin" {:bytes "x" :content-type "text/plain"})
      (let [res (router/handle c {:method :post :host "peer.local" :path "/pins"
                                  :body (json/encode {"cid" "bafypeerpin"})})]
        (is (= 202 (:status res)))
        (is (= "pinned" (get (json/parse (:body res)) "status")))))
    (let [res (router/handle c {:method :get :host "peer.local" :path "/other"})]
      (is (= 404 (:status res)))
      (is (str/includes? (:body res) "no protocol surface")))))

(deftest custom-apex
  (let [c (assoc (ctx) :apex "peer.example")]
    (is (= 200 (:status (router/handle c {:method :put :host "s3.peer.example"
                                          :path "/bkt/k" :body "v"}))))))
