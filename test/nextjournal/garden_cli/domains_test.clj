(ns nextjournal.garden-cli.domains-test
  (:require [clojure.test :refer :all]
            [nextjournal.garden-cli.domains :as sut]))

(deftest check-domain-dns-test
  (is (= {:error "domain foo.cloud.clerk.garden should point to 62.113.212.138 but points to 62.113.212.137"} (sut/check-domain-dns {:domain "foo.cloud.clerk.garden"
                                                                                                                                     :env :staging})))
  (is (not (contains? (sut/check-domain-dns {:domain "foo.cloud.staging.clerk.garden"
                                             :env :staging})
                      :error))))
