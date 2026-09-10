#!/bin/sh
# Jenkins Freestyle entry point. No production secrets or deployment actions.
set -eu
set +x
cd "${WORKSPACE:?WORKSPACE is required}"
test "$(uname -m)" = x86_64
test ! -f frontend/android/local.properties

SOURCE_SHA=$(git rev-parse HEAD)
EXPECTED_SHA=${gitlabMergeRequestLastCommit:-${gitlabAfter:-}}
if [ -n "$EXPECTED_SHA" ] && [ "$SOURCE_SHA" != "$EXPECTED_SHA" ]; then
    echo 'ERROR: checked-out SHA differs from webhook source SHA.' >&2
    exit 1
fi
printf '[MICON] source_sha=%s build=%s\n' "$SOURCE_SHA" "${BUILD_NUMBER:-local}"

TOOLS="$WORKSPACE/.ci-tools"
mkdir -p "$TOOLS"
JDK_ROOT="$TOOLS/temurin-21.0.12.1_1"
if [ ! -x "$JDK_ROOT/bin/java" ]; then
    JDK_STAGE=$(mktemp -d "$TOOLS/jdk-download.XXXXXX")
    curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 300 \
        'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz' \
        -o "$JDK_STAGE/jdk.tar.gz"
    printf '%s  %s\n' ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 "$JDK_STAGE/jdk.tar.gz" | sha256sum -c -
    mkdir -p "$JDK_STAGE/unpacked"
    tar -xzf "$JDK_STAGE/jdk.tar.gz" --strip-components=1 -C "$JDK_STAGE/unpacked"
    test -x "$JDK_STAGE/unpacked/bin/java"
    test ! -e "$JDK_ROOT"
    mv "$JDK_STAGE/unpacked" "$JDK_ROOT"
fi
export JAVA_HOME="$JDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
java -XshowSettings:properties -version 2>&1 | grep -Eq 'java.specification.version = 21$'

LFS_ROOT="$TOOLS/git-lfs-3.7.1-amd64"
if [ ! -x "$LFS_ROOT/git-lfs-3.7.1/git-lfs" ]; then
    mkdir -p "$LFS_ROOT"
    curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 180 \
        'https://github.com/git-lfs/git-lfs/releases/download/v3.7.1/git-lfs-linux-amd64-v3.7.1.tar.gz' -o "$LFS_ROOT/package.tar.gz"
    printf '%s  %s\n' 1c0b6ee5200ca708c5cebebb18fdeb0e1c98f1af5c1a9cba205a4c0ab5a5ec08 "$LFS_ROOT/package.tar.gz" | sha256sum -c -
    tar -xzf "$LFS_ROOT/package.tar.gz" -C "$LFS_ROOT"
fi
export PATH="$LFS_ROOT/git-lfs-3.7.1:$PATH"
git lfs version

export ANDROID_HOME="$TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDK_TOOLS="$ANDROID_HOME/cmdline-tools/15859902"
if [ ! -x "$SDK_TOOLS/bin/sdkmanager" ]; then
    SDK_STAGE=$(mktemp -d "$TOOLS/sdk-download.XXXXXX")
    curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 300 \
        'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' -o "$SDK_STAGE/sdk.zip"
    printf '%s  %s\n' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 "$SDK_STAGE/sdk.zip" | sha256sum -c -
    unzip -q "$SDK_STAGE/sdk.zip" -d "$SDK_STAGE/unpacked"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    test ! -e "$SDK_TOOLS"
    mv "$SDK_STAGE/unpacked/cmdline-tools" "$SDK_TOOLS"
fi
export PATH="$SDK_TOOLS/bin:$ANDROID_HOME/platform-tools:$PATH"
if [ ! -d "$ANDROID_HOME/platforms/android-37" ] && [ ! -d "$ANDROID_HOME/platforms/android-37.0" ]; then
    echo 'ERROR: install SDK 37 under the already approved Android SDK terms before this build.' >&2
    exit 1
fi
test -x "$ANDROID_HOME/build-tools/36.0.0/apksigner"
echo '[MICON] SDK 37 and Build Tools 36.0.0 verified'

# Each build fetches into an empty, build-specific LFS cache to prove authentication.
# Git Username and Password binding supplies GIT_ASKPASS for this operation.
LFS_VERIFY_STORE=$(mktemp -d "$TOOLS/lfs-auth-check.XXXXXX")
git -c lfs.storage="$LFS_VERIFY_STORE" lfs fetch origin "$SOURCE_SHA"
git -c lfs.storage="$LFS_VERIFY_STORE" \
    -c filter.lfs.process='git-lfs filter-process' \
    -c filter.lfs.clean='git-lfs clean -- %f' \
    -c filter.lfs.smudge='git-lfs smudge -- %f' \
    -c filter.lfs.required=true lfs checkout
git -c lfs.storage="$LFS_VERIFY_STORE" lfs fsck --objects
CATALOG=frontend/android/app/src/main/assets/micon_catalog.sqlite
CATALOG_SHA=$(git show "$SOURCE_SHA:$CATALOG" | sed -n 's/^oid sha256://p')
printf '%s\n' "$CATALOG_SHA" | grep -Eq '^[0-9a-f]{64}$'
printf '%s  %s\n' "$CATALOG_SHA" "$CATALOG" | sha256sum -c -
test "$(head -c 15 "$CATALOG")" = 'SQLite format 3'
echo '[MICON] Fresh authenticated LFS download and SQLite verified'

# Do not pass credential environment variables to the Gradle test/build process.
unset GIT_USERNAME GIT_PASSWORD GIT_ASKPASS SSH_ASKPASS
export GIT_TERMINAL_PROMPT=0
export API_BASE_URL='https://j15b110.p.ssafy.io/api/v1/'
export MICON_CI=true
export GRADLE_USER_HOME="$WORKSPACE/.gradle"
cd frontend/android
sh ./gradlew --no-daemon --stacktrace clean :app:testDebugUnitTest :app:assembleDebug
grep -F 'API_BASE_URL = "https://j15b110.p.ssafy.io/api/v1/"' app/build/generated/source/buildConfig/debug/com/micon/app/BuildConfig.java
APK=app/build/outputs/apk/debug/app-debug.apk
test -s "$APK"
unzip -tq "$APK"
test "$(unzip -p "$APK" assets/micon_catalog.sqlite | head -c 15)" = 'SQLite format 3'
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify "$APK"
sha256sum "$APK"
printf 'source_sha=%s\nbuild_number=%s\napi_base_url=%s\n' "$SOURCE_SHA" "${BUILD_NUMBER:-local}" "$API_BASE_URL" > app/build/outputs/apk/debug/build-metadata.properties
sha256sum "$APK" > app/build/outputs/apk/debug/app-debug.apk.sha256
echo '[MICON] API_BASE_URL injection, APK archive, SQLite asset and signature verified'
