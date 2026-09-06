Pure-Clojure copies of the two libraries porch needs that jolt doesn't ship:
`clj-yaml.core` (the real one sits on SnakeYAML, a Java class jolt can't load)
and `babashka.cli`. Both are taken verbatim from cljc's `vendor/` so all three
runtimes parse and print exactly the same YAML. Only the `:jolt` alias in
deps.edn puts this directory on a path; cljc ignores aliases and bb.edn doesn't
list it, so neither of them sees these copies.
