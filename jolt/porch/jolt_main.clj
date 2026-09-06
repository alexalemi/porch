(ns porch.jolt-main
  "Entry for `jolt build -m porch.jolt-main`: what bin/porch-jolt does, as a
   namespace. porch is required from inside -main, not the ns form, because
   porch.clj ends in (System/exit (-main *args*)) and would exit the build.")
(require 'cljc)
(intern (create-ns 'porch.store) 'sh cljc/sh)
(defn -main [& args]
  (.addShutdownHook (Runtime/getRuntime) (Thread. flush))
  (intern (create-ns 'porch) '*args* (vec args))
  (require 'porch))
