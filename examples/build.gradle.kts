// Not published and not a library: the one place every module meets, so the
// claim that they compose is a test rather than a README paragraph. A load
// test here runs against a JDK HttpServer, so it needs no network and no
// container.
dependencies {
    testImplementation(project(":kestrel-core"))
    testImplementation(project(":kestrel-engine"))
    testImplementation(project(":kestrel-http"))
    testImplementation(project(":kestrel-junit5"))
    testImplementation(project(":kestrel-report-html"))
    testImplementation(project(":kestrel-report-github"))
}
