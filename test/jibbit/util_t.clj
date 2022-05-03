(ns jibbit.util-t
  (:require [clojure.test :as t]
            [jibbit.util :refer [env-subst]]))

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
