(ns verschlimmbesserung.core
  "Core Raft API operations over HTTP. Clients are currently stateless, but you
  may maintain connection pools going forward. In general, one creates a client
  using (connect) and uses that client as the first argument to all API
  functions.

  Every operation may take a map of options as an optional final argument.
  These options are remapped from :clojure-style keys to their Raft equivalents
  and passed as the query parameters of the request; with the exception of a
  few keys like :timeout; see http-opts for details.

  Functions with a bang, like reset!, mutate state. All other functions are
  pure.

  Some functions come in pairs, like get and get*.

  The get* variant returns the full etcd response body as a map, as specified
  by http://coreos.com/docs/distributed-configuration/etcd-api/. Note that
  values are strings; verschlimmbesserung does not provide value
  serialization/deserialization yet.

  The get variant returns a more streamlined representation: just the node
  value itself."
  (:refer-clojure :exclude [swap! reset! get set])
  (:require [clojure.core           :as core]
            [clojure.core.reducers  :as r]
            [clojure.string         :as str]
            [clj-http.client        :as http]
            [clj-http.util          :as http.util]
            [cheshire.core          :as json]
            [slingshot.slingshot    :refer [try+ throw+]])
  (:import (com.fasterxml.jackson.core JsonParseException)
           (java.io InputStream)
           (clojure.lang MapEntry)))

(def api-version "v2")

(def default-timeout "milliseconds" 1000)

(def default-swap-retry-delay
  "How long to wait (approximately) between retrying swap! operations which
  failed. In milliseconds."
  100)

