plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.register<JavaExec>("demo") {
    group = "verification"
    description = "Синтетическая смена: XLSX + GeoJSON в build/demo"
    mainClass.set("ai.roadscan.core.demo.DemoMain")
    classpath = sourceSets["main"].runtimeClasspath
    args(layout.buildDirectory.dir("demo").get().asFile.absolutePath)
}
