import groovy.util.Node
import groovy.xml.XmlUtil

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.protobuf)
}

val toolchainVersion = 21

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(toolchainVersion)
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(toolchainVersion)
}

val gitHashShort = runCatching {
    providers.exec {
        commandLine(
            "git",
            "rev-parse",
            "--short",
            "HEAD"
        )
    }.standardOutput.asText.get().trim()
}.getOrNull()

val commits = runCatching {
    providers.exec {
        commandLine("git", "log", "--pretty=format:%h %s")
    }.standardOutput.asText.get().trim()
}.getOrNull()
val commitCount = commits?.lines()?.size

android {
    namespace = "nodomain.freeyourgadget.gadgetbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "nodomain.freeyourgadget.gadgetbridge"
        // Note: Bump together with source and target compatibility version.
        minSdk = 23
        // Note: Bump together with toolchain version.
        targetSdk = 34

        // Note: Bump version name and code together.
        versionName = "0.91.1"
        versionCode = 248

        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "GIT_HASH_SHORT", "\"$gitHashShort\"")
        buildConfigField("boolean", "INTERNET_ACCESS", "false")
        resValue("string", "applicationId", "$applicationId")
    }

    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/sources/gbdao"))
            res.srcDir(layout.buildDirectory.dir("generated/res/changelog"))
        }
    }

    val hasNightlySigningConfig = !System.getProperty("nightly_store_file").isNullOrBlank()

    signingConfigs {
        if (hasNightlySigningConfig) {
            create("nightly") {
                storeFile = file(System.getProperty("nightly_store_file"))
                storePassword = System.getProperty("nightly_store_password")
                keyAlias = System.getProperty("nightly_key_alias")
                keyPassword = System.getProperty("nightly_key_password")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }

        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-${gitHashShort}"

            signingConfig =
                if (hasNightlySigningConfig) signingConfigs.getByName("nightly") else signingConfigs.getByName(
                    "debug"
                )
        }

        create("nopebble") {
            initWith(getByName("nightly"))
            applicationIdSuffix = ".nightly_nopebble"
        }
    }

    androidComponents {
        onVariants { variant ->
            if (listOf("nightly", "nopebble").contains(variant.buildType)) {
                variant.outputs.forEach { output ->
                    output.versionCode.set(commitCount)
                }
            }
        }
    }

    // Note: Since there is only one flavor dimension it is used by default.
    flavorDimensions += "device"
    productFlavors {
        create("mainline") {
            isDefault = true
        }
        create("banglejs") {
            applicationId = "com.espruino.gadgetbridge.banglejs"
            // TODO: Figure out if we can safely remove the suffix and still upgrade on F-Droid.
            //  This would allow us to get the commit hash into the version name on nightly builds.
            versionNameSuffix = "-banglejs"
            buildConfigField("boolean", "INTERNET_ACCESS", "true")
        }
    }

    lint {
        abortOnError = true
        lintConfig = file(layout.projectDirectory.file("src/main/lint.xml"))
        htmlReport = true
        baseline = file("lint-baseline.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.constraintlayout)
    implementation(libs.bundles.androidx.camera)

    testImplementation(libs.junit)
    testImplementation(libs.mockito)
    testImplementation(libs.robolectric)
    testImplementation(libs.hamcrest)

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.kotlin.bom))
    implementation(libs.kotlin.stdlib)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.gridlayout)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.material)
    implementation(libs.flexbox)
    implementation(libs.gson)

    implementation(libs.dfu)
    implementation(libs.logback.android)
    implementation(libs.slf4j.api)
    implementation(libs.mpandroidchart)
    implementation(libs.durationformatter)
    implementation(libs.ckchangelog)
    implementation(libs.solarpositioning)
    implementation(libs.cbor)
    // use pristine greendao instead of our custom version, since our custom jitpack-packaged
    // version contains way too much and our custom patches are in the generator only.
    implementation(libs.greendao)
    implementation(libs.apache.commons.lang3)
    implementation(libs.cyanogenmod.sdk)
    implementation(libs.colorpicker)
    implementation(libs.bundles.android.emojify)
    implementation(libs.protobuf.lite)
    implementation(libs.okhttp)
    implementation(libs.msgpack)
    implementation(libs.searchpreference)

    implementation(libs.bundles.mapsforge)
    implementation(libs.androidsvg)
    implementation(libs.jsoup)

    // Bouncy Castle is included directly in GB, to avoid pulling the entire dependency
    // It's included in the org.bouncycastle.shaded package, to fix conflicts with roboelectric
    //implementation('org.bouncycastle:bcpkix-jdk18on:1.76')
    //implementation('org.bouncycastle:bcprov-jdk18on:1.76')

    // Android SDK bundles org.json, but we need an actual implementation(to replace the stubs in tests)
    testImplementation(libs.json)

    // Needed for Armenian transliteration
    implementation(libs.ahocorasick)

    // Android Health Connect
    implementation(libs.guava) // This is needed because CameraActivity.java line 41 doesn't have a ListenableFuture package
    implementation(libs.androidx.health.connect.client)
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

evaluationDependsOn(":GBDaoGenerator")

val generateSources = project(":GBDaoGenerator").tasks.named("generateSources")

val generateChangelog by tasks.registering {
    val outputFile = layout.buildDirectory.file("generated/res/changelog/xml/changelog_git.xml")

    inputs.file(rootProject.layout.projectDirectory.dir(".git/HEAD"))
    outputs.file(outputFile)

    doLast {
        val outputFile = outputFile.get().asFile
        outputFile.parentFile.mkdirs()

        val root = Node(null, "changelog")

        commits ?: run {
            logger.warn("Skipping changelog generation: git is not available")
            outputFile.writeText(XmlUtil.serialize(root))
            return@doLast
        }
        var commitCount = commitCount!!
        var translations = 0

        commits.trim().lines().take(100).forEach { line ->
            val (hash, message) = line.split(" ", limit = 2)

            if (message.contains("Translated using Weblate")) {
                if (translations == 0) {
                    val release = Node(
                        root,
                        "release",
                        mapOf("version" to hash, "versioncode" to commitCount--)
                    )
                    Node(
                        release,
                        "change",
                        emptyMap<String, Any>(),
                        "Sync translations from Weblate"
                    )
                }
                translations++;
                return@forEach
            }

            val release =
                Node(root, "release", mapOf("version" to hash, "versioncode" to commitCount--))
            Node(release, "change", emptyMap<String, Any>(), message)
        }

        outputFile.writeText(XmlUtil.serialize(root))
    }
}

tasks.preBuild {
    dependsOn(generateSources)
    dependsOn(generateChangelog)
}

val cleanGenerated by tasks.registering(Delete::class) {
    delete(fileTree("src/main/java/nodomain/freeyourgadget/gadgetbridge/entities") {
        include("**/*.java")
        exclude("**/Abstract*.java")
        exclude("**/GenericActivitySample.java")
    })
}

tasks.named("clean") {
    dependsOn(cleanGenerated)
}

tasks.withType<Test>().configureEach {
    systemProperty("MiFirmwareDir", System.getProperty("MiFirmwareDir", null))
    systemProperty(
        "logback.configurationFile",
        "${layout.projectDirectory.file("src/main/assets/logback.xml")}"
    )
    systemProperty("GB_LOGFILES_DIR", "$temporaryDir")
}
