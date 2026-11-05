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

dependencies {
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
