# Saved profile / recommendation refresh

- Dispatch suspends an AFTER_COMMIT listener's completed transaction resources.
  Lifecycle writes each use REQUIRES_NEW; the AI HTTP call is outside transactions.
- V29 adds nullable immutable `source_session_id` and `profile_version` snapshots.
  Historical recommendations are deliberately not backfilled from mutable profiles.
  Unknown or superseded identities cannot be returned by latest/detail/song-fit.
- Creation locks the profile and verifies that assembled input still belongs to
  its session/version. A previous version's in-flight job cannot block a new one.
- Recommendation responses add `profileId`, `sourceSessionId`, `profileVersion`.
  Android validates all three against the server's saved profile.
- Voice-data deletion clears the account's saved profile and draft; confirmed
  missing server profiles clear the report cache, but network failures do not.
- Profile changes and recommendation-tab re-entry reload recommendations.
  Visible GENERATING results are polled for up to two minutes. Leaving cancels
  polling; request generations prevent late old responses from replacing new state.

Regression coverage includes real Spring AFTER_COMMIT commits/rollback/failure,
profile replacement during generation, missing/stale recommendation identities,
cache clearing versus offline failure, late responses, tab re-entry and polling.
Device microphone remeasurement/installation is a separate manual acceptance check.

Spring transaction semantics reference:
https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html
