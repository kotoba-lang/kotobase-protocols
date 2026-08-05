(ns kotobase.protocols.s3-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotobase.protocols.store :as local]
            [kotobase.protocols.s3 :as s3]
            [kotobase.protocols.store :as st]))

(defn- ctx [] {:store (local/memory-store) :now "2026-07-17T00:00:00Z"})

(deftest put-get-head-delete-object
  (let [c (ctx)
        put (s3/handle c {:method :put :path "/media/videos/a.txt"
                          :headers {"content-type" "text/plain"}
                          :body "hello"})]
    (testing "PUT returns quoted etag"
      (is (= 200 (:status put)))
      (is (str/starts-with? (get-in put [:headers "etag"]) "\"")))
    (testing "GET round-trips body, content-type, etag; key may contain /"
      (let [got (s3/handle c {:method :get :path "/media/videos/a.txt"})]
        (is (= 200 (:status got)))
        (is (= "hello" (:body got)))
        (is (= "text/plain" (get-in got [:headers "content-type"])))
        (is (= (get-in put [:headers "etag"]) (get-in got [:headers "etag"])))))
    (testing "HEAD has headers, no body"
      (let [head (s3/handle c {:method :head :path "/media/videos/a.txt"})]
        (is (= 200 (:status head)))
        (is (nil? (:body head)))
        (is (= "5" (get-in head [:headers "content-length"])))))
    (testing "DELETE then GET is NoSuchKey"
      (is (= 204 (:status (s3/handle c {:method :delete :path "/media/videos/a.txt"}))))
      (let [got (s3/handle c {:method :get :path "/media/videos/a.txt"})]
        (is (= 404 (:status got)))
        (is (str/includes? (:body got) "NoSuchKey"))))))

(deftest list-objects-v2
  (let [c (ctx)]
    (doseq [k ["a/1" "a/2" "b/1"]]
      (s3/handle c {:method :put :path (str "/bkt/" k) :body k}))
    (s3/handle c {:method :delete :path "/bkt/a/2"})
    (let [res (s3/handle c {:method :get :path "/bkt"
                            :query {"list-type" "2" "prefix" "a/"}})]
      (is (= 200 (:status res)))
      (is (str/includes? (:body res) "<KeyCount>1</KeyCount>"))
      (is (str/includes? (:body res) "<Key>a/1</Key>"))
      (is (not (str/includes? (:body res) "<Key>a/2</Key>")))
      (is (not (str/includes? (:body res) "<Key>b/1</Key>"))))))

(deftest audit-trail
  (let [{:keys [store] :as c} (ctx)]
    (s3/handle c {:method :put :path "/bkt/k" :body "v"})
    (s3/handle c {:method :delete :path "/bkt/k"})
    (let [events (st/read-events store :kotobase.protocols/audit 0)]
      (is (= [:put-object :delete-object] (map :op events)))
      (is (every? #(= :s3 (:surface %)) events)))))

(deftest xml-escaping
  (let [c (ctx)]
    (s3/handle c {:method :put :path "/bkt/a&b<c" :body "x"})
    (let [res (s3/handle c {:method :get :path "/bkt" :query {}})]
      (is (str/includes? (:body res) "<Key>a&amp;b&lt;c</Key>")))))
