(ns wall
  "wall — the atilde command line tool.

   Run via bin/wall, which puts src/ on CLJC_PATH and hands the arguments here
   as *args*."
  (:require [clojure.string :as str]
            [babashka.cli :as cli]
            [wall.tid :as tid]
            [wall.doc :as doc]
            [wall.store :as store]
            [wall.fmt :as fmt]
            [wall.tui :as tui]))

;; ── commands ───────────────────────────────────────────────────────────────
;; Each takes the parsed {:opts :args} map and returns an exit code (nil = 0).

(defn- body-text
  "The body for a new document: the remaining argv joined, or stdin when there
   is none. An article isn't something you type as a shell argument, so
   `wall blog --title T < draft.md` has to work as well as `wall post hi`."
  [args]
  (let [t (if (seq args) (str/join " " args) (slurp "/dev/stdin"))]
    (when (str/blank? t) (throw (ex-info "nothing to write (no text, empty stdin)" {})))
    (if (str/ends-with? t "\n") t (str t "\n"))))

(defn- created
  "Write a new document into one of my collections and print its address."
  [collection front body]
  (let [rkey (tid/next-tid)
        user (store/me)]
    (store/write-doc! user collection rkey {:front front :body body})
    (println (store/addr user collection rkey))))

(defn cmd-tid
  "wall tid — print a fresh TID."
  [_]
  (println (tid/next-tid)))

(defn cmd-post
  "wall post <text>            — a top-level post
   wall post --reply <addr> <text> — a reply"
  [{:keys [opts args]}]
  (let [text (body-text args)
        parent (when-let [a (:reply opts)] (store/read-doc a))
        front (when (:reply opts)
                ;; Threading: the root is the parent's root if it has one,
                ;; otherwise the parent itself. One hop, never a walk.
                {:reply {:root (or (get-in parent [:front :reply :root])
                                   (:reply opts))
                         :parent (:reply opts)}})]
    (created "posts" front text)))

(defn cmd-ls
  "wall ls [user] [collection] — rkeys in chronological order."
  [{:keys [args]}]
  (let [[user collection] (case (count args)
                            0 [(store/me) "posts"]
                            1 [(first args) "posts"]
                            [(first args) (second args)])]
    (doseq [k (store/rkeys user collection)]
      (println (store/addr user collection k)))))

(defn cmd-cat
  "wall cat <addr> — show one document."
  [{:keys [args]}]
  (if-let [d (store/read-doc (first args))]
    (print (doc/render d))
    (do (binding [*out* *err*] (println "no such thing:" (first args))) 1)))

(defn cmd-users
  "wall users — everyone on the box with a wall."
  [_]
  (doseq [u (store/users)] (println u)))

(defn cmd-blog
  "wall blog --title <title> [text…] — a long post; body from argv or stdin."
  [{:keys [opts args]}]
  (let [title (:title opts)]
    (when (str/blank? (str title)) (throw (ex-info "blog needs --title" {})))
    (created "blog" {:title title} (body-text args))))

(defn cmd-link
  "wall link <url> [--title <t>] [why…] — recommend a link."
  [{:keys [opts args]}]
  (let [[url & why] args]
    (when (str/blank? (str url)) (throw (ex-info "link needs a url" {})))
    (created "links"
             (cond-> {:url url} (:title opts) (assoc :title (:title opts)))
             ;; the body is optional here — a bare URL is a valid recommendation
             (if (seq why) (str (str/join " " why) "\n") ""))))

(defn cmd-like
  "wall like <addr> — like any address; unlike is `rm` on the file it prints."
  [{:keys [args]}]
  (let [subject (first args)]
    (when (str/blank? (str subject)) (throw (ex-info "like needs an address" {})))
    (store/parse-addr subject)          ; reject a malformed address early
    ;; Liking twice is the same statement as liking once, so reuse the existing
    ;; like rather than stacking duplicates a later `rm` would only half undo.
    ;; We do NOT check that the subject exists: dangling references are normal
    ;; here (deletion is `rm`), and refusing to like something mid-delete would
    ;; be worse than carrying a dead pointer.
    (if-let [existing (store/find-like (store/me) subject)]
      (println existing)
      (created "likes" {:subject subject} ""))))

