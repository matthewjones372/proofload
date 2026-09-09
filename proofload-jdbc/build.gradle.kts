plugins {
    // So the dependency on core is exported to consumers: this module's public
    // signatures are made of core's types, and `implementation` would leave a
    // caller unable to name the `Action` it is handed.
    `java-library`
}

// `java.sql` and `javax.sql` ship with the JDK, which is why a module carrying
// database steps still puts nothing on a consumer's classpath but proofload-core.
// The driver is the caller's, as the gRPC channel is (0071) and the DataSource
// is: a pool built here would have different limits from the one the service
// runs, and the pool is the thing under test as often as the database.
//
// `NoThirdPartyDependenciesTest` asserts that list rather than promising it.
dependencies {
    api(project(":proofload-core"))

    // An engine to run the scenarios the pool tests build, and a database to
    // run them against. Both test-only: the assertion below is about the *main*
    // classpath, which stays core and the JDK.
    testImplementation(project(":proofload-engine"))

    // H2 in its PostgreSQL compatibility mode, so the statements in the tests
    // look like statements somebody would write.
    testImplementation("com.h2database:h2:2.5.250")
}

tasks.test {
    // Hand the *main* runtime classpath to the test JVM. The test classpath
    // necessarily carries JUnit and a driver; only the main one is meant to be
    // bare.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dproofload.jdbc.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
