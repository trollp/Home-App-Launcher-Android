# Health Connect planner integration

## Scope and privacy

Phase 2 adds optional, read-only Health Connect context to the day planner. Quiet Home requests these record categories independently:

- sleep sessions and stages;
- exercise sessions;
- heart-rate samples;
- steps;
- total calories burned;
- background health reads, when the device/provider supports that feature.

A denied or malformed category does not suppress the others. Quiet Home reduces records on-device into bounded totals: sleep duration/stage minutes for the last 24 hours, exercise count/minutes and qualifying gym count for seven days, 24-hour heart-rate average/minimum/maximum, and today’s steps/calories. It does not persist raw records, record IDs, sample timestamps, data origins, or medical details. The cached aggregate is used when a background read is unavailable and by the immediate local fallback.

Health access is optional. The launcher and planner continue without it. Permissions can be reviewed or revoked in Health Connect. If **Send planner snapshot to OpenAI** is separately enabled, the bounded health aggregate is part of that disclosed snapshot. No health data is sent merely by granting Health Connect access.

The fallback presents gym counts and sleep duration as neutral observations. The cloud system prompt forbids diagnosis, readiness/recovery inference, and unsupported Garmin metrics such as Body Battery, stress, HRV, training load, and VO2 max.

## Gym qualification

Planner settings provide a minimum exercise duration (1–240 minutes) and a separate **Strength sessions always count** toggle. A Health Connect exercise qualifies as a gym observation when it reaches the configured duration, or when it is classified as strength training and that toggle is enabled. This only affects planner gym progress; it does not alter Health Connect records.

## Foreground, background, and cache behavior

A visible launcher/assistant refresh performs a foreground Health Connect read. The periodic WorkManager refresh identifies itself as background work. Background reads are attempted only when both the Health Connect feature and background permission are available; otherwise the last bounded aggregate is used. Provider absence and collector failures also fail safely to cache where possible. Cached values retain their original collection timestamp and are marked cached in the planner snapshot.

## Required real-device verification

The host build cannot establish device/provider behavior. Verify all of the following on each target phone and account setup:

- **Garmin categories:** confirm Garmin Connect actually writes sleep sessions/stages, exercise sessions (including the strength-training type), heart-rate samples, steps, and total calories to Health Connect. Garmin support, history windows, delayed sync, stage labels, exercise classification, and category toggles can differ by Garmin Connect/device version. Body Battery, stress, HRV, training load, and VO2 max are not requested or supported by this integration.
- **Permissions and provider:** test initial grant, partial grants, revocation, provider update/missing-provider flow, Android 14 permission-usage rationale, and Health Connect on Android 9–13 versus the Android 14 system implementation.
- **Background access:** explicitly grant background Health Connect read access when offered, then test WorkManager refresh under screen-off, doze, battery optimization, and OEM background restrictions. Without it, periodic work intentionally uses cached health.
- **Calendar alongside health:** Outlook must sync calendars into Android’s Calendar Provider; Quiet Home does not use Outlook OAuth. Verify work-profile and MDM policy visibility because enterprise policy can hide or block provider calendars even when Health Connect works.
- **Exact alarms:** meeting preparation still depends on exact-alarm access and OEM scheduling. When unavailable, Quiet Home uses an inexact while-idle reminder; neither mode is safety-critical or guaranteed.

The project keeps `minSdk 26` and `targetSdk 34`. Health Connect collection is available only from Android 9/API 28 when a compatible provider is available.
