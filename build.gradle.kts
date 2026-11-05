plugins {
    `java-library`
    `maven-publish`
}

group = "com.github.justadeni"
version = "0.1.0"
description = "Rigid-body and debris physics for groups of block displays, rendered with packets."

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
}

tasks {
    jar {
        from(paper.output)
    }
    named<Jar>("sourcesJar") {
        from(paper.allSource)
    }
    compileJava {
        options.release = 25
    }
    javadoc {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
    test {
        useJUnitPlatform()
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
