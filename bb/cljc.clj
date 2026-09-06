(ns cljc
  "Babashka (and jolt) stand-ins for the cljc natives porch uses.

   cljc defines these as root bindings whose names happen to contain a slash,
   so `cljc/env*` in the porch sources resolves here as long as this namespace
   is loaded first (bin/porch-bb does that). Each fn keeps the exact contract
   documented next to the native in cljc.c, including the nil/false cases."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.process :as p]))

;; ---- environment & process -------------------------------------------------

(defn env* [k] (System/getenv k))

(defn getpid
  "ProcessHandle on bb; jolt has no JDK class behind it, so fall back to
   /proc, which is what the tilde box is running anyway."
  []
  (try (.pid (java.lang.ProcessHandle/current))
       (catch Exception _
         (parse-long (re-find #"\d+" (slurp "/proc/self/stat"))))))

(defn now-us*
  "Wall-clock microseconds since the epoch. Instant/now is microsecond-precise
   on Linux JDKs, which is what TIDs assume."
  []
  (let [i (java.time.Instant/now)]
    (+ (* (.getEpochSecond i) 1000000) (quot (.getNano i) 1000))))

;; ---- filesystem --------------------------------------------------------------

(defn dir?* [path] (.isDirectory (io/file path)))

(defn list-dir*
  "Entry names (no . or ..) as a vector, or nil when path isn't a readable dir."
  [path]
  (some->> (.listFiles (io/file path)) (mapv #(.getName ^java.io.File %))))

(defn sh
  "cljc's global (sh \"cmd\") => {:exit n :out \"stdout+stderr\"}. Runs through
   /bin/sh exactly as the native does, so quoting rules match."
  [cmd]
  (let [r (p/sh ["sh" "-c" cmd] {:err :out})]
    {:exit (:exit r) :out (:out r)}))

;; ---- terminal ----------------------------------------------------------------
;; All three go through stty on /dev/tty, not stdin, so they behave the same
;; whether or not stdin is redirected, and need nothing native.

(defn- stty
  "Run stty against the controlling terminal; nil if there isn't one."
  [& args]
  (try
    (let [r (p/sh ["sh" "-c" (str "stty " (apply str (interpose " " args)) " < /dev/tty")]
                  {:err :string})]
      (when (zero? (:exit r)) (:out r)))
    (catch Exception _ nil)))

(defonce ^:private saved (atom nil))    ; `stty -g` string to restore
(defonce ^:private hooked (atom false))

(defn raw-mode*
  "Enter (true) or leave (false) raw mode. false when there is no tty. A
   shutdown hook restores the terminal even if the script dies mid-screen,
   like cljc's atexit hook."
  [on?]
  (if on?
    (when-let [orig (some-> (stty "-g") str/trim)]
      (when-not @saved (reset! saved orig))
      (when (compare-and-set! hooked false true)
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn [] (when-let [s @saved] (stty s))))))
      (boolean (stty "raw" "-echo")))
    (when-let [s @saved]
      (reset! saved nil)
      (boolean (stty s)))))

(defn term-size*
  "[rows cols], [24 80] when unknown."
  []
  (or (when-let [out (stty "size")]
        (let [[r c] (map parse-long (re-seq #"\d+" out))]
          (when (and r c (pos? r) (pos? c)) [r c])))
      [24 80]))

(defn- utf8-len [b]
  (cond (< b 0x80) 1 (< b 0xE0) 2 (< b 0xF0) 3 :else 4))

(defn read-key*
  "Block for one keypress; return its bytes as a string: one UTF-8 character
   or a whole escape sequence. nil on EOF. A lone ESC with nothing behind it
   within 30ms is a plain Escape."
  []
  (let [in System/in
        b  (.read in)]
    (when-not (neg? b)
      (let [buf (java.io.ByteArrayOutputStream.)]
        (.write buf b)
        (if (= b 27)
          (do (Thread/sleep 30)
              (while (pos? (.available in)) (.write buf (.read in))))
          (dotimes [_ (dec (utf8-len b))]
            (let [c (.read in)] (when-not (neg? c) (.write buf c)))))
        (String. (.toByteArray buf) "UTF-8")))))
