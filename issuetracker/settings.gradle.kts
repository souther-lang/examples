// issuetracker is its own Gradle build rather than a module of the Maven reactor above it — the same
// arrangement account has for Clojure, and for the same reason: the example's point is the toolchain
// its boundary language is actually used with. So nothing here is inherited from ../pom.xml, and the
// versions the parent used to supply are declared in build.gradle.kts.
rootProject.name = "issuetracker"

dependencyResolutionManagement {
    repositories {
        // souther.version is a -SNAPSHOT that is published nowhere, so it is resolved from the local
        // repository that `mvn install` of the compiler put it in.
        mavenLocal()
        mavenCentral()
    }
}
