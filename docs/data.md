# Data per user and values between steps

Ten thousand users sending one identical request measure whatever the target
does with a duplicate: a cache hit, a deduplicated write, a unique-index
collision. This page covers giving each user its own data, and carrying values
from one step's response into the next.

## Sessions and keys

Each virtual user has a **session**: an immutable map from typed keys to values.
A key is declared once and used everywhere, so a capture into a `String` key
reads back as a `String` with no cast:

```kotlin
import io.github.matthewjones372.proofload.sessionKey

val orderId = sessionKey<String>("orderId")
```

A user's session starts as whatever its feeder gives it. Each step receives the
session the previous step returned, so values flow along one user's journey and
never between users. A step that fails has its partial writes discarded, and
the user's journey ends there ([failures](measurement.md#failures)).

`{name}` in a request path or body is filled from the session key of that name.

## Carry a value from one step to the next

```kotlin
val orderId = sessionKey<String>("orderId")

val checkout = scenario("checkout") {
    exec(
        placeOrder,
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { response -> response.header("location") },
    )
    exec(confirm, api.get("/orders/{orderId}/confirmation"))
}
```

A capture runs only when the status matched and every check passed. A capture
that finds nothing fails the step it was declared on, rather than the step that
would have used the value. `capture` is on HTTP requests; a step body can read
and write the session directly.


## Feed each user its own data

A feeder gives each user its starting session:

```kotlin
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.sessionKey

val customer = sessionKey<String>("customer")

val checkout = scenario("checkout") {
    exec(browse, api.get("/customers/{customer}/products"))   // filled from the session
}

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { user -> "customer-$user" })
```

A feeder is a function of the user's number rather than a cursor over a source.
Nothing is locked on the path every request takes, nothing runs out, and user
4,001 gets the same data on every run. In a mix of several journeys, each arm
numbers its users from zero.

Java says this as `Feeders.of(customer, user -> "customer-" + user)` and
`Feeders.fedBy(simulation, feeder)`; Scala says `feed(customer)(user =>
s"customer-$user")`, with `+` to combine two and `feedFrom` for a fixed list.

A request body reads the session the same way, so the thing being posted varies
per user too:

```kotlin
exec(placeOrder, api.post("/orders").body("""{"customer":"{customer}","cart":"1 anvil"}"""))
```

Only an identifier between braces is a placeholder, so a JSON document's own
braces are content and arrive as written. A `{name}` the session has nothing
under fails the step rather than sending the braces to the target.

A body too large to hold is opened instead of written:

```kotlin
exec(upload, api.put("/uploads/{id}").bodyFrom(bytes = size) { Files.newInputStream(archive) })
```

The lambda is called once per attempt, so a retry and a redirect each get a
fresh stream. Give `bytes` where you know
the length and it is sent as `content-length`; leave it out and the request is
chunked. Nothing fills `{name}` in a streamed body: the substitution reads a
string, and a stream is not one.

## A fixed list

```kotlin
import io.github.matthewjones372.proofload.feedFrom

val sku = sessionKey<String>("sku")

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feedFrom(sku, listOf("anvil", "rocket", "birdseed")))
```

It wraps round at the end. A feeder that ran out would end a load test for a
reason that has nothing to do with the target.

Two feeders compose, and the later one wins where they fill the same key:

```kotlin
import io.github.matthewjones372.proofload.plus

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { "customer-$it" } + feedFrom(sku, skus))
```

## A CSV file

Read once, before the run, and held as a value:

```kotlin
import io.github.matthewjones372.proofload.csv
import io.github.matthewjones372.proofload.feeding

val customer = sessionKey<String>("customer")
val tier = sessionKey<String>("tier")

val accounts = csv(Path.of("src/test/resources/accounts.csv"))

checkout.at(50.perSecond, over = 1.minutes).fedBy(accounts.feeding(customer, tier))
```

Each key fills from the column the header gave the same name, so there is no
second place to keep the mapping. A key naming a column the file does not have
fails when the feeder is built, naming the column and listing the ones that are
there, before the run departs anything, rather than on user one.

A CSV has no types, so a key that is not a `String` names its own conversion:

```kotlin
val account = sessionKey<Long>("account")

accounts.feeding(account) { it.toLong() }
```

The conversion runs once per row while the feeder is built, never on the path a
departure takes.

The file is held in memory rather than streamed, because a file read between a
departure and its request would be measured as the target's latency. One
session is built per row up front, and rows wrap round. The parser handles a
small part of RFC 4180: quoted fields and doubled quotes inside them.

