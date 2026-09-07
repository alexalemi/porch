(ns porch.tui
  "A terminal front end for the porch: one scrolling timeline you can post,
   reply, like and react from.

   Raw keys come from cljc/read-key* (a native, so the bundled binary stays
   free of the FFI), everything on screen is plain ANSI. The screen is redrawn
   whole on every keypress — the list is a few dozen lines, so diffing would
   buy nothing but bugs.

   Text entry deliberately drops out of raw mode and uses read-line: the line
   discipline already does backspace, ^W, ^U and paste properly, and a post is
   one line long."
  (:require [clojure.string :as str]
            [porch.tid :as tid]
            [porch.doc :as doc]
            [porch.store :as store]
            [porch.fmt :as fmt]
            [porch.text :as text]
            [porch.web :as web]))

;; ── terminal ───────────────────────────────────────────────────────────────

(def ^:private ESC (str (char 27)))
(defn- csi [& parts] (str ESC "[" (apply str parts)))
(def ^:private clear    (csi "2J"))
(def ^:private home     (csi "H"))
(def ^:private reset    (csi "0m"))
(def ^:private bold     (csi "1m"))
(def ^:private dim      (csi "2m"))
(def ^:private reverse' (csi "7m"))
(def ^:private alt-on   (csi "?1049h"))
(def ^:private alt-off  (csi "?1049l"))
(def ^:private cursor-hide (csi "?25l"))
(def ^:private cursor-show (csi "?25h"))

(defn- out [& xs] (print (apply str xs)) (flush))

;; Key names for the byte strings read-key* hands back. Anything not listed
;; comes through as itself, so plain letters dispatch on the letter.
(def ^:private key-names
  {(str (char 13)) :enter (str (char 10)) :enter
   (csi "A") :up   (csi "B") :down  (csi "C") :right (csi "D") :left
   (str ESC "OA") :up   (str ESC "OB") :down
   (csi "5~") :pgup (csi "6~") :pgdn
   (csi "H") :home (csi "F") :end (csi "1~") :home (csi "4~") :end
   ESC :esc (str (char 3)) :ctrl-c (str (char 4)) :ctrl-d (str (char 127)) :backspace})

(defn- read-key []
  (let [k (cljc/read-key*)]
    (if (nil? k) :ctrl-d (get key-names k k))))

(defn- prompt
  "Ask for one line of text with the terminal in cooked mode: proper editing
   for free, and the user sees what they type. nil when they give up (empty
   line or ^D)."
  [label]
  (cljc/raw-mode* false)
  (let [[rows _] (cljc/term-size*)]
    (out (csi rows ";1H") (csi "2K") cursor-show bold label reset " ")
    (let [line (read-line)]
      (out cursor-hide)
      (cljc/raw-mode* true)
      (when-not (str/blank? (str line)) (str/trim line)))))

;; ── model ──────────────────────────────────────────────────────────────────

(def ^:private all-colls ["posts" "blog" "links" "likes" "reactions" "feeds"])
(defn- mentions-me? [d] (some #{(store/me)} (text/mentions (:body d))))

;; [name collections predicate?]. A predicate has to open every doc in the
;; index, which the plain filters never do — fine for one user's mentions on
;; a tilde box, not something to add casually.
(def ^:private filters [["all" ["posts" "blog" "links"]]
                        ["posts" ["posts"]]
                        ["blog" ["blog"]]
                        ["links" ["links"]]
                        ["mentions" ["posts" "blog" "links"] mentions-me?]
                        ["everything" all-colls]])

(defn- load-state
  "Read the box. Entries are [rkey user coll] newest first; docs are read
   lazily into a cache as they scroll into view; the tally is read eagerly
   because every row shows its counts."
  [state]
  (let [users (store/users)
        [_ colls pred] (nth filters (:filter state))
        entries (vec (cond->> (store/index users colls)
                       pred (filter (fn [[k u c]] (some-> (store/read-doc (store/addr u c k)) pred)))))]
    (assoc state
           :users users
           :entries entries
           :docs {}
           :tally (store/tally users)
           :cursor (min (:cursor state) (max 0 (dec (count entries)))))))

(defn- doc-at [state i]
  (let [[k u c] (nth (:entries state) i)]
    (or (get-in state [:docs i])
        (store/read-doc (store/addr u c k)))))

(defn- ensure-docs
  "Cache the docs for rows [from, to) — the visible window."
  [state from to]
  (reduce (fn [st i]
            (if (get-in st [:docs i]) st (assoc-in st [:docs i] (doc-at st i))))
          state
          (range from (min to (count (:entries state))))))

(defn- selected-addr [state]
  (when (seq (:entries state))
    (let [[k u c] (nth (:entries state) (:cursor state))] (store/addr u c k))))

;; ── view ───────────────────────────────────────────────────────────────────

(defn- row-line
  "One list row. Layout: `@user  ago  summary  [♥3 🔥1]`, ↳ on replies."
  [state i width]
  (let [[k u c] (nth (:entries state) i)
        d (get-in state [:docs i])
        addr (store/addr u c k)
        counts (fmt/tally-line (get (:tally state) addr))
        reply? (get-in d [:front :reply])
        head (format "%-8s %-9s " (fmt/clip (str "@" u) 8) (fmt/ago (tid/micros k)))
        tail (if counts (str "  " counts) "")
        room (max 8 (- width (count head) (count tail) 4))
        body (str (when reply? "↳ ") (when (get-in d [:front :edited]) "✎ ") (fmt/summarize c d room))]
    (str head (fmt/clip body room) tail)))

(defn- draw [state]
  (let [[rows cols] (cljc/term-size*)
        list-rows (- rows 3)
        top (:top state)
        state (ensure-docs state top (+ top list-rows))
        n (count (:entries state))
        [fname _] (nth filters (:filter state))]
    (out clear home
         bold "porch" reset dim "  " (count (:users state)) " users · " n " entries · "
         fname reset "\r\n")
    (doseq [i (range top (min n (+ top list-rows)))]
      (let [line (fmt/clip (row-line state i cols) (- cols 1))]
        (out (if (= i (:cursor state)) (str reverse' line reset) line) "\r\n")))
    (when (zero? n) (out dim "  nothing here yet — press p to post" reset "\r\n"))
    ;; the footer is clipped too: a wrapped hint line would scroll the list
    (out (csi rows ";1H") (csi "2K")
         (if-let [m (:msg state)]
           (str bold (fmt/clip m (dec cols)) reset)
           (str dim (fmt/clip "j/k move  ⏎ view  p post  r reply  l like  e react  L link  t filter  R refresh  ? help  q quit" (dec cols)) reset)))
    state))

(defn- view-doc
  "Full-screen view of one document, with its reactions and any replies."
  [state]
  (let [[rows cols] (cljc/term-size*)
        addr (selected-addr state)
        d (store/read-doc addr)
        [user _ rkey] (store/parse-addr addr)
        replies (->> (:entries state)
                     (filter (fn [[_ _ c]] (= "posts" c)))
                     (keep (fn [[k u c]]
                             (let [rd (store/read-doc (store/addr u c k))]
                               (when (= addr (get-in rd [:front :reply :parent])) [u rd])))))]
    (out clear home bold "@" user reset dim "  " (fmt/ago (tid/micros rkey)) "  " addr reset "\r\n")
    (when-let [t (fmt/tally-line (get (:tally state) addr))] (out dim t reset "\r\n"))
    (out "\r\n")
    (doseq [line (str/split-lines (doc/render d))]
      (out (fmt/clip line (dec cols)) "\r\n"))
    (when (seq replies)
      (out "\r\n" bold "replies" reset "\r\n")
      (doseq [[u rd] replies]
        (out "  " bold "@" u reset "  " (fmt/clip (fmt/first-line (:body rd) cols) (- cols 14)) "\r\n")))
    (out (csi rows ";1H") dim "any key to go back" reset)
    (read-key)
    state))

(def ^:private help-text
  ["j / ↓      down            k / ↑      up"
   "g / G      top / bottom    PgUp/PgDn  page"
   "⏎          view the entry, its reactions and replies"
   "p          new post        r          reply to the selected entry"
   "l          like it         e          react with an emoji"
   "L          recommend a link"
   "t          cycle the filter (all → posts → blog → links → mentions → everything)"
   "R          re-read the box (someone else may have posted)"
   "q / Esc    quit"
   ""
   "Everything you write lands as a file under ~/.porch; `rm` undoes it."])

(defn- show-help [state]
  (let [[rows _] (cljc/term-size*)]
    (out clear home bold "porch — keys" reset "\r\n\r\n")
    (doseq [l help-text] (out "  " l "\r\n"))
    (out (csi rows ";1H") dim "any key to go back" reset)
    (read-key)
    state))

;; ── actions ────────────────────────────────────────────────────────────────

(defn- write! [state coll front body]
  (let [rkey (tid/next-tid)]
    (store/write-doc! (store/me) coll rkey {:front front :body body})
    (-> state load-state (assoc :msg (str "wrote " (store/addr (store/me) coll rkey))))))

(defn- post! [state]
  (if-let [text (prompt "post:")]
    (write! state "posts" nil (str text "\n"))
    (assoc state :msg "cancelled")))

(defn- reply! [state]
  (if-let [parent (selected-addr state)]
    (if-let [text (prompt (str "reply to " parent ":"))]
      (let [pd (store/read-doc parent)]
        (write! state "posts"
                {:reply {:root (or (get-in pd [:front :reply :root]) parent) :parent parent}}
                (str text "\n")))
      (assoc state :msg "cancelled"))
    state))

(defn- like! [state]
  (if-let [subject (selected-addr state)]
    (if-let [existing (store/find-like (store/me) subject)]
      (assoc state :msg (str "already liked: " existing))
      (write! state "likes" {:subject subject} ""))
    state))

(def ^:private quick-emoji ["👍" "❤️" "😂" "🔥" "🎉" "👀"])

(defn- react! [state]
  (if-let [subject (selected-addr state)]
    (let [menu (str/join " " (map-indexed (fn [i e] (str (inc i) e)) quick-emoji))
          in (prompt (str "react " menu " or type one:"))]
      (if (nil? in)
        (assoc state :msg "cancelled")
        (let [emoji (if (re-matches #"[1-6]" in) (nth quick-emoji (dec (parse-long in))) in)]
          (if-let [existing (store/find-reaction (store/me) subject emoji)]
            (assoc state :msg (str "already reacted " emoji ": " existing))
            (write! state "reactions" {:subject subject :emoji emoji} "")))))
    state))

(defn- link! [state]
  (if-let [url (prompt "link url:")]
    (let [title (or (prompt "title (optional, blank to fetch):") (web/fetch-title url))
          why (prompt "why (optional):")]
      (write! state "links" (cond-> {:url url} title (assoc :title title))
              (if why (str why "\n") "")))
    (assoc state :msg "cancelled")))

;; ── loop ───────────────────────────────────────────────────────────────────

(defn- move [state delta]
  (let [n (count (:entries state))]
    (if (zero? n)
      state
      (let [[rows _] (cljc/term-size*)
            page (- rows 3)
            cur (-> (+ (:cursor state) delta) (max 0) (min (dec n)))
            top (:top state)
            top (cond (< cur top) cur
                      (>= cur (+ top page)) (inc (- cur page))
                      :else top)]
        (assoc state :cursor cur :top top)))))

(defn- step [state key]
  (let [state (dissoc state :msg)
        n (count (:entries state))]
    (case key
      (:down "j")  (move state 1)
      (:up "k")    (move state -1)
      :pgdn        (move state (- (first (cljc/term-size*)) 3))
      :pgup        (move state (- 3 (first (cljc/term-size*))))
      ("g" :home)  (move state (- n))
      ("G" :end)   (move state n)
      (:enter "v") (if (pos? n) (view-doc state) state)
      "p"          (post! state)
      "r"          (reply! state)
      "l"          (like! state)
      "e"          (react! state)
      "L"          (link! state)
      "t"          (-> state (update :filter (fn [f] (mod (inc f) (count filters)))) load-state)
      "R"          (-> state load-state (assoc :msg "refreshed"))
      "?"          (show-help state)
      ("q" :esc :ctrl-c :ctrl-d) (assoc state :quit true)
      state)))

(defn run!
  "Take over the terminal until q. Always hands it back — the finally runs on
   any exception, and cljc's atexit hook covers a hard exit."
  []
  (when-not (cljc/raw-mode* true)
    (throw (ex-info "porch tui needs a terminal (stdin isn't a tty)" {})))
  (out alt-on cursor-hide)
  (try
    (loop [state (load-state {:cursor 0 :top 0 :filter 0})]
      (let [state (draw state)]
        (when-not (:quit state)
          (recur (step state (read-key))))))
    (finally
      (out cursor-show alt-off)
      (cljc/raw-mode* false))))
