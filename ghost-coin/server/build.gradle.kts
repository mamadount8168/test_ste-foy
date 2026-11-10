plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.ghost.coin.server.MainKt") }
dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
// Jar autonome : java -jar server-all.jar
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.ghost.coin.server.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
}
