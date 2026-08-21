plugins {
    application
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "nodomain.freeyourgadget.gadgetbridge.codegen.main.KaitaiCodeGen"
}

dependencies {
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
    testImplementation(libs.hamcrest)
}

tasks.register<JavaExec>("genKaitai") {
    description = "Generates Kotlin code from Kaitai Structs"

    val schemaDir = project.file("src/main/resources")
    val outDir = project.rootProject.file("app/build/generated/sources/kaitai")

    inputs.dir(schemaDir)
    outputs.dir(outDir)

    mainClass = application.mainClass
    args(schemaDir.absolutePath)
    args(outDir.absolutePath)
    classpath = sourceSets.main.get().runtimeClasspath

    if (gradle.startParameter.logLevel <= LogLevel.INFO) {
        jvmArgs("-Dverbose=true")
    }
}
