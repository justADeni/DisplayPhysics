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
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    // Paper provides JOML, fastutil and the NMS classes the packet layer talks to at runtime.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
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
