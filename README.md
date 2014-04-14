# Verschlimmbesserung

An Etcd client for modern Clojure systems, built to address the needs of
Jepsen, and probably other systems too. Uses the HTTP v2 protocol.
Distinguishing features include:

- Explicit client objects
- Explicit option maps, not kwargs, for ease of composition
- Explicit timeouts throughout
- Key and value escaping; unicode keys are yours.
- Comprehensive error handling; catch specific etcd error codes
- Clojure-style swap!
- Both raw (`get*`) and simplified (`get`) APIs
- Etcd metadata on raw responses
- Directories as (possibly nested) maps, etc
- Comprehensive docstrings

## Installation

Via [https://clojars.org/verschlimmbesserung](Clojars), as usual.

## Usage

Require the library and define a client.

```clj
user=> (require '[verschlimmbesserung.core :as v])
nil
user=> (def c (v/connect "http://localhost:4001"))
#'user/c
```

First we'll create an node at `/atom`. All functions take a client as their
first argument.

```clj
user=> (v/reset! c :atom "hi")
{:action "set", :node {:key "/atom", :value "hi", :modifiedIndex 1520, :createdIndex 1520}}
```

Which will show up if we list the root:

```clj
user=> (v/get c nil)
{"atom" "hi"}
```

Let's get that key's value

```clj
user=> (v/get c :atom)
"hi"
```

A little more detail, please

```clj
user=> (v/get* c :atom)
{:action "get", :node {:key "/atom", :value "hi", :modifiedIndex 1520, :createdIndex 1520}}
user=> (meta (v/get* c :atom))
{:status 200, :leader-peer-url "http://127.0.0.1:7001", :etcd-index "1520", :raft-index "33259", :raft-term "0"}
```

Let's compare-and-set it to a new value based on the current value

```clj
user=> (v/cas! c :atom "hi" "meow")
{:action "compareAndSwap", :node {:key "/atom", :value "meow", :modifiedIndex 1521, :createdIndex 1520}, :prevNode {:key "/atom", :value "hi", :modifiedIndex 1520, :createdIndex 1520}}
```

That same CAS will fail if we try it a second time:

```clj
user=> (v/cas! c :atom "hi" "meow")
false
```

We can also use the node's index to constrain a CAS:

```clj
user=> (-> c (v/get* :atom) :node :modifiedIndex)
1521
user=> (v/cas-index! c :atom 123 "meow")
false
user=> (v/cas-index! c :atom 1521 "meow")
{:action "compareAndSwap", :node {:key "/atom", :value "meow", :modifiedIndex 1522, :createdIndex 1520}, :prevNode {:key "/atom", :value "meow", :modifiedIndex 1521, :createdIndex 1520}}
```

Or use a Clojure style swap! to apply a pure function to a node:

```clj
user=> (v/get c :atom)
"meow"
user=> (v/swap! c :atom str " says the cat")
"meow says the cat"
user=> (v/swap! c :atom str " says the cat")
"meow says the cat says the cat"
```

U-U-U-U-ULTRA PARALLEL

```clj
user=> (v/reset! c :counter "0")
{:action "set", :node {:key "/counter", :value "0", :modifiedIndex 2229, :createdIndex 2229}}
user=> (->> (range 100)
            (map #(future % (v/swap! c :counter (comp inc read-string))))
            doall
            (map deref))
(36 68 70 33 2 12 34 24 59 46 60 7 94 9 45 48 86 44 57 92 99 93 87 52 50 78 61 80 38 20 53 13 11 39 66 28 41 89 58 21 79 69 43 8 90 84 77 19 47 95 15 55 83 91 98 49 73 22 32 3 72 76 82 4 40 65 96 37 97 63 29 25 35 88 64 85 10 17 27 26 1 100 23 62 75 6 42 30 18 16 74 51 56 31 67 81 71 14 5 54)
```

Delete the node with `delete!`

```clj
user=> (v/delete! c :atom)
{:action "delete", :node {:key "/atom", :modifiedIndex 1525, :createdIndex 1520}, :prevNode {:key "/atom", :value "meow says the cat says the cat", :modifiedIndex 1524, :createdIndex 1520}}
```

Delete *everything* in a directory with `delete-all!`. You can use `nil` as a path to delete everything from the root.

```clj
user=> (v/delete-all! c nil)
nil
```

You can use strings, symbols, keywords, nil, and any sequential collection as a
key. Collections like vectors are joined with slashes, making it easier to manipulate nested objects. `nil` refers to the root.

```clj
user=> (v/reset! c [:cats :mittens] "black n white")
{:action "set", :node {:key "/cats/mittens", :value "black n white", :modifiedIndex 1528, :createdIndex 1528}}
user=> (v/reset! c [:cats :patches] "calico")
{:action "set", :node {:key "/cats/patches", :value "calico", :modifiedIndex 1529, :createdIndex 1529}}
user=> (v/get c [:cats :patches])
"calico"
```

Directories are rendered as maps:

```clj
user=> (v/get c [:cats])
{"mittens" "black n white", "patches" "calico"}
```

By default, `etcd` won't show the contents of subdirectories.

```clj
user=> (v/get c nil)
{"cats" nil}
user=> (v/get* c nil)
{:action "get", :node {:key "/", :dir true, :nodes [{:key "/cats", :dir true, :modifiedIndex 1528, :createdIndex 1528}]}}
```

But we can render them recursively like so:

```clj
user=> (v/get c nil {:recursive? true})
{"cats" {"mittens" "black n white", "patches" "calico"}}
```

Character encodings work like you'd expect. Let's create a unique key under
"physics" with the value "student":

```clj
user=> (v/create! c "物理学" "学生")
"/物理学/1530"
user=> (v/get c ["物理学" 1530])
"学生"
```

Errors are mapped nicely through the Slingshot library, so you get details up
front:

```clj
user=> (use 'slingshot.slingshot)
nil
user=> (try+ (v/swap! c :nonexistent identity) (catch Object e (pprint e)))
{:status 404,
 :errorCode 100,
 :message "Key not found",
 :cause "/nonexistent",
 :index 1903}
```

Which makes it easy to write code that correctly handles specific HTTP statuses:

```clj
user=> (try+ (v/swap! c :nonexistent identity) (catch [:status 404] _ :not-found))
:not-found
```

Or etcd error codes

```clj
user=> (try+ (v/swap! c :nonexistent identity) (catch [:errorCode 100] _ :not-found))
:not-found
```

No support for watches/locks/leaders, but they wouldn't be hard to add. Have at
it! :)

## License

Copyright © 2014 Kyle Kingsbury <aphyr@aphyr.com>

Distributed under the Eclipse Public License, the same as Clojure.
