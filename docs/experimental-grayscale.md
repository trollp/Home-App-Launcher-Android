# Experimental grayscale

Quiet Home cannot normally apply grayscale to selected apps. The optional sideload feature declares `android.permission.WRITE_SECURE_SETTINGS`; Android never shows a permission dialog for it. A user must explicitly run the grant command shown in the app, and can copy the matching revoke command there.

When enabled, foreground package events from the existing optional accessibility service are evaluated away from the accessibility callback. For selected, non-safety packages, Quiet Home captures and changes only these secure color-correction settings:

- `accessibility_display_daltonizer`
- `accessibility_display_daltonizer_enabled`

It selects Android's monochromacy mode (`0`). Prior string values, including an absent value, are captured before writes and restored when leaving a selected app, disabling the feature, interruption/service teardown, or an error when restoration is possible. If the ADB permission is revoked while grayscale is active, Android prevents restoration; the saved restore state is retained so restoration can be retried after a grant.

These setting names and daltonizer behavior are device/OEM-dependent. The feature may do nothing or be overridden by system accessibility settings. It is disabled by default, experimental, and not suitable for a Play Store build.
