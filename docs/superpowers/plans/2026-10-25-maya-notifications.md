# Maya Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Maya notices who messages the user (app + sender, never text) and may comment on it.

**Architecture:** The phone's NotificationListenerService feeds a pure core `NotificationFilter` that emits `notification` events; the brain turns them into memory lines and an initiative bump with a per-sender limit.

**Tech Stack:** Python brain (pydantic, pytest); Kotlin core (JUnit); Android framework `NotificationListenerService`.

**Spec:** `docs/superpowers/specs/2026-10-25-maya-notifications-design.md`

## Global Constraints

- Wire: `notification{type,id,ts,package,app,sender,group?:string|null,kind:"message"|"email"|"call"}`; `app`/`sender` 1–100 chars; `group` ≤ 100 or null; unknown `kind` → `"message"`.
- Never read notification text (`EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT`, message bodies).
- Burst dedupe on phone: same (package, sender, group) at most once per 120 s.
- Brain urge bump `+8`, at most once per sender per 600 s; notifications are NOT user activity.
- Memory wording: `"{sender} messaged on {app}"`, `"{sender} in {group} on {app}"`, `"{sender} emailed ({app})"`, `"missed call from {sender} ({app})"`. Reason wording: `"{sender} just messaged on {app}"` / `"{sender} just emailed ({app})"` / `"missed call from {sender} ({app})"`.
- Brain tests: `cd brain && .venv/bin/pytest -q` (+ `-W error`). Core: `cd android/core && gradle test -I /tmp/claude-0/-home-user-PPet/d6eab334-7ee4-5d52-a74e-33e22d437b37/scratchpad/init.gradle`. `:app` compiles in CI only.

## Review Focus

1. **Notification flood** (a busy group chat) → one event per sender per 2 min on the phone and one bump per sender per 10 min in the brain. (T1 `test_bump_once_per_sender_per_10_min`; T2 `dedupe_window`.)
2. **Text leakage** → filter input type has no field for text at all. (T2: `NotificationInfo` has no text field.)
3. **Old brain / old app mix** → unknown type ignored both ways. (T1 `test_unknown_kind_is_message`; existing unknown-type tests.)
4. **Notifications while the user is away** → no "came back" bump, idle timers unaffected. (T1 `test_notification_is_not_user_activity`.)
5. **Settings switch off / access revoked** → nothing emitted, no crash. (T3 manual check + guarded code.)

---

### Task 1: Brain support

**Files:** Modify `brain/maya_brain/{protocol.py, mind.py, initiative.py, sim.py}`, `brain/README.md`; Test `brain/tests/test_notifications.py`

**Interfaces:**
- Produces: `NotificationEvent(package: str, app: str 1..100, sender: str 1..100, group: str|None ≤100 = None, kind: Literal["message","email","call"] = "message")` with `type: Literal["notification"]` in the Event union (unknown kind → "message" via before-validator).
- `Initiative.on_event` handles it: `+8` and reason per Global Constraints, at most once per sender per 600 s (state persisted with the rest); not in `USER_ACTIVITY`.
- Mind: memory turn per wording; Mind's `_last_event`/idle tracking unchanged by it; then `_maybe_initiate()` as for other non-direct events. Offline summary also counts inner `notification` items: appends `"; messages: 4 from Sara (WhatsApp), 1 from Omar (Gmail)"` (counts per (sender, app), first-seen order).
- Sim: `/notify <sender> [app] [group]` → `NotificationEvent(package=app.lower(), app=app or "WhatsApp", sender, group)`.

- [ ] **Step 1: Write failing tests** — `test_parse_notification` (all fields, defaults), `test_unknown_kind_is_message`, `test_memory_line_per_kind` (4 wordings), `test_bump_and_reason`, `test_bump_once_per_sender_per_10_min` (second within 600 s → no bump; other sender → bump; after 600 s → bump), `test_notification_is_not_user_activity` (no came-back bump after 4 h; Mind idle reflection timer unaffected), `test_offline_summary_counts_messages`, `test_sim_notify_command`.
- [ ] **Step 2–4:** FAIL → implement → PASS (full suite, `-W error`).
- [ ] **Step 5: Commit** `feat(brain): notice who messages the user`

