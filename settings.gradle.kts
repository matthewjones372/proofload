rootProject.name = "proofload"

include(
    "proofload-arbs",
    "proofload-baseline",
    "proofload-cli",
    "proofload-contract",
    "proofload-core",
    "proofload-engine",
    "proofload-export",
    "proofload-grpc",
    "proofload-grpc-dynamic",
    "proofload-http",
    "proofload-java",
    "proofload-jdbc",
    "proofload-junit5",
    "proofload-kafka",
    "proofload-kotest",
    "proofload-otel",
    "proofload-openapi",
    "proofload-mcp",
    "proofload-plan",
    "proofload-plan-kafka",
    "proofload-pelican",
    "proofload-record",
    "proofload-report-github",
    "proofload-report-html",
    "proofload-scala",
    "proofload-websocket",
    "proofload-zio-test",
    // Not a library: the end-to-end proof that the modules above compose.
    "examples",
    // Not a library either: a Java compiler is the only thing that can see
    // whether proofload-java is callable from Java.
    "examples-java",
    // Nor this: a Scala compiler is the only thing that can see whether
    // proofload-scala is callable from Scala, and there is no .api dump for it.
    "examples-scala",
    // Not a library either: what Proofload costs, measured rather than claimed.
    "benchmarks",
)
