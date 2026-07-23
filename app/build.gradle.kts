import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val whiteDnsVersionCode = providers.gradleProperty("WHITE_DNS_VERSION_CODE")
    .map { it.toInt() }
    .orElse(13)
val whiteDnsVersionName = providers.gradleProperty("WHITE_DNS_VERSION_NAME")
    .orElse("1.5.1c")

// Release signing is read from a gitignored keystore.properties at the repo root
// (never committed). Absent the file (e.g. a fresh clone), release builds stay
// unsigned instead of failing, so debug work is unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()
val cottenDnsNativeBinaries = fileTree("src/main/jniLibs") {
    include("*/libcottendns_client.so")
}
// CottenDNS is not vendored here: the engine is checked out at a pinned commit
// (see .engine/COTTENDNS_ENGINE_SHA) and built from its own source by `make
// CottenDns` / CI. This file is the input that ties the packaged binaries back
// to that pin.
val cottenDnsEngineShaFile = rootProject.file(".engine/COTTENDNS_ENGINE_SHA")

android {
    namespace = "shop.whitedns.client"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        // Forked app identity: installs side-by-side with the upstream WhiteDNS
        // (namespace stays shop.whitedns.client so the R class / code package is
        // unchanged; only the install id and FileProvider authority shift).
        applicationId = "shop.whitedns.client.c"
        minSdk = 26
        targetSdk = 34
        versionCode = whiteDnsVersionCode.get()
        versionName = whiteDnsVersionName.get()

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyCottenDnsNativeBinaries by tasks.registering {
    group = "verification"
    description = "Fails when packaged CottenDns binaries do not match the pinned engine commit."
    inputs.files(cottenDnsNativeBinaries)
    inputs.file(cottenDnsEngineShaFile)

    doLast {
        val repositoryDir = rootProject.projectDir
        fun commandResult(vararg command: String): Pair<Int, String> {
            val process = ProcessBuilder(*command)
                .directory(repositoryDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return process.waitFor() to output.trim()
        }

        if (!cottenDnsEngineShaFile.exists()) {
            throw GradleException("Missing ${cottenDnsEngineShaFile.relativeTo(projectDir)}. Pin a CottenDNS engine commit before building.")
        }
        val expectedRevision = cottenDnsEngineShaFile.readText().trim()
        if (!expectedRevision.matches(Regex("[0-9a-fA-F]{40}"))) {
            throw GradleException("${cottenDnsEngineShaFile.relativeTo(projectDir)} does not contain a full 40-character commit SHA: $expectedRevision")
        }

        val binaries = cottenDnsNativeBinaries.files.sortedBy { it.path }
        if (binaries.size != 4) {
            throw GradleException("Expected four CottenDns Android binaries, found ${binaries.size}. Run make CottenDns.")
        }

        val revisionPattern = Regex("vcs\\.revision=([0-9a-fA-F]{40})")
        binaries.forEach { binary ->
            val (versionExit, versionOutput) = commandResult("go", "version", "-m", binary.absolutePath)
            val binaryRevision = revisionPattern.find(versionOutput)?.groupValues?.get(1)
            if (versionExit != 0 || !binaryRevision.equals(expectedRevision, ignoreCase = true)) {
                throw GradleException(
                    "Stale CottenDns binary: ${binary.relativeTo(projectDir)}. " +
                        "Expected pinned engine revision $expectedRevision but found ${binaryRevision ?: "no embedded revision"}. " +
                        "Run make CottenDns.",
                )
            }
        }
    }
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("NativeLibs")
}.configureEach {
    dependsOn(verifyCottenDnsNativeBinaries)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
