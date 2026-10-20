# Phase 1 planner

## Architecture and data flow

1. `CalendarProviderCollector` reads visible/selected calendars and the next 24 hours from Android `CalendarContract.Instances`. There is no vendor OAuth or direct calendar service connection.
2. `SnapshotBuilder` normalizes events, local tasks, profile constraints/goals, sleep/wind-down, gym configuration and preferred slots, proactive budget, installed non-hidden packages, weather/usage source state, free slots, metrics, activity counts and the last 20 records.
3. `PlannerSnapshotCodec` enforces a 16 KiB UTF-8 transport cap. Source status and true collection age are included; an unavailable calendar never receives fabricated freshness.
4. `FallbackPlanner` produces immediate local content. Bootstrap fallback cache entries are marked separately and cannot suppress the first full collection refresh.
5. Cloud planning runs only when both the explicit planner-cloud consent and an OpenAI key exist. The strict JSON schema and `PlanValidator` bound all output. Empty or failed validated output falls back locally.
6. `MeetingPrepScheduler` reconciles stable alarms after every full or cache-backed refresh. It schedules future busy-meeting preparation plus validated proactive model nudges, and cancels obsolete IDs. Meeting and general planner reminders use separate channels and copy.
7. `ActionExecutor` revalidates against current tasks and installed/non-hidden packages. It uses Android system activities for event, map, calendar insertion and alarm UI, persists local task/gym mutations, and requires confirmation for consequential actions.

## Cloud disclosure

When enabled, the complete bounded planner snapshot is sent to OpenAI. It may contain calendar and task titles, event locations and times, calendar/account labels, profile constraints and goals, sleep/gym preferences, free slots, package names and usage minutes, weather, activity counts and recent records. Credentials are never part of the snapshot. Maps URLs and Android intents are never accepted from model output.

Cloud consent defaults off on new installs and migrations, even if a key or legacy provider preference exists. Saving a key does not toggle consent. Local fallback and periodic local refresh do not require network connectivity.

## Configuration

Planner settings expose sleep/wake/wind-down, gym weekly target/duration/travel/preferred slots, meeting-prep lead time, hard constraints, goals, proactive daily budget, important app package names, local tasks, free-event inclusion and calendar selection. Selecting no calendars intentionally yields no calendar events and is surfaced as a warning/state.

The planner model defaults to `gpt-5-mini`, low reasoning, 4000 completion tokens. The separately stored intent-bar model defaults to `gpt-5-nano`, minimal reasoning, 800 completion tokens. Both model names, reasoning efforts, and token caps are editable under Planner settings; the intent-bar model is groundwork and is not invoked in Phase 1. Health Connect and notification digest are placeholders only.

## Platform and hardware verification

Not verified in this repository's host environment:

- Calendar sync through third-party account apps, including Outlook calendar sync into Android's provider.
- Garmin-originated Health Connect records are not read in Phase 1; sleep, exercise, heart-rate, steps, and calories require Phase 2 plus real-device verification.
- Work-profile and MDM policy effects on provider visibility and selected calendar IDs.
- Calendar provider reads and 30-minute planner refreshes while the launcher is visible under OEM background restrictions.
- Real-device provider differences for recurring instances, out-of-office availability, attendee state and all-day events.
- Exact alarm permission/OEM scheduling, notification delivery, reboot behavior and doze timing.
- Alarm-clock, calendar, maps and event activity handlers.
- Notification policy/DND access and OEM interruption-filter behavior.

When exact alarms are unavailable, meeting prep uses an inexact while-idle alarm. This is a reminder aid, not a guaranteed alarm or safety-critical scheduler.
