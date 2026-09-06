plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published modules ship.
    id("org.jetbrains.dokka") version "2.1.0" apply false
    // Writes each published module's public surface to `api/<module>.api` and
    // fails `check` where the surface moved and the file did not. It records
    // the API rather than freezing it: until 1.0 a break is allowed, and the
    // point is that it arrives as a line removed from a file a reviewer is
    // already looking at.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

scmVersion {
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name: a contributor told to install locally
    // and depend on a version should not find that version changing with the
    // branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is laid out by
 * hand on purpose. The rules that make ktlint disagree with that are turned
 * off in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    // `smoke/` is a build of its own and so has no subproject to format it, but
    // it is Kotlin a reader copies from and is held to the same layout.
    kotlin {
        target("smoke/src/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts", "smoke/*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "kestrel-arbs" to "Generators shaped like traffic: cardinality and skew as a function of the user's number.",
    "kestrel-baseline" to "Keeps a run on disk, so the next one can be compared to it.",
    "kestrel-core" to "Load scenarios as values. No dependencies.",
    "kestrel-engine" to "Runs a Kestrel simulation on virtual threads. Depends on kestrel-core.",
    "kestrel-export" to "A run's measurements in formats other tools read. No dependencies.",
    "kestrel-grpc" to "gRPC steps over a caller's own stubs and channel.",
    "kestrel-http" to "HTTP steps on the JDK client. Depends on kestrel-core and nothing else.",
    "kestrel-java" to "Kestrel from Java: static factories and builders over the same values Kotlin builds.",
    "kestrel-jdbc" to "Database steps over a caller's own DataSource, with the pool wait counted apart.",
    "kestrel-junit5" to "Load tests that are ordinary JUnit 5 tests.",
    "kestrel-kafka" to "Kafka produce steps, and completions read off another topic.",
    "kestrel-kotest" to "Load tests that are ordinary Kotest specs.",
    "kestrel-otel" to "A run's measurements sent to an OpenTelemetry collector.",
    "kestrel-pelican" to "Load tests driven by Pelican endpoint descriptions.",
    "kestrel-plan-kafka" to "Lowers a plan's produce steps onto a Kafka cluster.",
    "kestrel-record" to "A HAR recording read into a Kestrel scenario you edit and commit.",
    "kestrel-report-github" to "Run results as markdown, a job summary and a Pages directory.",
    "kestrel-report-html" to "A run result as one self-contained HTML file. No dependencies.",
    "kestrel-websocket" to "WebSocket steps on the JDK client. Depends on kestrel-core and nothing else.",
)

// Coverage, aggregated across the modules rather than per-module: a line in
// core may be exercised by another module's tests as often as by its own, and
// a per-module number reports that as a gap.
//
// The floor is a ratchet against regression, not a target to code towards — a
// test written to move a percentage is worth less than no test at all. Raise
// it when the real number has been comfortably above it for a while.
kover {
    reports {
        total {
            verify {
                rule { minBound(80) }
            }
        }
    }
}

dependencies {
    // Measurement is excluded on purpose: measuring the tool is not testing it,
    // and counting its lines would let a benchmark carry the coverage floor.
    // In `benchmarks` that is the whole module; elsewhere it is a single task,
    // excluded from instrumentation in the build file that declares it.
    subprojects.filterNot { it.name == "benchmarks" }.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") { dependsOn("koverVerify") }

/**
 * Derived from the tag rather than kept in a list beside it: a second list is a
 * thing to forget, and forgetting it puts a wall-clock test back into `build`.
 */
fun Test.measuresElapsedTime(): Boolean =
    (options as? org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions)
        ?.includeTags
        ?.contains("timing") == true

// What keeps a wall-clock test out of `./gradlew build` is one line in one
// module's build file, and every route back in is indirect: Kover pulls in the
// test tasks it instruments, so a module added to the aggregation brings its
// own. Asserting the graph is the only thing that notices. This runs once the
// graph is known, before `--dry-run` prints it and before any task starts.
gradle.taskGraph.whenReady {
    val testTasks = allTasks.filterIsInstance<Test>()
    val measuring = testTasks.filter { it.measuresElapsedTime() }
    val sharing = testTasks - measuring.toSet()
    if (measuring.isNotEmpty() && sharing.isNotEmpty()) {
        throw GradleException(
            "${measuring.joinToString { it.path }} measures elapsed time and cannot share a " +
                "machine, but this build also runs ${sharing.size} other test task(s): " +
                "${sharing.joinToString { it.path }}. Exclude it from Kover instrumentation " +
                "in its own build file, and run it alone with " +
                "`./gradlew ${measuring.first().path}`.",
        )
    }
}

/** Every module is published unless it is listed here. */
val publishedModules = subprojects.map { it.name } - "examples" - "examples-java" - "benchmarks"

// Derived from the published list rather than kept beside it: a second list is
// a thing to forget, and forgetting this one means a new module ships with no
// record of what it promised. `examples`, `examples-java` and `benchmarks` are
// not libraries and their surface is nobody's business.
apiValidation {
    ignoredProjects.addAll(subprojects.map { it.name } - publishedModules.toSet())
}

// The signature section of `docs/for-agents.md` is rendered from those same
// dumps rather than written out. A hand-written list of a public surface is
// the second source of truth `apiCheck` exists to stop the repository having,
// and it is stale the first release nobody re-reads it; rendering means a
// method that leaves the library leaves the documentation in the commit that
// removed it.
val forAgents: File = file("docs/for-agents.md")

val generatedFrom = "<!-- Rendered from the .api dumps by ./gradlew apiDocDump. Do not edit below. -->"
val generatedTo = "<!-- End of the rendered surface. -->"

val jvmPrimitives = mapOf(
    'V' to "Unit", 'Z' to "Boolean", 'B' to "Byte", 'C' to "Char", 'S' to "Short",
    'I' to "Int", 'J' to "Long", 'F' to "Float", 'D' to "Double",
)

/** Written by `equals`, `copy`, `component1` and the value-class bridges, and read by nobody. */
val boilerplate = Regex("""^(component\d*|copy|equals\d*|hashCode|toString|box|unbox|constructor|access.*)$""")

val declaresClass = Regex("""^public (.*?)class (\S+)(?: : (.*))? \{$""")
val declaresFun = Regex("""^\tpublic (.*?)fun (\S+) \(([^)]*)\)(.*)$""")
val declaresField = Regex("""^\tpublic (.*?)field (\S+) (.*)$""")

fun simpleName(binary: String): String = binary.substringAfterLast('/').replace('$', '.')

/** A value class in a signature mangles the name it is in; the suffix is not part of the API. */
fun demangled(name: String): String = name.substringBefore("\$default").substringBefore('-')

fun readOneType(descriptor: String, from: Int): Pair<String, Int> {
    val dimensions = descriptor.drop(from).takeWhile { it == '[' }.length
    val at = from + dimensions
    val (name, next) = when (descriptor[at]) {
        'L' -> descriptor.indexOf(';', at).let { simpleName(descriptor.substring(at + 1, it)) to it + 1 }
        else -> (jvmPrimitives[descriptor[at]] ?: "?") to at + 1
    }
    return "Array<".repeat(dimensions) + name + ">".repeat(dimensions) to next
}

fun typesIn(descriptors: String): List<String> = generateSequence(0 to "") { (at, _) ->
    if (at >= descriptors.length) null else readOneType(descriptors, at).let { (name, next) -> next to name }
}.drop(1).map { it.second }.toList()

fun renderMember(line: String): String? {
    if ("synthetic" in line) return null
    declaresField.find(line)?.groupValues?.let { (_, _, name, type) ->
        return if (name in setOf("Companion", "INSTANCE")) null else "val $name: ${typesIn(type).single()}"
    }
    val (_, _, raw, parameters, returns) = declaresFun.find(line)?.groupValues ?: return null
    val name = demangled(raw)
    if (boilerplate.matches(name)) return null
    val arguments = typesIn(parameters).joinToString()
    val returned = typesIn(returns).single()
    return when {
        name == "<init>" -> "constructor($arguments)"

        arguments.isEmpty() && name.startsWith("get") && name.length > 3 ->
            "val ${name[3].lowercaseChar()}${name.substring(4)}: $returned"

        returned == "Unit" -> "fun $name($arguments)"

        else -> "fun $name($arguments): $returned"
    }
}

// The same rendering twice running is one declaration the compiler emitted two
// ways — a boxed overload beside an unboxed one — not two a caller can pick from.
fun renderDump(dump: String): List<String> = renderedLines(dump)
    .let { lines -> lines.filterIndexed { at, line -> at == 0 || line != lines[at - 1] } }

fun renderedLines(dump: String): List<String> = dump.lines().mapNotNull { line ->
    declaresClass.find(line)?.let { found ->
        val (modifiers, name, supertypes) = found.destructured
        val declared = simpleName(name)
        // A file facade is not a type: `ScenarioKt` is where `scenario` and
        // `step` live, and a reader told it is a class will try to make one.
        val kind = when {
            declared.endsWith("Kt") -> "top-level in"
            "interface" in modifiers -> "interface"
            else -> "class"
        }
        val extends = supertypes.split(", ").filter { it.isNotBlank() }.joinToString { simpleName(it) }
        "$kind $declared" + if (extends.isEmpty()) "" else " : $extends"
    } ?: renderMember(line)?.let { "    $it" }
}

fun renderedSurface(): String = publishedModules.sorted().joinToString(separator = "\n") { module ->
    val rendered = renderDump(file("$module/api/$module.api").readText()).joinToString(separator = "\n")
    "### `io.github.matthewjones372:$module`\n\n```text\n$rendered\n```\n"
}

fun forAgentsWithSurface(): String {
    val document = forAgents.readText()
    val before = document.substringBefore(generatedFrom, missingDelimiterValue = "")
    val after = document.substringAfter(generatedTo, missingDelimiterValue = "")
    if (before.isEmpty() || after.isEmpty()) {
        throw GradleException("${forAgents.path} must hold the markers `$generatedFrom` and `$generatedTo`.")
    }
    return "$before$generatedFrom\n\n${renderedSurface()}\n$generatedTo$after"
}

tasks.register("apiDocDump") {
    group = "documentation"
    description = "Renders the checked-in .api dumps into the signature section of docs/for-agents.md."
    inputs.files(publishedModules.map { file("$it/api/$it.api") }).withPropertyName("theDumpsApiCheckGates")
    inputs.file(forAgents).withPropertyName("theDocumentAroundThem")
    outputs.file(forAgents)
    doLast { forAgents.writeText(forAgentsWithSurface()) }
}

// Fires from `check`, so a surface that moved without the documentation moving
// with it fails `./gradlew build` the way a stale `.api` dump already does.
val apiDocCheck = tasks.register("apiDocCheck") {
    group = "verification"
    description = "Fails when docs/for-agents.md no longer matches the .api dumps it is rendered from."
    inputs.files(publishedModules.map { file("$it/api/$it.api") }).withPropertyName("theDumpsApiCheckGates")
    inputs.file(forAgents).withPropertyName("theDocumentRenderedFromThem")
    outputs.upToDateWhen { true }
    doLast {
        if (forAgents.readText() != forAgentsWithSurface()) {
            throw GradleException(
                "${forAgents.path} no longer matches the .api dumps it is rendered from. " +
                    "Run `./gradlew apiDocDump` and commit the diff beside the change that moved the surface.",
            )
        }
    }
}

tasks.named("check") { dependsOn(apiDocCheck) }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }

    // The namespace verified on the Central Portal, against the GitHub account
    // it names.
    group = "io.github.matthewjones372"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)

        compilerOptions {
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here emit
            // real JVM default methods, so the copies are the ABI of a
            // compatibility mode with nothing behind it.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        // Assertions only. The tests run on the JUnit platform — kotest is here
        // for its matchers and the failure messages they produce, not as a
        // second test framework.
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        // Forwarded to the test JVM, which is where the goldens are compared.
        // `-Dkestrel.regenerate=true` rewrites them from the run and fails, so
        // a change to a page is a diff to read rather than a file to hand-edit.
        providers.systemProperty("kestrel.regenerate").orNull
            ?.let { asked -> systemProperty("kestrel.regenerate", asked) }
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    if (name in publishedModules) {
        apply(plugin = "com.vanniktech.maven.publish")
        apply(plugin = "org.jetbrains.dokka")

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Sources are not an optional extra for a library someone else has
            // to debug, and Maven Central will not accept a release without a
            // javadoc jar. Dokka fills it: an empty jar leaves javadoc.io
            // blank, which puts the KDoc out of reach of anyone who has not
            // cloned the repository.
            configure(
                com.vanniktech.maven.publish.KotlinJvm(
                    javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                    sourcesJar = true,
                ),
            )

            pom {
                name.set(this@subprojects.name)
                description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Kestrel.")
                url.set("https://github.com/matthewjones372/kestrel")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("matthewjones372")
                        name.set("Matt Jones")
                    }
                }
                scm {
                    url.set("https://github.com/matthewjones372/kestrel")
                    connection.set("scm:git:https://github.com/matthewjones372/kestrel.git")
                    developerConnection.set("scm:git:ssh://git@github.com/matthewjones372/kestrel.git")
                }
            }
        }

        extensions.configure<PublishingExtension> {
            repositories {
                // `./gradlew publishToMavenLocal` for a local try-out, and
                // `publishAllPublicationsToLocalRepository` for something to
                // inspect without installing it.
                maven {
                    name = "local"
                    url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
                }
            }
        }
    }
}
