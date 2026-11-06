plugins {
    `java-library`
    `maven-publish`
    id("me.champeau.jmh") version "0.7.3"
}

group = "com.github.justadeni"
version = "0.1.0"
description = "Physics for groups of block displays, rendered with packets."

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    maven { url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") }
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

val vectorModule = "--add-modules=jdk.incubator.vector"

dependencies {
    // The server provides the API, JOML and fastutil. Only RegionizedPlatform touches Paper-only API, and it is
    // loaded by name where that API exists.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("it.unimi.dsi:fastutil:8.5.15")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
    testImplementation("it.unimi.dsi:fastutil:8.5.15")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Benchmarks run against the same classes the tests do
    jmhImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
    jmhImplementation("it.unimi.dsi:fastutil:8.5.15")
}

tasks {
    compileJava {
        // The SIMD kernels use the incubating Vector API. --release can't be combined with --add-modules, so the
        // toolchain's own JDK is the target. They only load when the running JVM has the module.
        options.compilerArgs.add(vectorModule)
    }
    named<JavaCompile>("compileJmhJava") {
        options.compilerArgs.add(vectorModule)
    }
    javadoc {
        (options as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:none", "-quiet")
            addStringOption("-add-modules", "jdk.incubator.vector")
        }
    }
    test {
        useJUnitPlatform()
        jvmArgs("--add-modules=jdk.incubator.vector")
    }
}

jmh {
    // Every fork gets the module, so the vector kernels can load; the benchmarks pick scalar or vector themselves
    // -XX:-UseJVMCICompiler: on a GraalVM JDK the default JIT is Graal, whose Vector API support is partial; servers run
    // HotSpot's C2, so that is what is measured. The IgnoreUnrecognized flag keeps this harmless on other JDKs.
    jvmArgs.set(listOf("--add-modules=jdk.incubator.vector", "-XX:+UseParallelGC", "-XX:+IgnoreUnrecognizedVMOptions", "-XX:-UseJVMCICompiler"))
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)
    resultFormat.set("json")
    resultsFile.set(project.file("build/reports/jmh/results.json"))
    timeUnit.set("ms")
    benchmarkMode.set(listOf("AverageTime"))
    forceGC.set(true)
    // ./gradlew jmh -Pbench=Baseline runs only the benchmarks whose name matches
    includes.set(listOf(providers.gradleProperty("bench").getOrElse(".*")))
    // -Pkernels=scalar restricts the kernel benchmarks to one implementation
    providers.gradleProperty("kernels").orNull?.let {
        benchmarkParameters.put("kernels", objects.listProperty<String>().value(listOf(it)))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
