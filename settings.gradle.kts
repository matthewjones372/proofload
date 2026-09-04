rootProject.name = "kestrel"

include(
    "kestrel-baseline",
    "kestrel-cli",
    "kestrel-core",
    "kestrel-engine",
    "kestrel-export",
    "kestrel-grpc",
    "kestrel-http",
    "kestrel-jdbc",
    "kestrel-junit5",
    "kestrel-kafka",
    "kestrel-kotest",
    "kestrel-otel",
    "kestrel-plan",
    "kestrel-pelican",
    "kestrel-record",
    "kestrel-report-github",
    "kestrel-report-html",
    "kestrel-websocket",
    // Not a library: the end-to-end proof that the modules above compose.
    "examples",
    // Not a library either: what Kestrel costs, measured rather than claimed.
    "benchmarks",
)
