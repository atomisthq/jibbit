(ns jibbit.util-t
  (:require [clojure.test :as t]
            [jibbit.util :refer [env-subst parse-docker-port]]))

(t/deftest env-subst-tests
  (t/is (= "gcr.io/personalsdm/service" (env-subst "gcr.io/personalsdm/service" (constantly nil))))
  (t/is (= "gcr.io/personalsdm/service" (env-subst "gcr.io/$PROJECT_ID/service"
                                                   (fn [s] (when (= s "PROJECT_ID") "personalsdm")))))
  (t/is (= "gcr.io/personalsdm/clj-http" (env-subst "gcr.io/$PROJECT_ID/${SERVICE}"
                                                    (fn [s] (cond (= s "PROJECT_ID") "personalsdm"
                                                                  (= s "SERVICE") "clj-http")))))
  (t/is (= nil (env-subst nil (constantly nil))))
  (t/is (= "" (env-subst "" (constantly nil))))
  (t/is (thrown? Throwable (env-subst "gcr.io/$PROJECT_ID/service" (constantly nil)))))

(t/deftest parse-docker-port-tests
  (t/is (= {:port 80 :protocol :tcp} (parse-docker-port 80)))
  (t/is (= {:port 80 :protocol :tcp} (parse-docker-port "80")))
  (t/is (= {:port 80 :protocol :tcp} (parse-docker-port "80/tcp")))
  (t/is (= {:port 53 :protocol :udp} (parse-docker-port "53/udp")))
  (t/is (thrown? Throwable (parse-docker-port nil)))
  (t/is (thrown? Throwable (parse-docker-port "")))
  (t/is (thrown? Throwable (parse-docker-port "81/icmp"))))