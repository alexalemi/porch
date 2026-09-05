(ns wall.store
  "Where things live on disk, and how addresses map onto files.

   An address is `user/collection/rkey` with no extension. Text collections
   resolve to a `.md` file; reference collections are bare, because they carry
   no body worth putting in markdown."
  (:require [clojure.string :as str]
            [wall.doc :as doc]))

;; Deliberately NOT (load-file "fs.clj"): that battery reaches the filesystem
;; through the FFI, which makes a bundled `wall` shell out to `cc` on first run
;; and dlopen a .so from a world-writable, content-addressed /tmp path — on a
;; shared box, any user could plant that .so ahead of you. cljc/list-dir*,
;; cljc/dir?* and cljc/env* are natives and need none of that.

(def text-collections #{"posts" "blog" "links"})
(def ref-collections  #{"likes" "feeds"})
(def collections (into text-collections ref-collections))

(defn me [] (or (cljc/env* "WALL_USER") (cljc/env* "USER")))

(def ^:dynamic *homes*
  "Where home directories live. nil means the real thing — /home, with $HOME
   honoured for the current user. Set it (via $WALL_HOMES) or bind it to a temp
   dir and you get a whole throwaway box: several users, their own walls, no
   root and no real accounts. That's how the tests stay off your real wall, and
   how you can try a multi-user timeline before anyone else is on the machine."
  (cljc/env* "WALL_HOMES"))

(defn home
  "A user's home directory."
  [user]
  (cond
    *homes*        (str *homes* "/" user)
    (= user (me))  (or (cljc/env* "HOME") (str "/home/" user))
    :else          (str "/home/" user)))

(defn wall-dir [user] (str (home user) "/.wall"))

(defn- exists? [p]
  (or (cljc/dir?* p)
      (try (slurp p) true (catch Exception _ false))))

(defn ext
  "The filename extension for a collection, \"\" for reference collections."
  [collection]
  (if (text-collections collection) ".md" ""))

(defn path
  "Absolute path of the file backing user/collection/rkey."
  [user collection rkey]
  (str (wall-dir user) "/" collection "/" rkey (ext collection)))

(defn parse-addr
  "\"sam/posts/3ab…\" -> [user collection rkey]. A leading @ is tolerated so
   `wall like @sam/posts/…` reads naturally."
  [addr]
  (let [parts (str/split (str/replace addr #"^@" "") #"/")]
    (when-not (= 3 (count parts))
      (throw (ex-info (str "not an address (want user/collection/rkey): " addr) {})))
    parts))

(defn addr [user collection rkey] (str user "/" collection "/" rkey))

(defn read-doc
  "Read an address, returning {:front :body} plus :addr, or nil if it's gone.
   Dangling references are normal here — deletion is `rm` — so absence is a
   value, not an error."
  [a]
  (let [[user collection rkey] (parse-addr a)
        p (path user collection rkey)]
    (when (exists? p)
      (assoc (doc/parse (slurp p)) :addr a))))

(defn write-doc!
  "Write {:front :body} to user/collection/rkey, creating the collection dir."
  [user collection rkey d]
  (let [p (path user collection rkey)]
    (sh (str "mkdir -p " (pr-str (str (wall-dir user) "/" collection))))
    (spit p (doc/render d))
    p))

(defn rkeys
  "The rkeys in user/collection, in TID (i.e. chronological) order."
  [user collection]
  (let [dir (str (wall-dir user) "/" collection)]
    (when (cljc/dir?* dir)
      (->> (cljc/list-dir* dir)
           (map (fn [n] (str/replace n #"\.md$" "")))
           sort))))

(defn users
  "Everyone on the box with a .wall — the whole federation, such as it is."
  []
  (->> (cljc/list-dir* (or *homes* "/home"))
       (filter (fn [u] (cljc/dir?* (wall-dir u))))
       sort))

(defn docs
  "Every document in user/collection, oldest first, each with its :addr."
  [user collection]
  (keep (fn [k] (read-doc (addr user collection k))) (rkeys user collection)))

(defn find-like
  "The address of user's existing like of `subject`, or nil. Liking is a
   statement, not a counter, so `like` reuses this rather than piling up
   duplicates that a single `rm` would only half undo."
  [user subject]
  (->> (docs user "likes")
       (filter (fn [d] (= subject (get-in d [:front :subject]))))
       first
       :addr))

(defn index
  "Every entry in `collections` across `users`, as [rkey user collection],
   newest first.

   This is the whole payoff of TIDs: an rkey is a microsecond timestamp in an
   order-preserving encoding, so merging every user's directory listings is a
   plain sort on the filename. No dates parsed, no documents opened — we only
   read the ones the caller actually keeps, which is why this stays cheap as
   the box fills up."
  [users collections]
  (->> (for [u users, c collections, k (rkeys u c)] [k u c])
       (sort-by first)
       reverse))
