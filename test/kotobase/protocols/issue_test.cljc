(ns kotobase.protocols.issue-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotobase.local :as local]
            [kotobase.protocols.issue :as issue]
            [kotobase.protocols.json :as json]
            [kotobase.store :as st]))

(def repo "gftdcojp/local-manimani")

(defn- ctx [] {:store (local/local-store) :now "2026-07-18T00:00:00Z"})

(defn- req
  ([method path] (req method path nil))
  ([method path body]
   (cond-> {:method method :path (str "/" repo path)}
     body (assoc :body (json/encode body)))))

(defn- create-issue! [c body] (issue/handle c (req :post "/issues" body)))
(defn- get-issue [c id] (issue/handle c (req :get (str "/issues/" id))))
(defn- list-issues
  ([c] (issue/handle c (req :get "/issues")))
  ([c status] (issue/handle c (assoc (req :get "/issues") :query {"status" status}))))
(defn- propose! [c issue-id body] (issue/handle c (req :post (str "/issues/" issue-id "/proposals") body)))
(defn- get-proposal [c id] (issue/handle c (req :get (str "/proposals/" id))))
(defn- review! [c proposal-id body] (issue/handle c (req :post (str "/proposals/" proposal-id "/reviews") body)))
(defn- merge! [c proposal-id] (issue/handle c (req :post (str "/proposals/" proposal-id "/merge"))))

(defn- body-of [res] (json/parse (:body res)))

(deftest create-and-fetch-issue
  (let [c (ctx)
        res (create-issue! c {"title" "login button misaligned" "body" "off by 4px" "author" "did:key:z6Mk..."})
        b (body-of res)]
    (is (= 201 (:status res)))
    (is (string? (get b "id")))
    (is (= "open" (get b "status")))
    (is (= "login button misaligned" (get b "title")))
    (testing "GET fetches it back with an empty proposalIds list"
      (let [g (body-of (get-issue c (get b "id")))]
        (is (= (get b "id") (get g "id")))
        (is (= [] (get g "proposalIds")))))
    (testing "unknown id -> 404"
      (is (= 404 (:status (get-issue c "does-not-exist")))))))

(deftest create-issue-requires-title
  (let [c (ctx)
        res (create-issue! c {"body" "no title here"})]
    (is (= 400 (:status res)))
    (is (= "title is required" (get (body-of res) "error")))))

(deftest list-issues-with-status-filter
  (let [c (ctx)
        id1 (get (body-of (create-issue! c {"title" "a"})) "id")
        _id2 (get (body-of (create-issue! c {"title" "b"})) "id")]
    (testing "unfiltered sees both"
      (is (= 2 (get (body-of (list-issues c)) "count"))))
    (testing "close one out of band via a merge and re-check open filter"
      (let [pid (get (body-of (propose! c id1 {"rationale" "fix"})) "id")]
        (review! c pid {"verdict" "approve"})
        (merge! c pid)
        ;; merging a proposal does not auto-close its issue (v0.1 scope,
        ;; documented in the ns docstring) -- both issues stay open
        (is (= 2 (get (body-of (list-issues c "open")) "count")))))))

(deftest full-issue-to-merge-loop
  (let [c (ctx)
        issue-id (get (body-of (create-issue! c {"title" "flaky test"})) "id")
        proposal-id (get (body-of (propose! c issue-id {"rationale" "add retry" "risk" "low" "author" "agent:x"})) "id")]
    (is (string? proposal-id))
    (testing "proposal is visible from the issue"
      (is (= [proposal-id] (get (body-of (get-issue c issue-id)) "proposalIds"))))
    (testing "merge before any review -> 409"
      (let [res (merge! c proposal-id)]
        (is (= 409 (:status res)))
        (is (= "no approving review yet" (get (body-of res) "error")))))
    (testing "a request-changes review still doesn't unblock merge"
      (review! c proposal-id {"verdict" "request-changes" "comment" "needs a test"})
      (is (= 409 (:status (merge! c proposal-id)))))
    (testing "an approve review unblocks merge"
      (review! c proposal-id {"verdict" "approve" "reviewer" "did:key:zAbc"})
      (let [res (merge! c proposal-id)
            b (body-of res)]
        (is (= 200 (:status res)))
        (is (= "merged" (get b "status")))))
    (testing "both reviews show up on the proposal"
      (let [reviews (get (body-of (get-proposal c proposal-id)) "reviews")]
        (is (= 2 (count reviews)))
        (is (= ["request-changes" "approve"] (mapv #(get % "verdict") reviews)))))
    (testing "merging an already-merged proposal -> 409"
      (is (= 409 (:status (merge! c proposal-id)))))))

(deftest reject-verdict-still-blocks-merge
  (let [c (ctx)
        issue-id (get (body-of (create-issue! c {"title" "x"})) "id")
        proposal-id (get (body-of (propose! c issue-id {"rationale" "y"})) "id")]
    (review! c proposal-id {"verdict" "reject" "comment" "wrong approach"})
    (is (= 409 (:status (merge! c proposal-id))))))

(deftest review-rejects-unknown-verdict
  (let [c (ctx)
        issue-id (get (body-of (create-issue! c {"title" "x"})) "id")
        proposal-id (get (body-of (propose! c issue-id {"rationale" "y"})) "id")
        res (review! c proposal-id {"verdict" "lgtm"})]
    (is (= 400 (:status res)))))

(deftest propose-against-unknown-issue-is-404
  (let [c (ctx)]
    (is (= 404 (:status (propose! c "no-such-issue" {"rationale" "y"}))))))

(deftest review-against-unknown-proposal-is-404
  (let [c (ctx)]
    (is (= 404 (:status (review! c "no-such-proposal" {"verdict" "approve"}))))))

(deftest ids-are-distinct-across-repeated-creates
  (let [c (ctx)
        id1 (get (body-of (create-issue! c {"title" "a"})) "id")
        id2 (get (body-of (create-issue! c {"title" "b"})) "id")]
    (is (not= id1 id2))))

(deftest audit-trail-records-the-full-loop
  (let [c (ctx)
        {:keys [store]} c
        issue-id (get (body-of (create-issue! c {"title" "x"})) "id")
        proposal-id (get (body-of (propose! c issue-id {"rationale" "y"})) "id")]
    (review! c proposal-id {"verdict" "approve"})
    (merge! c proposal-id)
    (let [events (->> (st/-read store :kotobase.protocols/audit 0)
                      (filter #(= :issue (:surface %))))]
      (is (= [:create-issue :propose :review :merge] (map :op events)))
      (is (every? #(= repo (:repo %)) events)))))

(deftest missing-org-repo-path-is-404
  (let [c (ctx)]
    (is (= 404 (:status (issue/handle c {:method :get :path "/issues"}))))))

(deftest malformed-body-and-wrong-method
  (let [c (ctx)]
    (testing "malformed JSON body -> 400"
      (is (= 400 (:status (issue/handle c (assoc (req :post "/issues") :body "{oops"))))))
    (testing "unsupported method on /issues -> 404 fallthrough"
      (is (= 404 (:status (issue/handle c (req :delete "/issues"))))))))
