(ns porch.text
  "Things parsed out of a body rather than declared in front matter.

   Mentions (@sam) and tags (#cnc) live in the prose, so a post is tagged the
   way you'd tag it talking to someone, and no writer has to remember a field.
   The cost is that the rules for what counts have to be spelled out here."
  (:require [clojure.string :as str]))

;; cljc's regex has no lookbehind and no \b, so \"not preceded by a word char
;; or a slash\" is written as an alternation that consumes the preceding
;; character; only the group is kept. The slash is what keeps http://x/#frag
;; from being a tag and me@x.org from being a mention.

(def ^:private tag-re     #"(?:^|[^\w/&])#([A-Za-z][\w-]*)")
(def ^:private mention-re #"(?:^|[^\w/.])@([A-Za-z_][A-Za-z0-9_-]*)")

(defn tags
  "Distinct tags in s, in order of first appearance, lowercased: #CNC and
   #cnc are one tag. A tag starts with a letter, so #1 and #2024 are prose."
  [s]
  (->> (re-seq tag-re (or s "")) (map (comp str/lower-case second)) distinct vec))

(defn mentions
  "Distinct logins mentioned in s, in order. Kept as written — a login is a
   filename, and those are case-sensitive."
  [s]
  (->> (re-seq mention-re (or s "")) (map second) distinct vec))

(defn strip-tag     [t] (str/replace (str t) #"^#" ""))
(defn strip-mention [u] (str/replace (str u) #"^@" ""))
