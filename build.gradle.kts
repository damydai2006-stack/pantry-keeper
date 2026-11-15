plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("repo.root", rootProject.projectDir.absolutePath)
        systemProperty("testdata.dir", rootProject.file("testdata").absolutePath)
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
