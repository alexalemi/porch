(ns porch
  "porch — the atilde command line tool.

   Run via bin/porch, which puts src/ on CLJC_PATH and hands the arguments here
   as *args*."
  (:require [clojure.string :as str]
            [babashka.cli :as cli]
            [porch.tid :as tid]
            [porch.doc :as doc]
            [porch.store :as store]
            [porch.fmt :as fmt]
            [porch.text :as text]
            [porch.web :as web]
            [porch.tui :as tui]))

;; ── commands ───────────────────────────────────────────────────────────────
;; Each takes the parsed {:opts :args} map and returns an exit code (nil = 0).

(defn- body-text
  "The body for a new document: the remaining argv joined, or stdin when there
   is none. An article isn't something you type as a shell argument, so
   `porch blog --title T < draft.md` has to work as well as `porch post hi`."
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
  "porch tid — print a fresh TID."
  [_]
  (println (tid/next-tid)))

(defn cmd-post
  "porch post <text>            — a top-level post
   porch post --reply <addr> <text> — a reply"
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
  "porch ls [user] [collection] — rkeys in chronological order."
  [{:keys [args]}]
  (let [[user collection] (case (count args)
                            0 [(store/me) "posts"]
                            1 [(first args) "posts"]
                            [(first args) (second args)])]
    (doseq [k (store/rkeys user collection)]
      (println (store/addr user collection k)))))

(defn cmd-cat
  "porch cat <addr> — show one document."
  [{:keys [args]}]
  (if-let [d (store/read-doc (first args))]
    (print (doc/render d))
    (do (binding [*out* *err*] (println "no such thing:" (first args))) 1)))

(defn- show-profile
  "Render a profile for the terminal: name and login, links, then the bio."
  [user {:keys [front body]}]
  (println (str (:name front) " (@" user ")"))
  (doseq [l (:links front)] (println (str "  " l)))
  (when-not (str/blank? (str body)) (println) (print body)))

(defn- next-profile
  "The profile to write: the one on disk (nil if none) with only what you
   passed changed. A merge, not a replace — `porch profile --name Sam` is a
   rename, and a rename shouldn't cost you your bio. --link adds to the list
   (deduplicated, order kept); --no-links clears it; argv, when present,
   replaces the bio."
  [existing {:keys [name link no-links]} args]
  (let [front (:front existing)
        front (cond-> front
                name           (assoc :name name)
                no-links       (dissoc :links)
                (seq link)     (update :links (fn [ls] (vec (distinct (concat ls link))))))]
    (when (str/blank? (str (:name front)))
      (throw (ex-info "profile needs a --name" {})))
    {:front front
     :body (if (seq args) (body-text args) (or (:body existing) ""))}))

(defn cmd-profile
  "porch profile [user]                     — show a profile (yours by default)
   porch profile --name N [--link URL…] [bio…] — write yours"
  [{:keys [opts args]}]
  (let [editing? (some opts [:name :link :no-links])
        user (if (and (not editing?) (seq args)) (first args) (store/me))]
    (if editing?
      (do (store/write-profile! (store/me) (next-profile (store/read-profile (store/me)) opts args))
          (println (store/profile-path (store/me))))
      (if-let [pr (store/read-profile user)]
        (show-profile user pr)
        (do (binding [*out* *err*] (println "no profile for" user)) 1)))))

(defn cmd-users
  "porch users — everyone on the box with a porch."
  [_]
  (doseq [u (store/users)]
    (let [n (store/display-name u)]
      (println (if (= n u) u (format "%-12s %s" u n))))))

(defn cmd-blog
  "porch blog --title <title> [text…] — a long post; body from argv or stdin."
  [{:keys [opts args]}]
  (let [title (:title opts)]
    (when (str/blank? (str title)) (throw (ex-info "blog needs --title" {})))
    (created "blog" {:title title} (body-text args))))

(defn cmd-link
  "porch link <url> [--title <t>] [why…] — recommend a link."
  [{:keys [opts args]}]
  (let [[url & why] args
        _ (when (str/blank? (str url)) (throw (ex-info "link needs a url" {})))
        ;; No --title? Ask the page. --no-fetch keeps it off the network, and a
        ;; fetch that comes back empty is not an error: a bare URL is a link.
        title (or (:title opts)
                  (when-not (:no-fetch opts)
                    (or (web/fetch-title url)
                        (do (binding [*out* *err*] (println "porch: no title found; --title T to set one"))
                            nil))))]
    (created "links"
             (cond-> {:url url} title (assoc :title title))
             ;; the body is optional here — a bare URL is a valid recommendation
             (if (seq why) (str (str/join " " why) "\n") ""))))

(defn cmd-like
  "porch like <addr> — like any address; unlike is `rm` on the file it prints."
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
  "porch feed <url> [--title <t>] — publish that you read a feed."
  [{:keys [opts args]}]
  (let [url (first args)]
    (when (str/blank? (str url)) (throw (ex-info "feed needs a url" {})))
    (created "feeds"
             (cond-> {:url url} (:title opts) (assoc :title (:title opts)))
             "")))