---

### Task 2: Core filter (Android core)

**Files:** Modify `android/core/src/main/kotlin/com/maya/core/Protocol.kt`; Create `NotificationFilter.kt`; Tests `ProtocolTest.kt`, `NotificationFilterTest.kt`

**Interfaces:**
- Produces:
  - Event `Notification(packageName: String, app: String, sender: String, group: String? = null, kind: String = "message", id, ts)` → encodes per wire format (group null → JSON null).
  - `data class NotificationInfo(packageName: String, app: String, category: String?, ongoing: Boolean, groupSummary: Boolean, foregroundService: Boolean, messagingStyle: Boolean, title: String?, styleSender: String?, conversationTitle: String?, isGroupConversation: Boolean, postedAt: Instant)` — deliberately no text field.
  - `class NotificationFilter(isBlocked: (String) -> Boolean, ownPackage: String, dedupeMs: Long = 120_000)` with `fun accept(n: NotificationInfo): Notification?` per spec §4.2 (kind mapping; sender = styleSender ?: title, trimmed, ≤100, blank → null; group = conversationTitle when isGroupConversation, ≤100; dedupe key (package, sender, group) by `postedAt`).

- [ ] **Step 1: Write failing tests** — `encode_notification` (fields + null group), `accepts_message_category`, `accepts_messaging_style_without_category`, `email_and_missed_call_kinds`, `ignores_ongoing_summary_fgs_own_blocked_and_other_categories`, `sender_from_style_then_title_and_group_title`, `blank_sender_ignored_and_long_names_truncated`, `dedupe_window` (same key within 120 s → null; after → accepted; different sender → accepted).
- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): core notification filter`

---

### Task 3: Phone listener and settings (app)

**Files:** Create `android/app/src/main/kotlin/com/maya/app/senses/{MayaNotificationListener.kt, NotificationBridge.kt}`; Modify `MayaService.kt`, `Prefs.kt`, `SettingsActivity.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `android/README.md`

**Interfaces:**
- Consumes: `NotificationFilter`, `NotificationInfo`, `Notification`, `DoNotWatch`, `Prefs`.
- Produces:
  - `MayaNotificationListener : NotificationListenerService` — `onNotificationPosted(sbn)` builds `NotificationInfo` from `sbn.packageName`, app label (PackageManager, fallback package), `notification.category`, `FLAG_ONGOING_EVENT`, `FLAG_GROUP_SUMMARY`, `FLAG_FOREGROUND_SERVICE`, MessagingStyle detection via `NotificationCompat`-free check: `extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name`, style sender from `Notification.MessagingStyle.extractMessagingStyleFromNotification(notification)` (API 28+, guarded; last message's `senderPerson?.name`), `EXTRA_TITLE`, `EXTRA_CONVERSATION_TITLE`, `EXTRA_IS_GROUP_CONVERSATION`, `sbn.postTime`. Hands it to `NotificationBridge.post(info)`. Wraps everything in try/catch.
  - `object NotificationBridge { var sink: ((NotificationInfo) -> Unit)? }` — MayaService sets it in onCreate (when `prefs.seeNotifications`) and clears it in onDestroy; posting is on the main thread (`Handler(Looper.getMainLooper())`).
  - MayaService owns a `NotificationFilter(doNotWatch::isBlocked, packageName)` and emits accepted events through `emit` (queued while disconnected like app events — extend BrainConnection's queueable set to include `Notification`).
  - `Prefs.seeNotifications: Boolean` default true; Settings: switch "Let Maya see who messages me" and a "Notification access" row (status: package listed in `Settings.Secure.getString(cr, "enabled_notification_listeners")`; Grant → `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
  - Manifest: `<service android:name=".senses.MayaNotificationListener" android:exported="true" android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"><intent-filter><action android:name="android.service.notification.NotificationListenerService"/></intent-filter></service>`.
  - README: grant step + privacy line + switch.

- [ ] **Step 1:** Implement; strict compiler pass.
- [ ] **Step 2: Verify** push → `android` workflow green.
- [ ] **Step 3: Commit** `feat(android): Maya notices who messages you`
