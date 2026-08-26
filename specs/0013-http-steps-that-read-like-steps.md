# 0013 — HTTP steps that read like steps

## Problem

An HTTP step carries two pieces of ceremony that say nothing:

```kotlin
exec(browse) { api.get("/products").send(this) }
```

The block exists only to reach `this`, and `this` is the scope the block is
already running against. For a step that is one request — which is most of them
— the whole lambda is punctuation around a call.

`send(this)` also reads backwards. The thing being sent is the request, and the
scope is where the result lands, so the request is the argument and the scope
should be the receiver.

## Not doing

- No change to what is recorded, or to how a step name is derived.
- No context parameters. They would give `api.get("/products").send()`, and
  they are still experimental; a compiler flag is not worth one word.
- No removal of `exec(name) { }`. A step that does more than one thing still
  needs a body.

## Shape

```kotlin
val checkout = scenario("checkout") {
    exec(api.get("/products"))                       // named for its template
    exec(placeOrder, api.post("/orders").expecting(201))

    exec(pay) {                                      // a body, where there is one
        val id = get(orderId)
        send(api.get("/pay/$id"))
    }
}
```

- `ScenarioBuilder.exec(action: HttpAction)` — the step's name is the path
  template the request already carries.
- `StepScope.send(action: HttpAction)` — replaces `HttpAction.send(scope)`, so
  the verb takes the request rather than the scope.

## Why this shape

A request is already a value that knows its name, its expectation and its
captures. Where a step is exactly one request, the step is that value, and
anything between the two is a lambda whose body is a single expression.

Where a step is more than one request, the body comes back — and there `send`
reads as a verb with an object, in the scope it reports to.

## Stack

- [ ] **`spec-0013-send`** — `StepScope.send`, `ScenarioBuilder.exec(action)`,
      and `HttpAction.send(scope)` removed.
      Done when: a one-request step is one line with no lambda, and a
      multi-request step still compiles.
- [ ] **`spec-0013-adopt`** — the example, the README and the docs.
      Done when: no example passes `this` to anything.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **`exec(action)` takes the template as the name**, including its braces:
    `/orders/{id}` is the row, which is the whole point of templates.
2. **`send` returns the response** where one arrived, so a step that needs the
    body has it without a capture. It stays a statement in the common case.
3. **`HttpAction` keeps implementing `Action`**, so `exec(name, action)` from
    core still works and the http module needs no overload for it.
