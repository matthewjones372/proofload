package io.github.matthewjones372.kestrel.mcp

/**
 * What a plan looks like, for a caller that has never seen one.
 *
 * The tool that makes the rest work. A caller who can ask for the format writes
 * a valid plan on the first attempt instead of a plausible one, and what comes
 * back is the shape the parser enforces rather than documentation about it —
 * every key named here is a key `readPlan` accepts, and a test says so.
 */
internal val PLAN_SCHEMA: String = """
    A plan is YAML or JSON. It states what a run sends without any code, and it
    deliberately cannot say everything the Kotlin DSL can: there are no
    captures, no conditions and no per-user bodies, because those need a lambda.
    When a plan needs one, `emit` prints the Kotlin it was equivalent to and you
    continue there.

    Keys, all required unless said otherwise:

      kestrel   plan/1 — the version this reader accepts, and the only one
      scenario  what the run is called, one row in the report
      steps     a list; each is a name, one key saying what it sends, and options
      load      rate + over, or from + to + over, or stages
      baseUrl   where request steps are sent; needed only if there are any
      brokers   the cluster topic steps reach; needed only if there are any
      goals     optional; a list of step + percentile, or failureRate

    A step is one of three kinds, decided by the key it carries. A plan may mix
    them, and every kind takes an optional pauseAfter — a wait that records
    nothing, e.g. 2s.

    A request names a verb — get, post, put, patch, delete, head — whose value
    is the path. Optional beside it:

      headers    a map
      body       a string
      expecting  the status that counts as success, default 200
      declared   statuses this endpoint documents; they fail under their own
                 reason rather than counting as defects

    A publish names produce, whose value is the topic:

      body       a string, sent as UTF-8, required
      key        the partition key; every record carries the same one, because
                 a plan has no lambda to vary it
      settings   Kafka's own producer settings by their own names, e.g. acks

    An answer names completes, whose value is the produce step it answers. What
    it records is the round trip, as a row of its own:

      on         the topic the answer arrives on
      by         the header both records carry the correlation id in
      within     how long the run waits for an answer; required, because a run
                 that waits forever reports no failure and no number
      group      the consumer group to read under

    A run is drained into one sink, so a plan declares at most one completes.

    A path may not contain {braces}: they are read from a session key of that
    name, a plan has no feeder to fill one, and the step would fail every
    request. Use a real value, or emit and add a feeder.

    Three worked plans.

    ---
    kestrel:  plan/1
    baseUrl:  https://orders.internal
    scenario: checkout
    steps:
      - name: browse
        get:  /products
      - name: place order
        post: /orders
        body: '{"cart":"1 anvil"}'
        expecting: 201
        declared: [409]
        pauseAfter: 2s
    load:
      rate: 50/s
      over: 1m
    goals:
      - step: place order
        p99:  200ms
    ---
    kestrel:  plan/1
    baseUrl:  https://orders.internal
    scenario: ramp
    steps:
      - name: browse
        get:  /products
    load:
      stages:
        - {rate: 10/s, over: 30s}
        - {rate: 200/s, over: 2m}
    goals:
      - step: browse
        failureRate: "1%"
    ---
    kestrel:  plan/1
    brokers:  localhost:9092
    scenario: orders
    steps:
      - name: place order
        produce: orders
        body: '{"cart":"1 anvil"}'
        settings:
          acks: all
      - name: confirmed
        completes: place order
        on: order-confirmations
        by: correlation-id
        group: kestrel-bench
        within: 30s
    load:
      rate: 500/s
      over: 1m
    goals:
      - step: confirmed
        p99: 2s
    ---
""".trimIndent()
