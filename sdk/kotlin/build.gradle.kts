import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    `maven-publish`
}

group = "io.github.gekkotron"
if (version == "unspecified") version = "0.0.0-SNAPSHOT"

repositories { mavenCentral() }

kotlin {
    explicitApi()
    jvmToolchain(17)

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        testRuns.named("test") {
            executionTask.configure { useJUnitPlatform { excludeTags("integration") } }
        }
    }
    // iOS: the phone and the Apple Silicon simulator (no iosX64).
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            // GatewayException.Transport's cause type (a typealias of java.io.IOException on the JVM).
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")
            // KtorTransport, the iOS transport. Common so the JVM tests exercise it with MockEngine.
            implementation("io.ktor:ktor-client-core:3.6.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("io.ktor:ktor-client-mock:3.6.1")
        }
        jvmMain.dependencies {
            // The JVM keeps OkHttp 4: Ktor's OkHttp engine would force OkHttp 5 on every consumer.
            api("com.squareup.okhttp3:okhttp:4.12.0")
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform("org.junit:junit-bom:5.11.3"))
            implementation("org.junit.jupiter:junit-jupiter")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
            implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.6.1")
            // AES-GCM: iOS has no public API for it outside CryptoKit.
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("dev.whyoleg.cryptography:cryptography-provider-cryptokit:0.6.0")
        }
        // kotlinx-coroutines-test's virtual-time APIs (currentTime, advanceTimeBy) are
        // @ExperimentalCoroutinesApi; test code opts in without per-usage annotations.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs tests against a live gateway (see GatewayIntegrationTest)."
    group = "verification"
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })
    useJUnitPlatform { includeTags("integration") }
    outputs.upToDateWhen { false }
}

kover {
    currentProject { instrumentation { disabledForTestTasks.add("integrationTest") } }
    reports { verify { rule { minBound(85) } } }
}

publishing {
    repositories {
        // Static Maven repo the release workflow pushes to the gh-pages branch (README: "Install").
        maven {
            name = "pages"
            url = uri(layout.buildDirectory.dir("pages-repo"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Nidoo Gateway Client")
            description.set("Kotlin Multiplatform client for the Nidoo end-to-end encrypted HTTP/MQTT gateway.")
            url.set("https://github.com/Gekkotron/Nidoo-gateway")
            licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
            developers { developer { id.set("Gekkotron"); name.set("Gekkotron") } }
            scm { url.set("https://github.com/Gekkotron/Nidoo-gateway") }
        }
    }
}
