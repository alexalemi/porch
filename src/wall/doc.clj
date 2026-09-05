(ns wall.doc
  "The on-disk file format: an optional YAML front matter fence, then markdown.

     ---
     title: Hello
     ---
     body text

   The fence is the ONLY way to carry metadata. A file with no `---` fence is
   all body, full stop — we never sniff a bare file to see whether it happens to
   parse as YAML. That costs `likes/` and `feeds/` a fence they'd otherwise not
   need, and buys a rule with no ambiguous cases: a post reading
   `Reading: notes on Simmel` is a post, and parse/render round-trip exactly."
  (:require [clojure.string :as str]
            [clj-yaml.core :as yaml]))

(defn- fenced
  "Split a `---`-fenced file into [front-yaml body], or nil if it isn't fenced."
  [s]
  (when (str/starts-with? s "---\n")
    (let [rest' (subs s 4)
          idx (str/index-of rest' "\n---\n")]
      (cond
        idx [(subs rest' 0 idx) (subs rest' (+ idx 5))]
        ;; a file that is nothing but front matter ends at the closing fence
        (str/ends-with? rest' "\n---") [(subs rest' 0 (- (count rest') 4)) ""]
        :else nil))))

(defn- yaml-map
  "Parse s as YAML, returning a map, or nil if it isn't one."
  [s]
  (try
    (let [v (yaml/parse-string s)]
      (when (map? v) v))
    (catch Exception _ nil)))

(defn parse
  "File contents -> {:front <map> :body <string>}."
  [s]
  (let [[front body] (fenced s)
        front (some-> front yaml-map)]
    (if front
      {:front front :body body}
      ;; No fence — or a fence whose contents aren't a YAML map, which is far
      ;; more likely to be a post that opens with a horizontal rule than a
      ;; corrupt header. Either way it's all body; nothing is ever dropped.
      {:front {} :body s})))

(defn render
  "{:front <map> :body <string>} -> file contents. A doc with no front matter is
   written bare, so a plain post is just its text — `cat` stays pleasant."
  [{:keys [front body]}]
  (let [body (or body "")]
    (if (seq front)
      (str "---\n" (str/trim (yaml/generate-string front)) "\n---\n" body)
      body)))
