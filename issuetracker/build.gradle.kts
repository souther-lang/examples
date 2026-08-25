import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.2.21"
    // Kotlin classes are final by default, and Spring needs @Configuration and @Transactional classes
    // open to proxy them. This plugin opens exactly those.
    kotlin("plugin.spring") version "2.2.21"
    // Declared for `bootRun`. bootJar is turned off below, so the build's artifact stays a plain jar
    // like the Maven examples'.
    id("org.springframework.boot") version "4.0.7"
    // Compiles src/main/souther into the main source set: the generated classes reach the Kotlin
    // compilation, the test compile class path and the jar, and the runtime they call is added at
    // the version that compiled them.
    id("org.souther-lang.souther") version "0.1.0"
}

souther {
    // A -SNAPSHOT is published nowhere, so it resolves from the local repository that `mvn install`
    // of the compiler put it in — settings.gradle.kts declares mavenLocal() for that.
    southerVersion = "0.1.0-SNAPSHOT"
}

val raohVersion = "0.7.0"

java {
    // Built with the JDK the rest of the examples are built with, but everything this build emits
    // targets 21: that is Souther's runtime floor (souther-runtime and the generated classes target
    // it), so the Kotlin boundary asks no more of a JVM than the generated code it drives.
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    // souther-runtime is not declared: the plugin adds it at the version of the Souther that
    // compiled the model, so there is no second version to keep in step with the first.
    implementation("net.unit8.raoh:raoh:$raohVersion")

    // reflect is what Spring reads constructor parameter names and nullability through. There is no
    // jackson-module-kotlin: no request or response shape is a Kotlin data class — every one of them
    // is a Souther data, decoded and encoded by the derived codec.
    implementation(kotlin("reflect"))

    // The boundary that actually runs: Boot's standard starters. DataSource / DSLContext /
    // TransactionManager and schema.sql execution are all left to autoconfig.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    runtimeOnly("com.h2database:h2")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // Read the JSR-305 nullability annotations Spring carries as strict Kotlin types rather than
        // platform types. Souther's own generated types say the same thing in JSpecify, which Kotlin
        // reads strictly on its own: souther-runtime marks its packages, and every generated class
        // carries @NullMarked itself, so nothing in this build has to say it.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = false }
}

// No repackage: the build's artifact is a plain jar, as the Maven examples' are.
tasks.bootJar { enabled = false }

tasks.jar {
    enabled = true
    archiveClassifier.set("")
}
