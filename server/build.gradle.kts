plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

application {
    mainClass.set("com.pantrykeeper.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.zonky) {
        exclude(group = "io.zonky.test.postgres")
    }
    testRuntimeOnly(libs.zonky.pg)
}

tasks.register<JavaExec>("smokeServer") {
    group = "application"
    description = "Local embedded-Postgres server for the Android emulator smoke. Credentials stay in /tmp."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.pantrykeeper.server.SmokeServerKt")
    workingDir = layout.buildDirectory.dir("smoke-server").get().asFile.apply { mkdirs() }
}

tasks.shadowJar {
    archiveBaseName.set("pantry-server")
    archiveClassifier.set("")
    // Shadow 9 defaults to DuplicatesStrategy.EXCLUDE, which drops the second
    // META-INF/services file before mergeServiceFiles() can see it. Flyway then
    // keeps a single plugin registry and migrate NPEs (DryRunConfigurationExtensionStub).
    mergeServiceFiles()
    filesMatching("META-INF/services/**") {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    }
}

tasks.register<JavaExec>("verifyShadowMigrate") {
    group = "verification"
    description = "Build the fat jar and run Flyway migrate from it against embedded PostgreSQL."
    dependsOn(tasks.shadowJar)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.pantrykeeper.server.ShadowMigrateCheckKt")
    args(tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
}
