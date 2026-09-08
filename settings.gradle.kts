rootProject.name = "kestrel"

include(
    "kestrel-arbs",
    "kestrel-baseline",
    "kestrel-cli",
    "kestrel-contract",
    "kestrel-core",
    "kestrel-engine",
    "kestrel-export",
    "kestrel-grpc",
    "kestrel-grpc-dynamic",
    "kestrel-http",
    "kestrel-java",
    "kestrel-jdbc",
    "kestrel-junit5",
    "kestrel-kafka",
    "kestrel-kotest",
    "kestrel-otel",
    "kestrel-openapi",
    "kestrel-mcp",
    "kestrel-plan",
    "kestrel-plan-kafka",
    "kestrel-pelican",
    "kestrel-record",
    "kestrel-report-github",
    "kestrel-report-html",
    "kestrel-scala",
    "kestrel-websocket",
    // Not a library: the end-to-end proof that the modules above compose.
    "examples",
    // Not a library either: a Java compiler is the only thing that can see
    // whether kestrel-java is callable from Java.
    "examples-java",
    // Not a library either: what Kestrel costs, measured rather than claimed.
    "benchmarks",
)
