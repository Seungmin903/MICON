#!/bin/sh
set -eu

release_sha="${1:-}"
expected_archive_sha="${2:-}"
case "$release_sha" in
    *[!0-9a-f]*|'') echo "invalid release SHA" >&2; exit 2 ;;
esac
case "$expected_archive_sha" in
    *[!0-9a-f]*|'') echo "invalid archive SHA-256" >&2; exit 2 ;;
esac
if [ "${#release_sha}" -ne 40 ] || [ "${#expected_archive_sha}" -ne 64 ]; then
    echo "invalid release identifiers" >&2
    exit 2
fi

release_root="$HOME/micon-releases"
incoming_dir="$release_root/incoming"
archive="$incoming_dir/micon-backend-$release_sha.tar.gz"
destination="$release_root/$release_sha"

if [ ! -f "$archive" ]; then
    echo "release archive is missing" >&2
    exit 1
fi
actual_archive_sha="$(sha256sum "$archive" | cut -d ' ' -f 1)"
if [ "$actual_archive_sha" != "$expected_archive_sha" ]; then
    echo "release archive checksum mismatch" >&2
    exit 1
fi
if [ -e "$destination" ]; then
    echo "release destination already exists; refusing overwrite" >&2
    exit 1
fi

temporary_dir="$(mktemp -d "$release_root/.extract-$release_sha.XXXXXX")"
case "$temporary_dir" in
    "$release_root"/.extract-*) ;;
    *) echo "unsafe extraction directory" >&2; exit 1 ;;
esac
complete=false
cleanup() {
    if [ "$complete" = false ] && [ -d "$temporary_dir" ]; then
        find "$temporary_dir" -mindepth 1 -delete
        rmdir "$temporary_dir"
    fi
}
trap cleanup EXIT HUP INT TERM

if tar -tzf "$archive" | awk '/^\// || /(^|\/)\.\.($|\/)/ { found=1 } END { exit found ? 0 : 1 }'; then
    echo "release archive contains an unsafe path" >&2
    exit 1
fi
tar -xzf "$archive" -C "$temporary_dir" --no-same-owner --no-same-permissions
printf '%s\n' "$release_sha" > "$temporary_dir/.micon-release-sha"
chmod 0444 "$temporary_dir/.micon-release-sha"
mv -T "$temporary_dir" "$destination"
complete=true
trap - EXIT HUP INT TERM

sudo chown -R root:root "$destination"
sudo "$destination/infra/deploy/deploy_backend_release.sh" "$release_sha"
