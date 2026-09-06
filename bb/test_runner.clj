;; Run porch_test.clj on Babashka (`make test-bb`) or jolt (`make test-jolt`).
;;
;; The test file was written for `cljc test`, so three things are papered over
;; here: cljc's global `sh` (interned into the namespaces that use it bare),
;; deftest/is coming from the ambient battery (clojure.test, `use`d into user),
;; and definition-order execution — clojure.test walks a hash map, but
;; write-and-read counts likes that likes-are-idempotent also writes, so the
;; vars are sorted by line to match cljc.
(require 'cljc)
(use 'clojure.test)
;; bb loads the test file into user, jolt into jolt.main.
(doseq [n '[user jolt.main porch.store]] (intern (create-ns n) 'sh cljc/sh))
;; bb's require skips a namespace create-ns already made, so it needs :reload-all;
;; jolt's doesn't, and its :reload-all path miscompiles `reverse` in store/index.
(if (System/getProperty "babashka.version")
  (require 'porch.store :reload-all)
  (require 'porch.store))
(load-file "porch_test.clj")
(let [vs (->> (all-ns) (mapcat (comp vals ns-interns)) (filter (comp :test meta)) (sort-by (comp :line meta)))
      r  (binding [*report-counters* (ref *initial-report-counters*)]
           (test-vars vs)
           @*report-counters*)
      bad (+ (:fail r) (:error r))]
  (println "Ran" (:test r) "tests:" (:pass r) "passed," bad "failed.")
  (System/exit (if (zero? bad) 0 1)))
