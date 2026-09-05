(ns porch.tid
  "TIDs: 13-char sortable timestamp identifiers.

   64 bits, base32-encoded in an alphabet whose ASCII order matches its digit
   order, so alphabetical order IS chronological order and `ls` gives you a
   timeline for free:

     bit 63     always 0 (keeps every TID 13 chars and positive)
     bits 62-10 microseconds since the Unix epoch (53 bits: good past year 2255)
     bits 9-0   clock id — a per-process random tiebreaker so two users (or two
                shells) minting a TID in the same microsecond don't collide")

(def alphabet "234567abcdefghijklmnopqrstuvwxyz")

(def ^:private index
  (into {} (map-indexed (fn [i c] [c i]) alphabet)))

(defn encode
  "63-bit integer -> 13 base32-sortable characters (big-endian, zero-padded)."
  [n]
  (apply str (map (fn [shift] (nth alphabet (bit-and (bit-shift-right n shift) 31)))
                  (range 60 -1 -5))))

(defn decode
  "13 base32-sortable characters -> the integer they encode."
  [s]
  (reduce (fn [acc c] (+ (* acc 32) (or (index c)
                                        (throw (ex-info (str "bad TID char: " c)
                                                        {:tid s})))))
          0 s))

(defn micros
  "The creation time of a TID, in microseconds since the epoch."
  [tid]
  (bit-shift-right (decode tid) 10))

(defn tid?
  [s]
  (and (string? s) (= 13 (count s)) (every? index s)))

;; The clock id is drawn once per process: every TID this run mints shares it,
;; so ordering within a process is decided purely by the timestamp.
(def ^:private clock-id (bit-and (cljc/getpid) 1023))

;; The last microsecond stamp we handed out, so we can notice collisions.
(def ^:private last-us (atom 0))

(def ^:private now cljc/now-us*)

(defn next-tid
  "Mint a fresh TID."
  []
  (encode 
    (bit-or clock-id (bit-shift-left (swap! last-us (fn [x] (max (now) (inc x)))) 10))))


(comment
  (next-tid))
