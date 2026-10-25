# Maya — Notifications Sense (v1)

Date: 2026-10-25
Status: Approved in chat ("go ahead").
Builds on: `2026-10-24-maya-design.md` (senses roadmap level B).

## 1. Goal

Maya notices when someone messages the user — which app and who — and decides for herself whether
to comment, without ever reading the message text.

### Success criteria
- A WhatsApp/SMS/Telegram/email message or a missed call from a person shows up in Maya's world as
  "Sara messaged on WhatsApp" within a few seconds (while connected).
- She sometimes comments ("Sara again? 👀"), within her usual guardrails; she never announces every message.
- Message text is never read on the phone and never sent to the PC.
- Do-not-watch apps, Maya herself, ongoing/silent/system notifications are ignored.
- A settings switch turns the whole sense off.

## 2. Decisions (agreed)

| Topic | Decision |
|---|---|
| What she sees | App + sender (+ group name). Never the text. |
| Reaction | Her own judgment: an urge bump + memory; existing guardrails apply. |
| Default | On, with a settings switch "Let Maya see who messages me". |

## 3. Protocol (backward compatible)

New event `notification`:
`{type, id, ts, package, app, sender, group?: string|null, kind: "message"|"email"|"call"}`.
`sender` 1–100 chars, `app` 1–100 chars, `group` ≤ 100 chars or null. Old brains ignore the unknown type.
It is queued while disconnected (like app events) and counted in `offline_summary`.

## 4. Phone

### 4.1 `MayaNotificationListener` (NotificationListenerService)
- Requires the special "Notification access" permission (Settings row + Grant button →
  `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
- Runs in the app process; forwards accepted notifications to `MayaService` (if running) via a
  process-local bridge; when the service isn't running or the switch is off it does nothing.

### 4.2 Filter (pure, in core: `NotificationFilter`)
Input: package, category, flags (ongoing, group summary, foreground-service), is-MessagingStyle,
title, conversation title, is-group, MessagingStyle last sender name, posting time.
- Ignore: own package, do-not-watch packages, ongoing, group summaries, foreground-service
  notifications, and categories other than `msg`, `email`, `call`, `missed_call` — except
  notifications using MessagingStyle, which count as `message` regardless of category.
- Kind: `msg`/MessagingStyle → message; `email` → email; `call`/`missed_call` → call
  (only missed calls: an ongoing incoming-call notification is ongoing and already ignored).
- Sender: MessagingStyle last message's sender name, else `EXTRA_TITLE`; group: conversation title
  when the conversation is a group (else null). Trimmed, max 100 chars; empty sender → ignored.
- **Burst dedupe:** the same (package, sender, group) is emitted at most once per 2 minutes
  (re-posts and rapid messages collapse into one).
- Never read `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, message bodies, or `EXTRA_SUB_TEXT`.

### 4.3 Settings
- Row "Notification access" with Grant (opens the listener settings) and status from
  `NotificationManagerCompat`-equivalent `Settings.Secure "enabled_notification_listeners"` check.
- Switch "Let Maya see who messages me" (`Prefs.seeNotifications`, default true).

## 5. Brain

- Protocol: `NotificationEvent` model (`kind` literal; unknown kind → "message").
- Mind:
  - Adds a memory turn: `event: "{sender} messaged on {app}"`, or `"{sender} in {group} on {app}"`,
    `"{sender} emailed ({app})"`, `"missed call from {sender} ({app})"`.
  - It is a user-activity-neutral event: it does **not** count as the user being active (no "came back"
    bump, does not reset idle timers).
- Initiative: `+8` urge per notification with reason `"{sender} just messaged on {app}"` (or the
  email/call wording); at most one bump per sender per 10 minutes. All guardrails unchanged
  (cooldown, cap, quiet hours, shush, connected & screen-on gating).
- Offline summary: adds `"messages: 4 from Sara (WhatsApp), 1 from Omar (Gmail)"` to the existing line.
- Simulator: `/notify <sender> [app] [group]` sends a message notification.

## 6. Privacy

Only app name, sender display name and group name leave the phone, only to the user's PC, and are
stored in her memory like other events. The notification text is never accessed.

## 7. Testing

- Brain: protocol parse (+ unknown kind), memory line wording per kind, urge bump + reason, per-sender
  10-minute limit, no activity side effects, offline summary counts, sim `/notify`.
- Core (Kotlin): protocol encode; filter accept/ignore rules per category/flags/own/do-not-watch;
  sender/group extraction; burst dedupe window.
- App: CI build; on-device checklist (grant access, message from another phone, group chat,
  missed call, do-not-watch app, switch off).

## 8. Out of scope

Reading message text; replying to messages; per-app choices beyond do-not-watch; contacts lookup.
