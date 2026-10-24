import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.jetbrains.dokka-javadoc") version "2.2.0"
}

repositories {
    mavenCentral()
}

kotlin {
    // Build with JDK 21, emit Java 11 bytecode so older JVMs can consume the library.
    jvmToolchain(21)
    explicitApi()
    // Compile against an older Kotlin API/stdlib so consumers on older Kotlin versions can use it.
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xjdk-release=11")
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("differential")
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Compares this library with Python `packaging` on generated inputs. The cases file is
// produced by tools/differential/generate.py; see CONTRIBUTING.md.
val differentialTest = tasks.register<Test>("differentialTest") {
    description = "Runs the differential test against a cases file produced by Python packaging."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("differential")
    }
    val casesFile = providers.gradleProperty("differential.cases")
    inputs.property("cases", casesFile.orElse(""))
    systemProperty("differential.cases", casesFile.getOrElse(""))
    outputs.upToDateWhen { false }
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The library targets Java 11 bytecode; run the whole suite on a Java 11 runtime to prove it.
val testOnJava11 = tasks.register<Test>("testOnJava11") {
    description = "Runs the unit and conformance tests on a Java 11 runtime."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(11)) })
    useJUnitPlatform {
        excludeTags("differential")
    }
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    description = "Assembles a Javadoc jar from Dokka's Javadoc output."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

tasks.jar {
    manifest {
        attributes(
            "Automatic-Module-Name" to "dev.greenflagged.pypackaging",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
    from(listOf("LICENSE", "NOTICE", "LICENSE-UNICODE")) { into("META-INF") }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "python-packaging"
            from(components["java"])
            artifact(javadocJar)
            pom {
                name.set("python-packaging")
                description.set(
                    "Python packaging standards for the JVM: PEP 440 versions and specifiers, " +
                        "PEP 508 requirements and markers, and PEP 503 name normalization. " +
                        "A conformance-tested port of pypa/packaging.",
                )
                url.set("https://github.com/greenflagged/python-packaging-kotlin")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("greenflagged")
                        name.set("Greenflagged")
                        url.set("https://github.com/greenflagged")
                    }
                }
                scm {
                    url.set("https://github.com/greenflagged/python-packaging-kotlin")
                    connection.set("scm:git:https://github.com/greenflagged/python-packaging-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/greenflagged/python-packaging-kotlin.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/greenflagged/python-packaging-kotlin/issues")
                }
            }
        }
    }
    repositories {
        // Releases are staged to a local directory, zipped, and uploaded to the Sonatype
        // Central Portal by .github/workflows/release.yml. See RELEASING.md.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    // In CI the key comes from the SIGNING_KEY / SIGNING_PASSWORD secrets (see RELEASING.md).
    // SIGNING_KEY_ID is optional: set it to sign with a subkey instead of the primary key.
    val signingKey = providers.environmentVariable("SIGNING_KEY").filter { it.isNotBlank() }
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").filter { it.isNotBlank() }
    val signingKeyId = providers.environmentVariable("SIGNING_KEY_ID").filter { it.isNotBlank() }
    if (signingKey.isPresent) {
        if (signingKeyId.isPresent) {
            useInMemoryPgpKeys(signingKeyId.get(), signingKey.get(), signingPassword.orNull)
        } else {
            useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        }
    }
    // Release builds sign. The release workflow stages with -Punsigned and signs in a
    // separate job with gpg, so no build code runs while the signing key is present.
    val unsigned = providers.gradleProperty("unsigned").isPresent
    setRequired {
        !unsigned && !version.toString().endsWith("SNAPSHOT") && gradle.taskGraph.allTasks.any { it.name.startsWith("publish") }
    }
    sign(publishing.publications["maven"])
}
