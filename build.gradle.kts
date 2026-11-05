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

// Code that needs Paper/Folia-only API (regionized schedulers) lives in its own source set, compiled
// against paper-api and packed into the same jar. It is only loaded when the server has that API.
val paper: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
}

// The SIMD kernels use the incubating Vector API, which needs --add-modules jdk.incubator.vector. They live in
// their own source set so the rest of the library neither compiles against nor loads that module; the jar
// carries them, and they are only instantiated when the running JVM has the module.
val vector: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
}

val vectorModule = "--add-modules=jdk.incubator.vector"

dependencies {
    "paperCompileOnly"("io.papermc.paper:paper-api:26.1.2.build.+")
    "paperCompileOnly"("it.unimi.dsi:fastutil:8.5.15")

    // Compiled against Spigot's API so nothing Paper-only can slip in. The server provides JOML and fastutil.
    compileOnly("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    compileOnly("it.unimi.dsi:fastutil:8.5.15")

    testImplementation("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    testImplementation("it.unimi.dsi:fastutil:8.5.15")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(vector.output)

    // Benchmarks run against the same classes the tests do, plus the vector kernels
    jmhImplementation("org.spigotmc:spigot-api:26.1.2-R0.1-SNAPSHOT")
    jmhImplementation("it.unimi.dsi:fastutil:8.5.15")
    jmhImplementation(vector.output)
}

tasks {
    jar {
        from(paper.output)
        from(vector.output)
    }
    named<Jar>("sourcesJar") {
        from(paper.allSource)
        from(vector.allSource)
    }
    compileJava {
        options.release = 25
    }
    named<JavaCompile>("compileVectorJava") {
        // --release and --add-modules can't be combined, so this one compiles against the toolchain's own JDK
        options.compilerArgs.add(vectorModule)
    }
    named<JavaCompile>("compileJmhJava") {
        options.compilerArgs.add(vectorModule)
    }
    javadoc {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
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
