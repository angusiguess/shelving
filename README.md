# Shelving (née Tupelov)
<img align="right" src="./etc/shelving.jpg" width=300/>

> Shelving; Noun;
>
> Collective form of Shelf; a thin slab of wood, metal, etc., fixed horizontally to a wall or in a
> frame, for supporting objects.

Before you can have [Stacks](https://github.com/arrdem/stacks), you need shelving for the books.

## Manifesto

There's already plenty of prior art in the Clojure ecosystem for in-memory
databases. [datascript](https://github.com/tonsky/datascript) and
[pldb](https://github.com/clojure/core.logic/wiki/Features) pop to mind, among others. These tools
optimize for database-like query access over in-memory structures and don't provide concrete
serialization stories.

For small applications or applications which want to distribute data as resources, traditional
databases which require a central available server are a poor fit.

Shelving is a tool set for trying to implement quick-and-dirty storage layers for your existing
[spec](https://github.com/clojure/spec.alpha)'d data while retaining some of the query niceties that
make ORMs and real databases compelling.

## Usage
For brevity, only selected vars are listed here.
See the docs for complete listings.

- [Basic API](/docs/basic.md) - The core API
  - [shelving.core/open](/docs/basic.md#shelvingcoreopen)
  - [shelving.core/flush](/docs/basic.md#shelvingcoreflush)
  - [shelving.core/close](/docs/basic.md#shelvingcoreclose)
  - [shelving.core/put](/docs/basic.md#shelvingcoreput)
  - [shelving.core/get](/docs/basic.md#shelvingcoreget)
- [Shelf Spec API](/docs/schema.md#schema-api) - Specs describe the structure of values, but must be declared in a schema to associate semantics with that structure.
  - [shelving.core/empty-schema](/docs/schema.md#shelvingcoreemptyschema)
  - [shelving.core/value-spec](/docs/schema.md#shelvingcorevalue-spec)
  - [shelving.core/record-spec](/docs/schema.md#shelvingcorerecord-spec)
- [Shelf Relation API](/docs/rel.md#rel-api) - Rel(ation)s between vals on a shelf.
  Usable to implement query systems and interned values.
  - [shelving.core/shelf-rel](/docs/rel.md#shelvingcoreshelf-rel)
  - [shelving.core/enumerate-rels](/docs/rel.md#shelvingcoreenumerate-rels)
  - [shelving.core/enumerate-rel](/docs/rel.md#shelvingcoreenumerate-rel)
- [UUID helpers](/docs/helpers.md) - Mostly implementation details, but possibly interesting.

See also the [Grimoire v3 case study](/src/dev/clj/grimoire.clj) which motivates much of this work.

### Demo

Shelving includes a "trivial" back end, which provides most of the same behavior as simpledb, along
with the same trade-offs of always keeping everything in memory and using EDN for
serialization. This probably isn't the shelf you want, but it was the first one.

```clj
user> (require '[shelving.core :as sh])
nil
user> (require '[clojure.spec.alpha :as s])
nil
user> (s/def ::foo string?)
:user/foo
user> (s/def ::bar string?)
:user/bar
user> (def schema
        (-> sh/empty-schema
            (sh/value-spec  ::foo)   ;; values are immutable, unique
            (sh/record-spec ::foo))) ;; records are mutable; places
#'user/schema
user> (require '[shelving.trivial-edn :refer [->TrivialEdnShelf]])
nil
user> (def *conn
        (-> (->TrivialEdnShelf schema "demo.edn")
            (sh/open)))
#'user/*conn
;; Inserting some values
user> (sh/put *conn ::foo "my first write")
#uuid "33a65680-b734-fec6-a656-80b734fec6bd"
user> (sh/put *conn ::foo "another write")
#uuid "d47453e5-4611-a98e-7453-e54611a98e03"
user> (sh/enumerate-spec *conn ::foo)
(#uuid "33a65680-b734-fec6-a656-80b734fec6bd" #uuid "d47453e5-4611-a98e-7453-e54611a98e03")
;; Values are content-hashed and deduplicated
user> (sh/put *conn ::foo "another write")
#uuid "d47453e5-4611-a98e-7453-e54611a98e03"
user> (sh/enumerate-spec *conn ::foo)
(#uuid "33a65680-b734-fec6-a656-80b734fec6bd" #uuid "d47453e5-4611-a98e-7453-e54611a98e03")
;; Records however are mutable
user> (sh/put *conn ::bar "some text")
#uuid "eaecbe3d-ae9f-4cab-992d-24fbba0414af"
user> (sh/put *conn ::bar *1 "some other text")
#uuid "35a91d37-504b-4fc5-b386-6291da8867db"
user> (sh/get *conn ::bar *1)
"some other text"
user>
```

## License

Copyright © 2018 Reid "arrdem" McKenzie

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later
version.