(defn connect
  "Creates a new etcd client for the given server URI. Example:

  (def etcd (connect \"http://127.0.0.1:4001\"))

  Options:

  :timeout            How long, in milliseconds, to wait for requests.
  :swap-retry-delay   Roughly how long to wait between CAS retries in swap!"
  ([server-uri]
   (connect server-uri {}))
  ([server-uri opts]
   (merge {:timeout           default-timeout
           :swap-retry-delay  default-swap-retry-delay
           :endpoint          server-uri}
          opts)))

(defn base-url
  "Constructs the base URL for all etcd requests. Example:

  (base-url client) ; => \"http://127.0.0.1:4001/v2\""
  [client]
  (str (:endpoint client) "/" api-version))

(defn ^String encode-key
  "String, symbol, and keyword keys map to their names; e.g. \"foo\", :foo, and
  'foo are equivalent. All keys are url-encoded *except* for the forward slash,
  which is a path separator in Raft. Sequences like ['foo 'bar 'baz] have each
  component url-encoded and joined by slashes.

  Numbers map to (str num). Nils map to the empty string; e.g. the root."
  [key]
  (cond
    (nil? key) ""

    (instance? String key)
    (if (re-find #"/" key)
      (->> (str/split key #"/")
           (map http.util/url-encode)
           (str/join "/"))
      (http.util/url-encode key))

    (instance? clojure.lang.Named key)
    (recur (name key))

    (number? key)
    (recur (str key))

    (sequential? key)
    (str/join "/" (map encode-key key))

    :else (throw (IllegalArgumentException.
                   (str "Don't know how to interpret " (pr-str key)
                        " as key")))))

(defn key-url
  "The URL for a particular key.

  (key-url client \"foo\") ; => \"http://127.0.0.1:4001/v2/foo"
  [client key]
  (let [key  (encode-key key)
        path (if (and (pos? (.length key))
                      (= \/ (.charAt key 0)))
              (str "/keys" key)
              (str "/keys/" key))]
    (str (base-url client) path)))

(defn remap-keys
  "Given a map, transforms its keys using the (f key). If (f key) is nil,
  preserves the key unchanged.

  (remap-keys inc {1 :a 2 :b})
  ; => {2 :a 3 :b}

  (remap-keys {:a :a'} {:a 1 :b 2})
  ; => {:a' 1 :b 2}"
  [f m]
  (->> m
       (r/map (fn [[k v]]
                [(let [k' (f k)]
                   (if (nil? k') k k'))
                 v]))
       (into {})))

(defn http-opts
  "Given a map of options for a request, constructs a clj-http options map.
  :timeout is used for the socket and connection timeout. Remaining options are
  passed as query params."
  [client opts]
  {:as                    :string
   :throw-exceptions?     true
   :throw-entire-message? true
   :follow-redirects      true
   :force-redirects       true ; Etcd uses 307 for side effects like PUT
   :socket-timeout        (or (:timeout opts) (:timeout client))
   :conn-timeout          (or (:timeout opts) (:timeout client))
   :query-params          (dissoc opts :timeout)})

(defn parse-resp
  "Takes a clj-http response, extracts the body, and assoc's status and Raft
  X-headers as metadata (:etcd-index, :raft-index, :raft-term) on the
  response's body."
  [response]
  (when-not (:body response)
    (throw+ {:type     ::missing-body
             :response response}))

  (try+
    (let [body (:body response)
          body (if (instance? InputStream body)
                 (json/parse-stream body true)
                 (json/parse-string body true))
          h    (:headers response)]
      (with-meta body
                 {:status           (:status response)
                  :leader-peer-url  (core/get h "x-leader-peer-url")
                  :etcd-index       (core/get h "x-etcd-index")
                  :raft-index       (core/get h "x-raft-index")
                  :raft-term        (core/get h "x-raft-term")}))
    (catch JsonParseException e
      (throw+ {:type     ::invalid-json-response
               :response response}))))

(defmacro parse
  "Parses regular responses using parse-resp, but also rewrites slingshot
  exceptions to have a little more useful structure; bringing the json error
  response up to the top level and merging in the http :status."
  [expr]
  `(try+
     (let [r# (parse-resp ~expr)]
       r#)
     (catch (and (:body ~'%) (:status ~'%)) {:keys [:body :status] :as e#}
       ; etcd is quite helpful with its error messages, so we just use the body
       ; as JSON if possible.
       (try (let [body# (json/parse-string ~'body true)]
              (throw+ (assoc body# :status ~'status)))
            (catch JsonParseException _#
              (throw+ e#))))))

(declare node->value)

(defn node->pair
  "Transforms an etcd node representation of a directory into a [key value]
  pair, recursively. Prefix is the length of the key prefix to drop; etcd
  represents keys as full paths at all levels."
  [prefix-len node]
  (MapEntry. (subs (:key node) prefix-len)
             (node->value node)))

(defn node->value
  "Transforms an etcd node representation into a value, recursively. Prefix is
  the length of the key prefix to drop; etcd represents keys as full paths at
  all levels."
  ([node] (node->value 1 node))
  ([prefix node]
   (if (:nodes node)
     ; Recursive nested map of relative keys to values
     (let [prefix (if (= "/" (:key node))
                    1
                    (inc (.length (:key node))))]
       (->> node
            :nodes
            (r/map (partial node->pair prefix))
            (into {})))
     (:value node))))

(defn get*
  ([client key]
   (get* client key {}))
  ([client key opts]
   (->> opts
        (remap-keys {:recursive?   :recursive
                     :consistent?  :consistent
                     :sorted?      :sorted
                     :wait?        :wait
                     :wait-index   :waitIndex})
        (http-opts client)
        (http/get (key-url client key))
        parse)))

(defn get
  "Gets the current value of a key. If the key does not exist, returns nil.
  Single-node queries return the value of the node itself: a string for leaf
  nodes; a sequence of keys for a directory.

  (get client [:cats :mittens])
  => \"the cat\"

  Directories have nil values unless :recursive? is specified.

  (get client :cats)
  => {\"mittens\"   \"the cat\"
      \"more cats\" nil}

  Recursive queries return a nested map of string keys to nested maps or, at
  the leaves, values.

  (get client :cats {:wait-index 4 :recursive? true})
  => {\"mittens\"   \"black and white\"
      \"snowflake\" \"white\"
      \"favorites\" {\"thomas o'malley\" \"the alley cat\"
                     \"percival\"        \"the professor\"}}

  Options:

  :recursive?
  :consistent?
  :sorted?
  :wait?
  :wait-index
  :timeout"
  ([client key]
   (get client key {}))
  ([client key opts]
   (try+
     (-> (get* client key opts)
         :node
         node->value)
     (catch [:status 404] _ nil))))

(defn reset!
  "Resets the current value of a given key to `value`. Options:

  :ttl
  :timeout"
  ([client key value]
   (reset! client key value {}))
  ([client key value opts]
   (->> (assoc opts :value value)
        (http-opts client)
        (http/put (key-url client key))
        parse)))

(defn create!*
  ([client path value]
   (create!* client path value {}))
  ([client path value opts]
   (->> (assoc opts :value value)
        (http-opts client)
        (http/post (key-url client path))
        parse)))

(defn create!
  "Creates a new, automatically named object under the given path with the
  given value, and returns the full key of the created object. Options:

  :timeout
  :ttl"
  ([client path value]
   (create! client path value {}))
  ([client path value opts]
   (-> (create!* client path value opts)
       :node
       :key)))

(defn delete!
  "Deletes the given key. Options:

  :timeout
  :dir?
  :recursive?"
  ([client key]
   (delete! client key {}))
  ([client key opts]
   (->> opts
        (remap-keys {:recursive?  :recursive
                     :dir?        :dir})
        (http-opts client)
        (http/delete (key-url client key))
        parse)))

(defn delete-all!
  "Deletes all nodes, recursively if necessary, under the given directory.
  Options:

  :timeout"
  ([client key]
   (delete-all! client key {}))
  ([client key opts]
   (doseq [node (->> (select-keys opts [:timeout])
                     (get* client key)
                     :node
                     :nodes)]
     (delete! client (:key node) {:recursive? (:dir node)
                                  :timeout    (:timeout opts)}))))

(defn cas!
  "Compare and set based on the current value. Updates key to be value' iff the
  current value of key is value. Optionally, you may also constrain the
  previous index and/or the existence of the key. Throws for CAS failure.
  Options:

  :timeout
  :ttl
  :prev-value
  :prev-index
  :prev-exist?"
  ([client key value value']
   (cas! client key value value' {}))
  ([client key value value' opts]
   (try+
     (->> (assoc opts
                 :prevValue  value
                 :value      value')
          (remap-keys {:prev-index  :prevIndex
                       :prev-exist? :prevExist})
          (http-opts client)
          (http/put (key-url client key))
          parse)
     (catch [:errorCode 101] _ false))))

(defn cas-index!
  "Compare and set based on the current value. Updates key to be value' iff the
  current index key matches. Optionally, you may also constrain the previous
  value and/or the existence of the key. Returns truthy if CAS succeeded; false
  otherwise. Options:

  :timeout
  :ttl
  :prev-value
  :prev-index
  :prev-exist?"
  ([client key index value']
   (cas-index! client key index value' {}))
  ([client key index value' opts]
   (try+
     (->> (assoc opts
                 :prevIndex  index
                 :value      value')
          (remap-keys {:prev-value  :prevValue
                       :prev-exist? :prevExist})
          (http-opts client)
          (http/put (key-url client key))
          parse)
     (catch [:errorCode 101] _ false))))

(defn swap!
  "Atomically updates the value at the given key to be (f old-value & args).
  Randomized backoff based on the client's swap retry delay. Returns the
  successfully set value."
  [client key f & args]
  (loop []
    (let [node    (:node (get* client key))
          index   (:modifiedIndex node)
          value'  (apply f (:value node) args)]
      (if (cas-index! client key index value')
        value'
        (do
          (Thread/sleep (:swap-retry-delay client))
          (recur))))))
