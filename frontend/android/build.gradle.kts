// Top-level build file. 모듈 공통 설정만 둔다. 버전은 gradle/libs.versions.toml 한 곳에서 관리한다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
