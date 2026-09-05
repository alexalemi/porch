(ns porch.fmt
  "One-line renderings shared by the CLI timeline and the TUI."
  (:require [clojure.string :as str]))

(defn ago
  "Coarse relative time. Timelines are read at a glance, and nobody needs
   seconds of precision on a week-old post — but past a week the actual date
   is more use than \"63d\", so it switches."
  [us]
  (let [s (quot (- (cljc/now-us*) us) 1000000)]
    (cond
      (< s 60)     "just now"
      (< s 3600)   (str (quot s 60) "m ago")
      (< s 86400)  (str (quot s 3600) "h ago")
      (< s 604800) (str (quot s 86400) "d ago")
      :else (subs (str (java.time.Instant/ofEpochMilli (quot us 1000))) 0 10))))

(defn clip
  "s cut to at most n codepoints, with an ellipsis when it was longer. Counts
   codepoints, not columns: a wide emoji still takes two cells, so callers
   leave a little slack rather than pretend to know the terminal's font."
  [s n]
  (let [s (or s "")]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn first-line [s n]
  (clip (str/trim (or (first (str/split-lines (or s ""))) "")) n))

(defn summarize
  "One line describing an entry, by collection. Each collection puts something
   different in the front matter, so a generic dump would bury the useful bit."
  [collection {:keys [front body]} width]
  (case collection
    "blog"      (str "“" (:title front) "” " (first-line body (max 10 (- width 4 (count (str (:title front)))))))
    "links"     (clip (str (:url front) (when-let [t (:title front)] (str " — " t))) width)
    "likes"     (str "♥ " (:subject front))
    "reactions" (str (:emoji front) " " (:subject front))
    "feeds"     (clip (str "⊙ " (:url front) (when-let [t (:title front)] (str " — " t))) width)
    (first-line body width)))

(defn tally-line
  "\"♥3 🔥2 👀1\" for one subject's tally entry, or nil when nobody reacted."
  [{:keys [likes reactions]}]
  (let [parts (concat (when (seq likes) [(str "♥" (count likes))])
                      (->> reactions (map second) frequencies (sort-by (comp - second))
                           (map (fn [[e n]] (str e n)))))]
    (when (seq parts) (str/join " " parts))))
