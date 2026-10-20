# Quiet Home

Quiet Home is a local-first, text-focused Android home launcher. It keeps the existing launcher, app rules, grayscale experiment, usage tools, and a Phase 1 day planner in one deliberately quiet interface.

## Phase 1 planner

The planner builds a bounded view of the next 24 hours from Android's Calendar Provider, local tasks, profile constraints and goals, cached Stockholm weather, optional Usage Access summaries, and a 14-day local activity log. A deterministic on-device fallback is always available. The planner does not connect directly to any calendar vendor: calendars must already be synced into Android's Calendar Provider by an account or calendar app.

Cloud planning is **off by default**. Adding an API key does not enable it, and upgrades never infer consent from an old key or provider setting. If the user separately enables **Send planner snapshot to OpenAI**, Quiet Home sends the disclosed snapshot to OpenAI. That snapshot can include calendar titles and locations, task titles, profile constraints/goals, free slots, usage summaries, and recent planner activity. Disable the toggle or delete the key to stop cloud calls. Keys are encrypted with Android Keystore, excluded from backup, and should be dedicated, revocable, and spending-limited.

Model output is untrusted. Quiet Home validates reference IDs, times, installed/non-hidden app packages, free calendar slots, text and action arguments. Maps URLs are constructed locally from the current event location. Mutations require confirmation where appropriate and are checked again against current local state.

Quiet Home has no analytics, ads, app account, or cloud backup. Local fallback, tasks, profile, activity and rules work without cloud planning. SMHI weather is the other network data source.

See [docs/planner.md](docs/planner.md) for architecture, data flow, limits, and device-verification status.

## Flavors and bank-safe scope

- `full` includes the optional Accessibility enforcement, Usage Access, notification listener and experimental secure-settings grayscale capability.
- `safe` omits those elevated capabilities while retaining the launcher and planner.

“Bank-safe” describes reduced permissions, not a security certification. Banking or identity apps may apply their own unsupported-device policies. Normal launcher/planner use does not require Developer options, Accessibility, or permission to install unknown apps.

## Permissions

The core launcher works without special access. Optional features use:

- **Default Home** for launcher selection.
- **Read calendar** for Android Calendar Provider events; planner shows permission/no-synced-calendar states rather than inventing freshness.
- **Post notifications / exact alarms** for meeting-prep reminders. Approximate alarms are used when exact-alarm access is unavailable.
- **Usage Access**, **Notification Access**, and **Accessibility** only in capabilities that expose them.
- **Camera** only for the local torch button.
- **ADB secure-settings grant** only for experimental grayscale; see [docs/experimental-grayscale.md](docs/experimental-grayscale.md).

## Build and focused tests

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testSafeDebugUnitTest testFullDebugUnitTest
./gradlew assembleSafeDebug assembleFullDebug
```

Release checks can additionally run:

```bash
./gradlew lintSafeRelease lintFullRelease assembleSafeRelease assembleFullRelease
```

The project targets Android 14/API 34 and supports Android 8/API 26 and later. Real-device calendar providers, work-profile/MDM calendar visibility, reminder delivery, exact alarms, alarm-clock handlers, DND policy access, and launcher selection remain hardware/OEM-dependent and must be verified on target devices.
