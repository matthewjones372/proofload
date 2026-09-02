// The OpenTelemetry SDK and its OTLP exporter, carried here the way
// kestrel-pelican carries pelican-core: a module that wants a collector takes
// this one, and everyone else's classpath is unchanged.
//
// Kept out of kestrel-export on purpose. That module writes two formats with
// nothing but the JDK behind them, and a consumer who wanted a histogram log
// should not inherit a metrics SDK for it.
dependencies {
    api(project(":kestrel-core"))
    api(platform("io.opentelemetry:opentelemetry-bom:1.54.1"))
    api("io.opentelemetry:opentelemetry-sdk-metrics")

    // The OTLP exporter ships with OkHttp behind it. A load test's own process
    // is the last place to put a second HTTP client — the first one is the
    // thing being measured — so the sender is swapped for the SDK's
    // `java.net.http` one, which is the client the rest of this tool already
    // sends on. `NoGrpcStackTest` is what keeps that true.
    api("io.opentelemetry:opentelemetry-exporter-otlp") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    api("io.opentelemetry:opentelemetry-exporter-sender-jdk")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")

    // Test-only, and for one assertion: that the two exports say the same
    // thing. A run's numbers reaching two backends with different boundaries
    // would be the same failure as re-bucketing, split across two modules.
    testImplementation(project(":kestrel-export"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkestrel.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