(defn cmd-timeline
  "porch timeline [--limit N] [--user U] [--coll C] — everyone's porch, newest first."
  [{:keys [opts]}]
  ;; --limit, not -n: cljc's babashka.cli treats every short flag as a boolean
  ;; and never consumes the value after it, so `-n 2` would silently mean
  ;; {:n true} and drop the 2. Say so rather than quietly showing 20.
  (when (true? (:n opts)) (throw (ex-info "use --limit N (short flags take no value)" {})))
  (let [users (if (:user opts) [(:user opts)] (store/users))
        colls (if (:coll opts) [(:coll opts)] ["posts" "blog" "links"])
        n     (or (:limit opts) 20)
        tag     (some-> (:tag opts) text/strip-tag str/lower-case)
        mention (some-> (:mention opts) text/strip-mention)
        keep?   (fn [d] (and d
                             (or (nil? tag) (some #{tag} (text/tags (:body d))))
                             (or (nil? mention) (some #{mention} (text/mentions (:body d))))))
        ;; The index is lazy and cheap; --tag and --mention have to open each
        ;; doc, so filter after the merge and stop as soon as n have passed.
        entries (->> (store/index users colls)
                     (map (fn [[k u c]] [k u c (store/read-doc (store/addr u c k))]))
                     (filter (fn [[_ _ _ d]] (keep? d)))
                     (take n))]
    (doseq [[k u c d] entries]
      (let []
        (println (format "@%s · %s%s · %s" u (fmt/ago (tid/micros k)) (fmt/edited-mark d) (store/addr u c k)))
        (when-let [parent (get-in d [:front :reply :parent])]
          (println (str "  ↳ replying to " parent)))
        (println (str "  " (fmt/summarize c d 72)))
        (println)))))

(defn cmd-mentions
  "porch mentions [user] — posts that mention you (or user), newest first."
  [{:keys [opts args]}]
  (cmd-timeline {:opts (assoc opts :mention (or (first args) (store/me)))}))

(defn cmd-edit
  "porch edit <addr> [text…] — replace the body of one of your own text
   documents, from argv or stdin, and stamp `edited:`. Front matter (title,
   url, reply threading) is kept; only the words change."
  [{:keys [args]}]
  (let [[a & words] args
        _ (when (str/blank? (str a)) (throw (ex-info "edit needs an address" {})))
        [user coll rkey] (store/parse-addr a)]
    (when-not (= user (store/me)) (throw (ex-info (str "not yours to edit: " a) {})))
    (when-not (store/text-collections coll) (throw (ex-info (str coll " has no body to edit") {})))
    (let [d (or (store/read-doc a) (throw (ex-info (str "no such thing: " a) {})))]
      (store/write-doc! user coll rkey
                        {:front (assoc (:front d) :edited (fmt/iso-now))
                         :body (body-text (vec words))})
      (println a))))

(defn cmd-react
  "porch react <addr> <emoji> — react to any address with an emoji."
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
  "porch tui — browse, post, reply, like and react in the terminal."
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
   "profile" cmd-profile
   "edit"    cmd-edit
   "mentions" cmd-mentions
   "tui"   cmd-tui
   "ls"    cmd-ls
   "cat"   cmd-cat
   "users" cmd-users})

(def help-lines
  [["tui"      "                   browse, post, reply, like, react — interactively"]
   ["timeline" "[--limit N] [--user U] [--coll C] [--tag T] [--mention U]  everyone's porch"]
   ["mentions" "[user]           posts that mention you"]
   ["post"  "<text…>            a short post; --reply <addr> to reply"]
   ["edit"  "<addr> [text…]     replace the body of your own post; stamps edited:"]
   ["blog"  "--title T [text…]  a long post; body from argv or stdin"]
   ["link"  "<url> [why…]       recommend a link; --title T, else fetched (--no-fetch)"]
   ["like"  "<addr>             like any address"]
   ["react" "<addr> <emoji>     react to any address"]
   ["feed"  "<url>              publish a feed you read; --title T"]
   ["profile" "[user]           show a profile; --name N [--link URL] [bio…] writes yours"]
   ["ls"    "[user] [coll]      addresses, oldest first"]
   ["cat"   "<addr>             show one document"]
   ["users" "                   everyone on this box with a porch"]
   ["tid"   "                   print a fresh TID"]])

(defn usage []
  (println "porch — a social filesystem for this box")
  (println)
  (doseq [[name blurb] help-lines]
    (println (format "  porch %-8s %s" name blurb))))

;; ── entry ──────────────────────────────────────────────────────────────────

(defn -main [argv]
  (let [{:keys [opts args]} (cli/parse-args argv {:coerce {:limit :int :link []}})
        [cmd & rest'] args]
    (cond
      (or (nil? cmd) (:help opts)) (do (usage) 0)
      (commands cmd) (or (try
                           ((commands cmd) {:opts opts :args (vec rest')})
                           (catch Exception e
                             (binding [*out* *err*] (println "porch:" (ex-message e)))
                             1))
                         0)
      :else (do (binding [*out* *err*] (println "porch: unknown command:" cmd))
                (usage)
                2))))

(System/exit (-main (vec (or *args* []))))
