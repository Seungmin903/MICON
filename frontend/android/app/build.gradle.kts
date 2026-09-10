import java.util.Properties
import java.net.URI
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // AGP 9.x 는 Kotlin 컴파일을 내장한다. org.jetbrains.kotlin.android 를 추가하지 말 것.
}

// ---------------------------------------------------------------------------
// local.properties -> Gradle -> BuildConfig / resValue / manifestPlaceholder
// 실제 키는 Git 에 커밋하지 않는다. local.properties.example 참고.
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun localSetting(name: String, default: String = ""): String =
    localProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: System.getenv(name)?.takeIf(String::isNotBlank)
        ?: default

val engineBaseUrl = localSetting("ENGINE_BASE_URL", "http://10.0.2.2:8787/")
val apiBaseUrl = localSetting("API_BASE_URL", "http://10.0.2.2:8080/api/v1/")
val rangePreviewBaseUrl = localSetting("RANGE_PREVIEW_BASE_URL", "http://127.0.0.1:8789/")
val recommendBaseUrl = localSetting("RECOMMEND_BASE_URL", "")   // 비어 있으면 앱 안 로컬 계산만 사용
val googleWebClientId = localSetting("GOOGLE_WEB_CLIENT_ID")
val kakaoNativeAppKey = localSetting("KAKAO_NATIVE_APP_KEY")
val naverClientId = localSetting("NAVER_CLIENT_ID")
val naverClientSecret = localSetting("NAVER_CLIENT_SECRET")

val releaseSigningKeys = listOf(
    "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"
)
val hasReleaseSigning = releaseSigningKeys.all { localSetting(it).isNotBlank() }
val releaseVersionCode = localSetting("RELEASE_VERSION_CODE")
val releaseVersionName = localSetting("RELEASE_VERSION_NAME")
val previousReleaseVersionCode = localSetting("PREVIOUS_RELEASE_VERSION_CODE")

// Do not silently produce an unsigned or emulator-targeting release APK.
val validateReleaseConfiguration by tasks.registering {
    notCompatibleWithConfigurationCache("Release signing settings must not be cached")
    doLast {
        check(hasReleaseSigning) { "Release signing settings are required; values are not logged." }
        val store = File(localSetting("RELEASE_STORE_FILE"))
        check(store.isAbsolute && store.isFile && store.name != "debug.keystore") {
            "A real Release keystore at an absolute path is required."
        }
        val code = releaseVersionCode.toIntOrNull()
        val previous = previousReleaseVersionCode.toIntOrNull()
        check(code != null && code in 1..2100000000 && previous != null && previous >= 0 && code > previous) {
            "Explicit increasing RELEASE_VERSION_CODE and PREVIOUS_RELEASE_VERSION_CODE are required."
        }
        check(releaseVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) {
            "RELEASE_VERSION_NAME must be a filename-safe version."
        }
        for (value in listOf(apiBaseUrl, engineBaseUrl)) {
            val uri = runCatching { URI(value) }.getOrNull()
            check(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null &&
                uri.path.endsWith("/") && uri.host !in listOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]")) {
                "Release API and engine URLs must be explicit HTTPS base URLs, not emulator defaults."
            }
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseConfiguration)
}

android {
    namespace = "com.micon.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Android and the PC model evaluate the same checked-in pitch fixtures.
    sourceSets.getByName("test").resources.srcDir(rootProject.file("../../tests/fixtures"))

    defaultConfig {
        applicationId = "com.micon.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = releaseVersionCode.toIntOrNull() ?: 3
        versionName = releaseVersionName.ifBlank { "0.1.0" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ENGINE_BASE_URL", "\"$engineBaseUrl\"")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "RECOMMEND_BASE_URL", "\"$recommendBaseUrl\"")

        resValue("string", "google_web_client_id", googleWebClientId)
        resValue("string", "kakao_native_app_key", kakaoNativeAppKey)
        resValue("string", "naver_client_id", naverClientId)
        resValue("string", "naver_client_secret", naverClientSecret)
        manifestPlaceholders["kakao_native_app_key"] = kakaoNativeAppKey
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(localSetting("RELEASE_STORE_FILE"))
                storePassword = localSetting("RELEASE_STORE_PASSWORD")
                keyAlias = localSetting("RELEASE_KEY_ALIAS")
                keyPassword = localSetting("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "HTTP_LOGGING", "true")
            buildConfigField("boolean", "GUIDED_RANGE_STANDALONE", localSetting("GUIDED_RANGE_STANDALONE", "false").toBoolean().toString())
            buildConfigField("String", "RANGE_PREVIEW_BASE_URL", "\"$rangePreviewBaseUrl\"")
            // Diagnostic capture is opt-in; never arm it in a normal test build.
            val diagnosticToken = localSetting("GUIDED_RANGE_DIAGNOSTIC_TOKEN")
            require(diagnosticToken.matches(Regex("[A-Za-z0-9_-]{0,80}")))
            buildConfigField("String", "GUIDED_RANGE_DIAGNOSTIC_TOKEN", "\"$diagnosticToken\"")
        }
        release {
            buildConfigField("boolean", "HTTP_LOGGING", "false")
            buildConfigField("boolean", "GUIDED_RANGE_STANDALONE", "false")
            buildConfigField("String", "RANGE_PREVIEW_BASE_URL", "\"\"")
            buildConfigField("String", "GUIDED_RANGE_DIAGNOSTIC_TOKEN", "\"\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    androidResources {
        // 개발용 샘플 DB(assets/micon_catalog.sqlite)를 APK 안에서 압축하지 않음 -> 첫 실행 복사 빠름
        noCompress += "sqlite"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    // java.time 등 JDK API 를 minSdk 24 에서도 사용할 수 있도록 백포트
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- AndroidX ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.security.crypto)

    // --- Compose ---
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Media3 (반주 재생) ---
    implementation(libs.bundles.media3)

    // --- Network / JSON / Async ---
    implementation(libs.bundles.network)
    implementation(libs.kotlinx.coroutines.android)

    // --- Image ---
    implementation(libs.coil.compose)

    // --- Login (Google / Kakao / Naver). 필요할 때 주석 해제하고 MiconApplication 의 Kakao init 도 켠다 ---
    // implementation(libs.bundles.login)

    // --- Test ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
