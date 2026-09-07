(ns porch.web
  "The one place porch touches the network: fetching a page title for
   `porch link`. Shells out to curl through `sh` rather than an HTTP library,
   for the same reason the store avoids the FFI — nothing to compile, nothing
   to dlopen, and curl is on every box."
  (:require [clojure.string :as str]))

(def ^:private entities
  {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "})

(defn decode-entities
  "Unescape the handful of entities a <title> is likely to carry, plus numeric
   ones. Unknown named entities are left alone rather than dropped."
  [s]
  (str/replace s #"&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);"
               (fn [[whole e]]
                 (cond
                   (str/starts-with? e "#x") (str (char (Long/parseLong (subs e 2) 16)))
                   (str/starts-with? e "#")  (str (char (parse-long (subs e 1))))
                   :else (get entities e whole)))))

(defn title-of
  "The <title> of an HTML string, whitespace collapsed, or nil. Case-folded
   by character class because cljc's regex has no (?i)."
  [html]
  (when-let [[_ t] (re-find #"<[Tt][Ii][Tt][Ll][Ee][^>]*>([^<]*)</[Tt][Ii][Tt][Ll][Ee]" (or html ""))]
    (let [t (-> t decode-entities (str/replace #"\s+" " ") str/trim)]
      (when-not (str/blank? t) t))))

(defn fetch-title
  "The title of the page at url, or nil for anything that goes wrong: no
   network, a timeout, a PDF, no <title>. A bare URL is a valid link, so the
   caller shouldn't need to care why."
  [url]
  (try
    (let [{:keys [out]} (sh (str "curl -sL --max-time 8 --max-filesize 262144 "
                                 (pr-str url) " 2>/dev/null | head -c 65536"))]
      (title-of out))
    (catch Exception _ nil)))
