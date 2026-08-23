(ns kotobase.protocols.git-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.protocols.store :as local]
            [kotobase.protocols.git :as git]))

(def sha1 "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def sha2 "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(defn- seeded []
  (let [store (local/memory-store)]
    (git/put-object! store "gftd/nightglass" sha1 "loose-object-bytes-1")
    (git/put-object! store "gftd/nightglass" sha2 "loose-object-bytes-2")
    (git/set-ref! store "gftd/nightglass" "refs/heads/main" sha1)
    (git/set-ref! store "gftd/nightglass" "refs/tags/v1" sha2)
    (git/set-head! store "gftd/nightglass" "refs/heads/main")
    {:store store}))

(deftest info-refs
  (let [c (seeded)
        res (git/handle c {:method :get :path "/gftd/nightglass/info/refs"})]
    (is (= 200 (:status res)))
    (is (= (str sha1 "\trefs/heads/main\n" sha2 "\trefs/tags/v1\n")
           (:body res))
        "sorted refname lines, HEAD excluded")))

(deftest head-symref
  (let [c (seeded)
        res (git/handle c {:method :get :path "/gftd/nightglass/HEAD"})]
    (is (= 200 (:status res)))
    (is (= "ref: refs/heads/main\n" (:body res)))))

(deftest loose-objects
  (let [c (seeded)
        res (git/handle c {:method :get
                           :path (str "/gftd/nightglass/objects/"
                                      (subs sha1 0 2) "/" (subs sha1 2))})]
    (is (= 200 (:status res)))
    (is (= "loose-object-bytes-1" (:body res)))
    (is (= "application/x-git-loose-object"
           (get-in res [:headers "content-type"])))))

(deftest unknown-repo-and-object
  (let [c (seeded)]
    (is (= 404 (:status (git/handle c {:method :get :path "/nope/info/refs"}))))
    (is (= 404 (:status (git/handle c {:method :get
                                       :path (str "/gftd/nightglass/objects/cc/"
                                                  (apply str (repeat 38 "c")))}))))
    (testing "read-only surface"
      (is (= 405 (:status (git/handle c {:method :post
                                         :path "/gftd/nightglass/info/refs"})))))))

(deftest sha-validation
  (let [{:keys [store]} (seeded)]
    (is (thrown? #?(:clj AssertionError :cljs js/Error)
                 (git/put-object! store "r" "not-a-sha" "bytes")))))