## Generated data with a stated cardinality and skew

A thousand-row file cycled for a million users keeps every one of those rows in
every cache the target has, so part of the p99 on the page is a hit rate the
test invented. Drawing a key uniformly at random is wrong the other way: it
misses every cache. Real traffic does neither. What decides the number is
how many distinct keys there are and how unevenly they are asked for, and
`proofload-arbs` is where both are said out loud:

```kotlin
import io.github.matthewjones372.proofload.arbs.map
import io.github.matthewjones372.proofload.arbs.oneOf
import io.github.matthewjones372.proofload.arbs.zipf
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.plus

val customerId = zipf(keys = 1_000_000, skew = 1.1).map { "customer-$it" }
val basket = oneOf("anvil", "rocket", "birdseed")

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { customerId at it } + feed(sku) { basket at it })
```

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-arbs:0.1.0-rc4")
}
```

A generator is read with `at(userNumber)`, never `next()`: it is a pure function
of the user's number, so a run replays exactly and the value user 8,412 sent can
be derived again from its number. It is drawn where the feeder runs, before the
departure, rather than inside a step body, so its allocation is not on the
measured path.

`zipf` answers a rank rather than a key, because a generator that formatted
strings would have guessed your id scheme; `map` turns it into whatever yours
is. `uniform` is the flat keyspace, `digits` and `uuids` are ids of a fixed
shape, and `weighted` is a traffic mix stated as proportions.

These are not Kotest's `Arb`, which leans towards edge cases for finding bugs.

A `Feeder` is a function of the user's number and nothing else, so nothing
downstream can work out what a run's keys were drawn from by looking at it.
`drawing` is where you say it, beside `fedBy`:

```kotlin
import io.github.matthewjones372.proofload.drawing

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { customerId at it } + feed(sku) { basket at it })
    .drawing(customerId.shape, basket.shape)
```

The shapes travel with the result, onto the report, `Data: zipf(keys=1000000,
skew=1.1), seed 0.` beside the arrival process, on the HTML page and in the job
summary, and into a baseline file. A comparison refuses two runs that named
different ones rather than reporting the cache hit rate one of them bought as a
regression. A run that named no shape is compared without this check, so moving a CSV-fed
run onto a generator is a change no comparison flags.

### The same thing in a plan

A plan file draws too, so a generated plan is not ten thousand requests for one
row. `draw` names a session key and a generator, and `{name}` in a path or a
body reads it:

```yaml
proofload:  plan/1
baseUrl:  https://shop.internal
scenario: catalogue
seed: 7
draw:
  sku:    {zipf: {keys: 1000000, skew: 1.1}}
  page:   {uniform: {from: 1, to: 20}}
  region: {oneOf: [emea, apac, amer]}
steps:
  - name: open product
    get: '/regions/{region}/products/{sku}'
    declared: [404]
load:
  rate: 200/s
  over: 1m
```

`{uniform: 500}` draws `0` to `499`; the `from`/`to` form draws the inclusive
range a contract states. Every drawn value arrives as a string, because that is
what a path reads. Each key is seeded from `seed` and its own name, so two keys
never draw in step with one another. Without that, customer 41 would always
buy item 41 and nothing in the report would show it.

`proofload from-openapi` writes the draw itself where the document bounds a
parameter: `minimum: 1, maximum: 500` becomes `{uniform: {from: 1, to: 500}}`,
and an `enum` becomes every value it lists. Where the document bounds nothing,
one legal value is substituted. Inventing a range it never stated
would be inventing the cardinality this section is about.

`proofload emit` prints the Kotlin above, seeds included, so the file and the
source draw the same data.

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-plan:0.1.0-rc4")
}
```

## A token that expires mid-run

Fetching a token inside a step puts that round trip in one request out of a few
hundred, and the p99 becomes a measurement of your identity provider.
`refreshing` keeps it off the measured path:

```kotlin
import io.github.matthewjones372.proofload.refreshing

val token = refreshing(every = 4.minutes) { fetchToken() }

val checkout = scenario("checkout") {
    exec(placeOrder) {
        send(api.post("/orders").header("authorization", "Bearer ${token.current}"))
    }
}

// afterwards
token.stop()
```

The first fetch happens when `refreshing` is called, before the run. Every later fetch runs
on a daemon thread no departure is submitted to. `current` is a volatile read of
a value already in hand and allocates nothing.
