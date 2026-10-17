# Quiet Home Launcher Implementation Plan

> **For Hermes:** Use subagent-driven-development and strict RED-GREEN-REFACTOR task-by-task.

**Goal:** Build an original, local-first Android home launcher with text navigation, mindful app opening, limits and schedules, a usage dashboard, notification batching, and opt-in experimental grayscale.

**Architecture:** One Kotlin/Android app with XML layouts and small Activities/Services. Framework-independent policy classes own filtering, ordering, launch decisions, limits, schedules, and summaries so JVM tests remain fast. Android adapters enumerate and launch apps, persist local settings, query UsageStats, listen for notifications, enforce optional Accessibility controls, and toggle grayscale only when privileged permission exists.

**Tech Stack:** Kotlin 1.9.24, Android Gradle Plugin 8.5.2, AndroidX/AppCompat/Material, SharedPreferences JSON storage, JUnit 4, minSdk 26, target/compile SDK 34.

---

## Product and safety boundaries

- Working product name: **Quiet Home**; package: `com.petertroll.quiethome`.
- Independently designed UI, text, icons, colors, and interactions. Do not copy minimalist phone trademarks, screenshots, wording, or assets.
- All launcher data stays local. No account, analytics, ads, or network permission.
- Core launcher works without special access.
- Usage Access gates usage totals and daily limits.
- Notification Access gates the filtered notification inbox.
- Accessibility is optional and gates enforcement when apps are opened outside Quiet Home.
- Blocking must never cover Quiet Home, Settings, System UI, package installer, dialer, emergency components, permission controllers, or the current default launcher recovery route.
- Every blocking screen provides an intentional temporary override.
- Experimental grayscale is disabled by default and only writes secure color-correction settings after explicit disclosure and a separately granted ADB development permission. Failure must restore normal color and never block app use.

## Task 1: Buildable launcher skeleton

**Files:** Gradle wrapper/configuration, `app/build.gradle.kts`, `AndroidManifest.xml`, base resources, `LauncherActivity.kt`.

1. Add a JVM manifest-contract test that fails because the HOME/DEFAULT intent filter is absent.
2. Run the test and record expected failure.
3. Add the smallest launcher manifest/activity and original text-only shell.
4. Run JVM tests and `assembleDebug`.
5. Commit.

## Task 2: App catalog and launcher preferences

**Files:** `domain/AppEntry.kt`, `domain/AppCatalog.kt`, `domain/LauncherPreferences.kt`, corresponding unit tests and Android adapters.

1. Test alphabetical ordering, normalized search, hidden-app exclusion, aliases, favorites, and deterministic fallback labels.
2. Confirm RED.
3. Implement pure policy and JSON-backed preferences.
4. Confirm GREEN and regression suite.
5. Connect PackageManager enumeration and app launch intents.

## Task 3: Touch-first home and app drawer

**Files:** launcher layouts/resources, adapters, `LauncherActivity.kt`.

1. Add tests for home sections and search results.
2. Implement clock/date, compact favorite list, swipe/tap navigation, immediate keyboard search, and 48dp minimum rows.
3. Add long-press actions: favorite, hide, rename, app info, uninstall.
4. Verify empty states, accessibility labels, large fonts, light/dark themes, and rotation.

## Task 4: Default Home onboarding and permission center

**Files:** `OnboardingActivity.kt`, `PermissionCenterActivity.kt`, role/access helpers.

1. Test permission-state derivation and recovery messaging as pure mappings.
2. Add Home role request with Settings fallback.
3. Add progressive cards for Usage, Notification, Accessibility, overlay/notifications, and experimental grayscale; every optional card has Not now.
4. Re-check state on resume and expose a clear switch-back path.

## Task 5: Mindful launch and session reminders

**Files:** `domain/LaunchPolicy.kt`, `MindfulGateActivity.kt`, reminder scheduler, tests.

1. Test unconfigured launch, selected duration, delay countdown, bypass, and critical-app exemption.
2. Implement per-app configuration and gate only selected apps.
3. Schedule exact-enough local reminders without requiring exact-alarm privilege.
4. Launch safely when package/activity disappears.

## Task 6: Limits and schedules

**Files:** `domain/BlockPolicy.kt`, schedule editor, `BlockedActivity.kt`, tests.

1. Test daily limits, overnight intervals, weekdays/weekends, focus sessions, grace override, clock boundaries, and safe-package exemptions.
2. Implement local rules and decision reasons.
3. Enforce launcher-origin launches directly.
4. Add optional AccessibilityService enforcement for external/recent-task launches, with loop prevention and emergency exclusions.

## Task 7: Usage dashboard

**Files:** `UsageRepository.kt`, `domain/UsageSummary.kt`, `UsageDashboardActivity.kt`, tests.

1. Test aggregation, day boundaries, sorting, absent permission, and no-data states.
2. Query UsageStatsManager only behind the adapter.
3. Render calm daily total, app breakdown, and seven-day trend without gamification.

## Task 8: Notification filter and inbox

**Files:** `QuietNotificationListener.kt`, `domain/NotificationRules.kt`, inbox storage/activity, tests.

1. Test allow/filter rules, calls/alarms/system exemptions, deduplication, retention, and redaction.
2. Store selected filtered notification metadata locally and cancel only configured notifications.
3. Build inbox grouped by time/app with clear/delete/open-app actions.
4. Never upload contents; add retention controls and clear-all.

## Task 9: Experimental grayscale

**Files:** `GrayscaleController.kt`, service integration, tests.

1. Test permission detection, selected-app decisions, and mandatory restore behavior.
2. Add explicit disclosure and copyable ADB grant/revoke commands.
3. Toggle Android secure color correction only when permission is actually granted.
4. Restore normal color on service stop, permission loss, crash recovery, or unselected foreground app.
5. Label feature experimental/device-dependent and exclude it from core acceptance.

## Task 10: Verification and delivery

1. Run all JVM tests, lint, debug/release builds, and `git diff --check`.
2. Review manifest permissions and ensure no INTERNET permission or tracking SDK exists.
3. Install on an Android device/emulator when framework-ready; select as Home, launch apps, test back/home, reboot persistence, permission denial, and override recovery.
4. Exercise notification/accessibility/grayscale only when corresponding access is available.
5. Verify final APK ZIP integrity, alignment, v2/v3 signature, package/version/min/target SDK, byte size, embedded permissions, and SHA-256.
6. Deliver the verified APK and accurately label physical-device/emulator coverage and experimental limitations.
