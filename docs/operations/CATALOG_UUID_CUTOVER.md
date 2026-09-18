# UUID catalog cutover

V28 stores `song_id` as an opaque string of at most 64 characters. Preserve the
source UUID; never regenerate it from a TJ number or cast it to an integer.
Android routes/DTOs and AI catalog snapshots support these IDs. Positive numeric
IDs from previous releases remain readable during the rollout. User, recording,
analysis, practice and setlist primary keys remain numeric.

## Release order

1. Validate the exact same selected ID set across source metadata, MR files and
   both embedding models. Exclude blocked tracks consistently from every asset.
2. Upload into new S3 prefixes, without replacing the active catalog. Check object
   counts, byte sizes, SHA-256, SSE-S3 and actual backend/AI read permissions.
3. Back up the current DB and verify restoration into isolated MySQL. Rehearse
   the selected SQL twice; confirm stable IDs and unchanged protected user data.
4. Merge reviewed code into develop, then main. Deploy the exact main commit,
   including the string-ID aware MR deployment gate. Distribute the compatible
   Android build and update the actual AI runtime before exposing UUID songs.
5. Import song data and register the immutable selected embedding batch. Build
   the recommendation index AND its matching range profiles with the same ID
   set. Switch the active batch using the expected revision, then refresh every
   AI worker and verify real recommendation IDs and MR playback.
6. Only after these checks, remove the explicitly approved old catalog records
   and associated references using a fixed deletion manifest. Preserve users,
   voice profiles and original `voice-recordings/` objects. Back up any S3
   objects that cannot be recovered through versioning before deleting them.

S3 upload completion is not DB activation, and backend deployment is not AI
runtime deployment. The actual AI can run on a separate Tailscale-connected PC.
Do not mark the cutover complete until that runtime has loaded the new catalog.

## Verification

- Android: `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`.
- Backend: `test bootJar` and `infra/contracts` tests.
- AI: embedding-catalog tests, catalog-link tests and composition tests.
- Deploy: `python -m unittest discover -s infra/deploy -p 'test_*.py'`.

The Android contract tests cover UUID JSON, legacy numeric responses, navigation,
HTTP paths, outgoing request bodies and rejection of unsafe path identifiers.