(defn cmd-feed
  "wall feed <url> [--title <t>] — publish that you read a feed."
  [{:keys [opts args]}]
  (let [url (first args)]
    (when (str/blank? (str url)) (throw (ex-info "feed needs a url" {})))
    (created "feeds"
             (cond-> {:url url} (:title opts) (assoc :title (:title opts)))
             "")))

(defn cmd-timeline
  "wall timeline [--limit N] [--user U] [--coll C] — everyone's wall, newest first."
  [{:keys [opts]}]
  ;; --limit, not -n: cljc's babashka.cli treats every short flag as a boolean
  ;; and never consumes the value after it, so `-n 2` would silently mean
  ;; {:n true} and drop the 2. Say so rather than quietly showing 20.
  (when (true? (:n opts)) (throw (ex-info "use --limit N (short flags take no value)" {})))
  (let [users (if (:user opts) [(:user opts)] (store/users))
        colls (if (:coll opts) [(:coll opts)] ["posts" "blog" "links"])
        n     (or (:limit opts) 20)]
    (doseq [[k u c] (take n (store/index users colls))]
      (let [d (store/read-doc (store/addr u c k))]
        (println (format "@%s · %s · %s" u (fmt/ago (tid/micros k)) (store/addr u c k)))
        (when-let [parent (get-in d [:front :reply :parent])]
          (println (str "  ↳ replying to " parent)))
        (println (str "  " (fmt/summarize c d 72)))
        (println)))))

(defn cmd-react
  "wall react <addr> <emoji> — react to any address with an emoji."
  [{:keys [args]}]
  (let [[subject emoji] args]
    (when (or (str/blank? (str subject)) (str/blank? (str emoji)))
      (throw (ex-info "react needs an address and an emoji" {})))
    (store/parse-addr subject)
    ;; same emoji twice is one statement; a different emoji is a new file
    (if-let [existing (store/find-reaction (store/me) subject emoji)]
      (println existing)
      (created "reactions" {:subject subject :emoji emoji} ""))))

(defn cmd-tui
  "wall tui — browse, post, reply, like and react in the terminal."
  [_]
  (tui/run!))

(def commands
  {"tid"      cmd-tid
   "timeline" cmd-timeline
   "post"  cmd-post
   "blog"  cmd-blog
   "link"  cmd-link
   "like"  cmd-like
   "react" cmd-react
   "feed"  cmd-feed
   "tui"   cmd-tui
   "ls"    cmd-ls
   "cat"   cmd-cat
   "users" cmd-users})

(def help-lines
  [["tui"      "                   browse, post, reply, like, react — interactively"]
   ["timeline" "[--limit N] [--user U] [--coll C]  everyone's wall, newest first"]
   ["post"  "<text…>            a short post; --reply <addr> to reply"]
   ["blog"  "--title T [text…]  a long post; body from argv or stdin"]
   ["link"  "<url> [why…]       recommend a link; --title T"]
   ["like"  "<addr>             like any address"]
   ["react" "<addr> <emoji>     react to any address"]
   ["feed"  "<url>              publish a feed you read; --title T"]
   ["ls"    "[user] [coll]      addresses, oldest first"]
   ["cat"   "<addr>             show one document"]
   ["users" "                   everyone on this box with a wall"]
   ["tid"   "                   print a fresh TID"]])

(defn usage []
  (println "wall — a social filesystem for this box")
  (println)
  (doseq [[name blurb] help-lines]
    (println (format "  wall %-8s %s" name blurb))))

;; ── entry ──────────────────────────────────────────────────────────────────

(defn -main [argv]
  (let [{:keys [opts args]} (cli/parse-args argv {:coerce {:limit :int}})
        [cmd & rest'] args]
    (cond
      (or (nil? cmd) (:help opts)) (do (usage) 0)
      (commands cmd) (or (try
                           ((commands cmd) {:opts opts :args (vec rest')})
                           (catch Exception e
                             (binding [*out* *err*] (println "wall:" (ex-message e)))
                             1))
                         0)
      :else (do (binding [*out* *err*] (println "wall: unknown command:" cmd))
                (usage)
                2))))

(System/exit (-main (vec (or *args* []))))
