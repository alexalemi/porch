;; Run: cljc test wall_test.clj
;;
;; NOT named test.clj — `cljc test` starts with (load-file "test.clj") to pull
;; in the battery, and `.` leads the load path, so a project-local test.clj
;; shadows it and run-tests never gets defined.
(require '[wall.tid :as tid] '[wall.doc :as doc])

(deftest tid-encoding
  (is (= 13 (count (tid/encode 12345))) "13 chars")
  (is (= 12345 (tid/decode (tid/encode 12345))) "encode/decode round-trip")
  (is (= (map tid/encode [3 500 90000]) (sort (map tid/encode [500 3 90000])))
      "alphabetical order = numeric order")
  (is (tid/tid? (tid/encode 999)) "tid? accepts what encode makes")
  ;; the clock id lives in the LOW bits, so it must never outrank the timestamp
  (let [mk (fn [cid us] (tid/encode (bit-or cid (bit-shift-left us 10))))]
    (is (= [(mk 1023 1788640000000000) (mk 0 1788640000000001)]
           (sort [(mk 1023 1788640000000000) (mk 0 1788640000000001)]))
        "1us later with min clock-id still sorts last"))
  ;; 13 chars from the epoch to the top of the documented range
  (is (= #{13} (set (map (fn [us] (count (tid/encode (bit-or 1023 (bit-shift-left us 10)))))
                         [1 1788640000000000 (dec (bit-shift-left 1 53))])))
      "width stable across the whole range"))

(deftest tid-minting
  (let [ts (vec (repeatedly 5000 tid/next-tid))]
    (is (every? tid/tid? ts) "all well-formed")
    (is (= ts (sort ts)) "5000 rapid TIDs come out sorted")
    (is (= 5000 (count (set ts))) "5000 rapid TIDs are distinct"))
  (is (< (Math/abs (- (tid/micros (tid/next-tid)) (cljc/now-us*))) 5000000)
      "the timestamp round-trips to within 5s of now"))

(deftest doc-fenced
  (is (= {:front {:title "Hi"} :body "body\n"} (doc/parse "---\ntitle: Hi\n---\nbody\n"))
      "fenced doc splits")
  (is (= {:front {:url "http://x"} :body ""} (doc/parse "---\nurl: http://x\n---"))
      "front-matter-only doc")
  (is (= "hello\n" (doc/render {:front nil :body "hello\n"}))
      "no front matter renders bare")
  (let [d {:front {:subject "sam/posts/aaaaaaaaaaaaa"} :body ""}]
    (is (= d (doc/parse (doc/render d))) "render/parse round-trip")))

(deftest doc-unfenced
  ;; The fence is the only carrier of metadata, so every one of these is body —
  ;; including the line that looks exactly like a likes/ file's front matter.
  (is (= "hello world\n" (:body (doc/parse "hello world\n")))
      "a plain one-line post is body")
  (is (= "Reading: notes\n" (:body (doc/parse "Reading: notes\n")))
      "prose that is accidentally a YAML map is still body")
  (is (= "one\n\ntwo\n" (:body (doc/parse "one\n\ntwo\n")))
      "a multi-line post is body")
  (is (= "subject: sam/likes/x\n" (:body (doc/parse "subject: sam/likes/x\n")))
      "YAML-shaped text without a fence is body, not front matter")
  (is (= {} (:front (doc/parse "hello\n"))) "unfenced front matter is empty")
  ;; an opening fence that never closes, or one whose contents aren't a map,
  ;; is a post that happens to start with a rule — never dropped
  (is (= "---\nnot a header\n" (:body (doc/parse "---\nnot a header\n")))
      "an unclosed fence is body")
  (is (= "---\njust a list\n---\n" (:body (doc/parse "---\njust a list\n---\n")))
      "a fence whose contents aren't a YAML map is body"))

