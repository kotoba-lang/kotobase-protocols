;; nbb test runner — first-class runtime per repo rule (kotoba wasm >
;; clojurewasm > cljs > nbb > (jvm/bb)). Run from the repo root:
;;
;;   nbb --classpath "src:test:<kotobase>/src" bin/run_tests.cljs
;;
;; where <kotobase> is a checkout of kotoba-lang/kotobase (provides
;; kotobase.protocols.store). CI uses the source-local memory host, so
;; deps.edn.
(ns run-tests
  (:require [cljs.test :as t]
            [kotobase.protocols.atproto-test]
            [kotobase.protocols.git-test]
            [kotobase.protocols.ipfs-test]
            [kotobase.protocols.json-test]
            [kotobase.protocols.router-test]
            [kotobase.protocols.s3-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (set! (.-exitCode js/process) 1)))

(t/run-tests 'kotobase.protocols.json-test
             'kotobase.protocols.s3-test
             'kotobase.protocols.ipfs-test
             'kotobase.protocols.atproto-test
             'kotobase.protocols.git-test
             'kotobase.protocols.router-test)
