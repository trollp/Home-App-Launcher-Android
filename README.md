# Quiet Home

Quiet Home is an original, local-first, text-focused Android home launcher designed to make the phone calmer without making it unusable.

## Features

- Text-first Home screen with clock, date, favorites, search, and an alphabetical app list.
- Hide, restore, rename, favorite, inspect, and uninstall app shortcuts.
- System, light, and dark themes with adjustable text size.
- Optional intentional-opening delay and session reminder per app.
- Optional daily limits, recurring schedules, focus periods, and deliberate grace overrides.
- Seven-day usage dashboard using Android Usage Access estimates.
- Optional local notification batching/silencing with an on-device inbox.
- Optional Accessibility enforcement for configured limits when apps are opened outside Quiet Home.
- Experimental per-app grayscale for sideloaded builds when `WRITE_SECURE_SETTINGS` is explicitly granted with ADB.
- Local-first context assistant for up to five user-defined routines or priorities, with capacity, explainable one-action suggestions, morning briefing, evening reflection, and evidence-gated weekly review.
- Optional Stockholm SMHI weather, read-only Outlook next-event timing, and explicit BYOK AI wording refinement. Calendar titles remain local; executable actions remain constrained to supplied local candidates, while dedicated bounded protocols handle briefing, reflection, and weekly-review wording.

Quiet Home has no analytics, ads, account, or cloud backup. Routine, feedback, reflection, launcher, and notification-inbox data remain on the device. Internet access is used only for SMHI weather and user-configured Outlook/BYOK connections. AI calls occur only when the user taps **Test connection** or an explicit **Refine** action; the selected provider receives only the bounded fields disclosed in Settings. Calendar titles and completion recency are not sent.

The **bank-safe** flavor is intended for phones that also run security-sensitive apps. It omits Accessibility, Usage Access, notification-listener, and `WRITE_SECURE_SETTINGS` capabilities. The core launcher, gestures that use ordinary Android APIs, routines, weather, Outlook, and optional AI remain available. Accessibility restrictions, ADB/Developer options, and permission to install unknown apps are not required for normal day-to-day use and can remain off after installation. Compatibility with any particular banking or identity app depends on that app's own security policy and is not guaranteed.

## Permissions and special access

The core launcher works without special access. Features are enabled progressively from the Permission Center:

- **Default Home:** lets Android use Quiet Home as the launcher.
- **Usage Access:** enables usage estimates and daily-limit decisions.
- **Notification Access:** enables configured notification batching/silencing. All apps default to Allow.
- **Accessibility:** optionally enforces configured rules for apps opened through Recents or another surface. Safety and recovery packages are exempted dynamically and enforcement fails open.
- **Post notifications:** enables approximate session reminders and rate-limited status notices.
- **ADB secure-settings grant:** optional, experimental, and only for grayscale. See [`docs/experimental-grayscale.md`](docs/experimental-grayscale.md).

Limits, reminders, and usage figures depend on Android/OEM scheduling and UsageStats behavior and are estimates rather than hard security controls. The blocked screen always provides a recovery path.

## Build

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The project targets Android 14/API 34 and supports Android 8.0/API 26 and later.

## Verification status for 0.9.0-beta1

- 66 local JVM tests pass.
- Android lint reports no issues.
- Debug and release APK assembly pass.
- Release artifact metadata, alignment, signature, archive integrity, and checksum are verified before delivery.
- No device was connected in the build environment, so selecting it as Home and exercising special-access services still require real-device beta testing.