(deftest doc-round-trip
  ;; The payoff of dropping the sniffing rule: parse . render = identity, for
  ;; body-only docs, front-only docs (likes/, feeds/) and both together.
  (doseq [d [{:front {} :body "hello world\n"}
             {:front {} :body "subject: sam/likes/x\n"}
             {:front {:subject "sam/posts/aaaaaaaaaaaaa"} :body ""}
             {:front {:url "http://x" :title "T"} :body "why I like it\n"}]]
    (is (= d (doc/parse (doc/render d))) (str "round-trips: " (pr-str d)))))

;; ── store / collections ────────────────────────────────────────────────────
;; These write to a throwaway $HOME so they can never touch a real wall.
(require '[wall.store :as store])

(def box (str "/tmp/wall-test-" (cljc/getpid)))
(sh (str "rm -rf " (pr-str box)))

(deftest collections
  (is (= ".md" (store/ext "posts")) "text collections get .md")
  (is (= "" (store/ext "likes")) "reference collections are bare")
  (is (= ["sam" "posts" "abc"] (store/parse-addr "sam/posts/abc")) "address splits")
  (is (= ["sam" "posts" "abc"] (store/parse-addr "@sam/posts/abc")) "a leading @ is fine")
  (is (thrown? Exception (store/parse-addr "sam/posts")) "a two-part address is rejected"))

(deftest write-and-read
  (binding [store/*homes* box]
   (let [me (store/me)]
    (doseq [[coll front body]
            [["posts" nil                          "hello\n"]
             ["blog"  {:title "T"}                 "# T\n\nbody\n"]
             ["links" {:url "http://x" :title "L"} "why\n"]
             ["likes" {:subject "sam/posts/aaaaaaaaaaaaa"} ""]
             ["feeds" {:url "http://f/atom"}       ""]]]
      (let [k (tid/next-tid)
            _ (store/write-doc! me coll k {:front front :body body})
            back (store/read-doc (store/addr me coll k))]
        (is (= (or front {}) (:front back)) (str coll ": front matter survives the disk"))
        (is (= body (:body back)) (str coll ": body survives the disk"))))
    ;; every collection now has exactly one thing in it, listed by address
    (doseq [c ["posts" "blog" "links" "likes" "feeds"]]
      (is (= 1 (count (store/rkeys me c))) (str c ": one entry")))
    (is (nil? (store/read-doc (store/addr me "posts" "3zzzzzzzzzzzz")))
        "a dangling address reads as nil, not an error")
    (is (= [me] (store/users)) "users lists whoever has a .wall on this box"))))

(deftest likes-are-idempotent
  (binding [store/*homes* box]
   (let [me (store/me)
        subject "sam/links/3aaaaaaaaaaaa"]
    (is (nil? (store/find-like me subject)) "not liked yet")
    (store/write-doc! me "likes" (tid/next-tid) {:front {:subject subject} :body ""})
    (let [found (store/find-like me subject)]
      (is (some? found) "find-like locates it")
      (is (= found (store/find-like me subject)) "and keeps finding the same one"))
    (is (nil? (store/find-like me "sam/links/3bbbbbbbbbbbb")) "unrelated subject not matched"))))

(deftest cross-user-index
  ;; A whole fake box: three users, no root, no real accounts.
  (let [box2 (str box "-multi")]
    (sh (str "rm -rf " (pr-str box2)))
    (binding [store/*homes* box2]
      ;; interleave the writes so correct order can't come from grouping by user
      (let [order (vec (for [[u c] [["alemi" "posts"] ["sam" "posts"] ["vi" "links"]
                                    ["sam" "blog"]    ["alemi" "posts"] ["vi" "posts"]]]
                         (let [k (tid/next-tid)]
                           (store/write-doc! u c k {:front (when (= c "links") {:url "http://x"})
                                                    :body (if (= c "links") "" "hi\n")})
                           [k u c])))
            idx (store/index (store/users) ["posts" "blog" "links"])]
        (is (= #{"alemi" "sam" "vi"} (set (store/users))) "every user with a wall is found")
        (is (= (reverse order) idx) "merged newest-first across users and collections")
        (is (= 4 (count (store/index (store/users) ["posts"]))) "collections filter")
        (is (= 2 (count (store/index ["alemi"] ["posts"]))) "users filter")))))
