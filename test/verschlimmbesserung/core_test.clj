(ns verschlimmbesserung.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [verschlimmbesserung.core :as v]))

(def c (v/connect "http://127.0.0.1:4001"))

; Delete all data before each test
(use-fixtures :each #(do (v/delete-all! c nil) (%)))

(deftest remap-keys-test
  (is (= (v/remap-keys inc {1 2 3 4})
         {2 2 4 4})))

(deftest key-encoding-test
  (testing "nil"
    (is (= "" (v/encode-key nil))))

  (testing "simple strings"
    (is (= "foo" (v/encode-key "foo"))))

  (testing "strings with slashes"
    (is (= "foo/bar" (v/encode-key "foo/bar"))))

  (testing "unicode"
    (is (= "%E2%88%B4/%E2%88%8E" (v/encode-key "∴/∎"))))

  (testing "keywords"
    (is (= "foo" (v/encode-key :foo))))

  (testing "symbols"
    (is (= "foo" (v/encode-key 'foo))))

  (testing "sequences"
    (is (= "foo"     (v/encode-key [:foo])))
    (is (= "foo/bar" (v/encode-key [:foo :bar])))
    (is (= "foo/bar" (v/encode-key '(:foo :bar))))
    (is (= "foo/bar/baz" (v/encode-key ["foo/bar" "baz"])))))

(deftest reset-get-test
  (testing "a simple key"
    (v/reset! c "test" "hi")
    (is (= "hi" (v/get c "test"))))

  (testing "Paths and unicode"
    (v/reset! c "∴/∎" "ℵ")
    (is (= "ℵ" (v/get c ['∴ '∎])))))

(deftest list-directory
  (is (= (v/get c nil)
         nil))

  (v/reset! c "foo" 1)
  (v/reset! c "bar" 2)
  (v/reset! c [:folder :of :stuff] "hi")

  (is (= (v/get c nil)
         {"foo"     "1"
          "bar"     "2"
          "folder"  nil}))

  (is (= (v/get c nil {:recursive? true})
         {"foo" "1"
          "bar" "2"
          "folder" {"of" {"stuff" "hi"}}})))

(deftest missing-values
  (is (nil? (v/get c :nonexistent))))

(deftest create-test!
  (let [r (str (rand))
        k (v/create! c :rand r)]
    (is (re-find #"^/rand/\d+$" k))
    (is (= r (v/get c k)))))

(deftest cas-test!
  (v/reset! c :foo :init)
  (is (false? (v/cas! c :foo :nope :next)))
  (is (= ":init" (v/get c :foo)))

  (v/cas! c :foo :init :next)
  (is (= ":next" (v/get c :foo))))

(deftest cas-index-test
  (let [idx (:createdIndex (:node (v/reset! c "foo" 0)))]
    (v/cas-index! c :foo idx 1)
    (is (= "1" (v/get c :foo)))

    (is (false? (v/cas-index! c :foo idx 2)))
    (is (= "1" (v/get c :foo)))))

(deftest swap-test
  (v/reset! c :atom 0)
  (is (= 3 (v/swap! c :atom (fn [a b c]
                              (+ (read-string a) b c)) 1 2)))
  (is (= "3" (v/get c :atom))))

;(deftest swap-aggressive-test
;  (v/reset! c :atom "0")
;  (->> (range 100)
;       (map #(future % (v/swap! c :atom (comp inc read-string))))
;       doall
;       (map deref)
;       sort
;       (= (range 1 101))
;       is)
;  (is (= "100" (v/get c :atom {:consistent? true}))))
