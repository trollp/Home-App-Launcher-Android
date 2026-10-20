# Phase 3 intent bar

The home screen has one persistent, single-line command field above planner guidance. Input is limited to 240 characters; newlines, control characters, and Unicode line/paragraph separators U+2028/U+2029 are rejected in both the UI filter and domain validation. The typed but unsent value survives activity recreation. **Go** and the keyboard Go action are equivalent.

## Local-first commands

These commands never need a network call:

- A bare app label or `open <app>` opens an exact or unique visible-label match. Hidden apps are excluded. Multiple matches are reported; Quiet Home never chooses one.
- `task <title>` or `add task <title>` proposes a local task and asks for confirmation.
- `done <task>` completes exactly one matching active task. Multiple matches are reported. Completion offers Undo.
- `alarm HH:mm` asks for confirmation and then opens Android's alarm UI.
- `gym done` records a local gym observation and offers Undo.
- `plan` opens planner details immediately; `apps` opens the app drawer immediately.

Every app launch uses `LaunchRouter`, including model-selected apps, so block rules and the mindful-opening gate remain in force. Actions are validated only against facts actually transmitted after payload minimization, then revalidated against current tasks, calendar state, and installed/non-hidden apps immediately before execution. Provider/package I/O and execution preparation run off the UI thread, and lifecycle gating is checked immediately before side effects and UI delivery. Task, calendar, alarm, and DND mutations require confirmation. DND supports only persistent on/off changes; temporary/until-DND is not advertised or accepted. The safe flavor excludes DND from both the payload allow-list and strict response schema. Mutations invalidate the planner cache and request an automatic refresh.

## Optional classifier and consent

Unmatched input stops locally unless both an OpenAI key exists and **Send unmatched intent commands to OpenAI** is enabled. This preference defaults off, is stored separately from planner-cloud consent, and is never inferred from planner consent, a legacy preference, or a key. Saving/replacing a key does not enable either consent. Deleting the key disables both toggles.

The classifier defaults to `gpt-5-nano`, `minimal` reasoning, and 800 completion tokens (`profile.intentModel`). It uses a strict JSON schema that returns either `null` or one `PlanAction`. The network response reader and parsed action content are each capped at 8 KiB; parsing fails closed. Output is passed through `PlanValidator`, then current-state checks at execution.

The explicit UTF-8 payload is capped at 8 KiB and contains only:

- bounded command text;
- current epoch time and time-zone ID;
- visible app labels and package names;
- active task IDs and titles;
- current event IDs, titles, begin/end times, and only a location-present boolean;
- free-slot begin/end times;
- fixed allowed tool names.

It excludes health values, usage, notifications, activity logs, calendar/account names, profile goals and constraints, actual event locations, credentials, prior commands, and other history. Activity logging stores only `INTENT_COMMAND` plus a fixed tool name and an already-known reference; raw command text is never logged.

## Lifecycle and failures

An `ActiveRequestGate` generation token prevents responses from an older request or stopped/recreated activity from changing UI or executing an action. Network, parse, schema, validation, and stale-state failures produce a status message and no action. There is no Ask AI, Refine, or manual-refresh control.

## Real hardware checks

The following remain device/OEM dependent and must be checked on representative physical devices in both `full` and `safe` flavors:

- IME Go behavior, hardware keyboard submit, rotation with unsent text, accessibility focus/live-region status, 48 dp Go target, large text, and narrow displays.
- LaunchRouter mindful gate/block behavior for intent-bar app launches and package removal while a request is pending.
- Alarm-clock and calendar insertion handlers, their confirmation screens, cancellation, and devices with no handler.
- DND policy-access grant/revocation and OEM interruption-filter behavior in the full flavor; verify that the safe flavor never advertises DND.
- Calendar Provider permission denial, no synced calendars, work profiles/MDM, recurring events, and changes while classification is pending.
- Offline, captive portal, timeout, HTTP errors, malformed/truncated responses, activity backgrounding, rapid repeat submissions, and rotation during classification.
- OpenAI key deletion while a request is pending and verification that both cloud toggles remain disabled afterward.
- Packet/proxy inspection on a dedicated test account to verify the classifier allow-list and 8 KiB payload cap; never use production personal data for this test.

Notification digest is intentionally deferred to Phase 4.
