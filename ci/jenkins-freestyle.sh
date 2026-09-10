#!/bin/sh
# Copy this bootstrap into the Jenkins Freestyle Execute shell field.
set -eu
set +x
cd "${WORKSPACE:?}"
if [ "${gitlabSourceRepoName:-S15P21B110}" != S15P21B110 ] || [ "${gitlabSourceNamespace:-s15-ai-speech-sub1}" != s15-ai-speech-sub1 ]; then
    echo 'ERROR: only this project is allowed' >&2
    exit 1
fi
EXPECTED_SHA=${gitlabMergeRequestLastCommit:-${gitlabAfter:-}}
if [ -n "$EXPECTED_SHA" ]; then
    printf '%s\n' "$EXPECTED_SHA" | grep -Eq '^[0-9a-f]{40}$'
    # SCM fetched only the fixed project. Never fetch a URL from the webhook.
    git cat-file -e "$EXPECTED_SHA^{commit}"
    GIT_LFS_SKIP_SMUDGE=1 git checkout --detach "$EXPECTED_SHA"
fi
ACTUAL_SHA=$(git rev-parse HEAD)
if [ -n "$EXPECTED_SHA" ] && [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    echo 'ERROR: webhook SHA differs from checked-out SHA' >&2
    exit 1
fi
export GIT_COMMIT="$ACTUAL_SHA"
printf '[MICON] verified_build_sha=%s\n' "$ACTUAL_SHA"
test -f ci/android-ci.sh
sh ci/android-ci.sh
