(ns kotobase.protocols.ipfs-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.protocols.blocks :as blocks]
            [kotobase.protocols.ipfs :as ipfs]))

(def cid "bafyreigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi")

(defn- ctx [] {:store (local/local-store)})

(deftest gateway-serves-blocks
  (let [{:keys [store] :as c} (ctx)]
    (blocks/put-block! store cid {:bytes "{\"a\":1}" :content-type "application/json"})
    (let [res (ipfs/handle c {:method :get :path (str "/ipfs/" cid)})]
      (is (= 200 (:status res)))
      (is (= "{\"a\":1}" (:body res)))
      (is (= "application/json" (get-in res [:headers "content-type"])))
      (is (= (str "\"" cid "\"") (get-in res [:headers "etag"])))
      (is (re-find #"immutable" (get-in res [:headers "cache-control"]))))
    (testing "HEAD serves headers only"
      (let [res (ipfs/handle c {:method :head :path (str "/ipfs/" cid)})]
        (is (= 200 (:status res)))
        (is (nil? (:body res)))))))

(deftest content-type-sanitization
  (let [{:keys [store] :as c} (ctx)]
    (blocks/put-block! store cid {:bytes "<svg/>" :content-type "image/svg+xml"})
    (let [res (ipfs/handle c {:method :get :path (str "/ipfs/" cid)})]
      (is (= "application/octet-stream" (get-in res [:headers "content-type"]))
          "scriptable content types degrade to octet-stream"))))

(deftest missing-and-write-paths
  (let [c (ctx)]
    (is (= 404 (:status (ipfs/handle c {:method :get :path (str "/ipfs/" cid)}))))
    (is (= 501 (:status (ipfs/handle c {:method :get :path "/ipns/example.com"}))))
    (is (= 405 (:status (ipfs/handle c {:method :put :path (str "/ipfs/" cid)})))
        "HTTP surface is read-only")))
