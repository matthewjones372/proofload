rootProject.name = "kestrel"

include(
    "kestrel-baseline",
    "kestrel-core",
    "kestrel-engine",
    "kestrel-http",
    "kestrel-junit5",
    "kestrel-kotest",
    "kestrel-pelican",
    "kestrel-report-github",
    "kestrel-report-html",
    // Not a library: the end-to-end proof that the modules above compose.
    "examples",
    // Not a library either: what Kestrel costs, measured rather than claimed.
    "benchmarks",
)
