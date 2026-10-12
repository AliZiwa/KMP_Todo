# Doorbell — App Store listing copy (v1.0)

Paste these verbatim into App Store Connect. Character counts are Apple's limits; the count in
brackets is the current text's length.

---

## App name (30 max)
```
Doorbell
```
[8]

## Subtitle (30 max)
```
A to-do list that rings
```
[23]

## Promotional text (170 max — editable without a new build)
```
Set a time and Doorbell rings when the task is due. Day, week and month views, live countdowns, and an assistant that turns "gym tomorrow at 7" into a task, on device.
```
[167]

## Description (4000 max)
```
Doorbell rings when your task is due.

Most to-do apps show you a list and hope you look at it. Doorbell waits for the moment a task is actually due, then rings a doorbell — on the lock screen, in the Dynamic Island, and as a notification you can act on without opening the app. Mark it Done, push it back by 15 minutes, dismiss it or delete it, straight from the alert.

YOUR DAY, RANKED

• Day, Week, Month and Recurring views
• Tasks sort by priority first, then by the time they are due
• A live countdown on everything due today
• Extend by +5, +15, +30 or +60 minutes when life gets in the way
• A daily "plan your day" prompt, so tomorrow starts written down
• A home-screen widget and a Live Activity for whatever is next
• Time-sensitive notifications that keep asking until you answer them

JUST SAY IT

Type the way you talk and Doorbell works out the rest:

"gym tomorrow at 7"
"call mum every day at 18:00"
"dentist friday 10am, high priority"
"done with laundry"

On iPhone this runs on Apple Intelligence — Apple's on-device Foundation Models — so what you type never leaves your phone. It needs iOS 26 and an Apple Intelligence-capable device. On any other device a built-in rules parser understands the same phrasings, completely offline. If you run your own language model at home, you can point Doorbell at it instead (Ollama, or any OpenAI-compatible server on your network). That is optional, you configure it yourself, and it is the only network connection Doorbell ever makes.

PRIVATE BECAUSE THERE IS NOWHERE TO SEND IT

• No account, no sign-in, no email address
• No analytics, no advertising, no tracking, no third-party SDKs
• Your tasks live in a database on your device and nowhere else
• Every feature works in airplane mode

There is a Mac version too, built from the same code.

Doorbell is one person's app, made because nothing else would reliably shout at the right minute. If something is wrong, write to ziwaali7@gmail.com and a human answers.
```
[~1750]

> **Guideline note (2.3.10):** metadata must not reference other mobile platforms. Android is
> deliberately not mentioned anywhere in this listing, in the screenshots, or in the app. macOS is
> fine — it is Apple's own platform.

## Keywords (100 max, comma-separated, no spaces, no words already in the name/subtitle)
```
reminder,alarm,task,due,countdown,timer,offline,private,widget,checklist,planner,recurring,daily,agenda
```
[103 → over the limit; see trimmed version below]

**Use this one (fits):**
```
reminder,alarm,task,due,countdown,offline,private,widget,checklist,planner,recurring,daily,agenda
```
[97]

## Category
- Primary: **Productivity**
- Secondary: **Utilities**

## Age rating
4+ — see `store/age-rating-and-privacy.md`.

## What's New in Version 1.0
```
First release.

• Day, Week, Month and Recurring views with priority-and-time ranking
• A doorbell alert when a task is due, with Done / +15 / Dismiss / Delete
• Live Activity on the lock screen and in the Dynamic Island
• Home-screen widget
• Natural-language assistant on Apple Intelligence, entirely on device
• A daily "plan your day" prompt
• No account, no analytics, no tracking
```

## URLs
| Field | Value |
|---|---|
| Support URL | `https://aliziwa.github.io/KMP_Todo/support.html` |
| Marketing URL | `https://aliziwa.github.io/KMP_Todo/` |
| Privacy Policy URL | `https://aliziwa.github.io/KMP_Todo/privacy.html` |

## Copyright
```
© 2026 Ali Ziwa
```

## Price
Free. No in-app purchases.

## Availability
All territories. No pre-order.

---

## App Review notes (paste into "Notes" on the version page)

```
Thanks for reviewing Doorbell.

NO LOGIN REQUIRED
There is no account, no sign-in and no demo credentials. Everything is available on first launch.
Please accept the notification permission prompt on first launch — the app's core feature is the
alert that fires when a task is due.

HOW TO SEE THE MAIN FEATURE IN UNDER TWO MINUTES
1. On the Today tab, tap + and add a task with a due time 1-2 minutes from now.
2. Leave the app (or lock the device). A Live Activity appears on the lock screen and in the
   Dynamic Island for any task due today.
3. At the due minute the app rings a doorbell sound and posts a time-sensitive notification.
   Long-press it for the Done / +15 min / Dismiss / Delete actions. They all work with the app
   closed.

THE ASSISTANT
The chat tab turns plain English ("gym tomorrow at 7") into a task. On iOS it uses Apple's
on-device Foundation Models framework (Apple Intelligence). It therefore requires iOS 26 and an
Apple Intelligence-capable device, and it makes no network requests.

If you review on a device without Apple Intelligence, this is expected and is not a bug: the app
falls back to a built-in rules-based parser, which handles the same phrasings offline, and
Settings shows a clear explanatory message ("Apple Intelligence is not available on this device —
using the built-in parser"). The chat tab remains fully functional either way.

Settings also offers an optional "Local server" backend for users who run their own LLM (Ollama or
an OpenAI-compatible server) on their own network. It is off by default and must be configured by
the user with their own address. It is the only network request the app can make. There is no
developer-operated server anywhere in this app.

DATA
All tasks and settings are stored in a local SQLite database on the device, inside an App Group
container shared with the widget extension. Nothing is collected, transmitted or shared. The App
Privacy answers are "Data Not Collected".

PERMISSIONS USED
- Notifications (the whole point of the app)
- Time Sensitive Notifications entitlement (the due alert must break through Focus)
- App Group group.dev.aliziwa.todo (widget reads the next task)
- Background App Refresh / Processing (re-arms alarms after the system suspends the app)

Contact: ziwaali7@gmail.com
```

---

## Screenshot caption plan

Screenshots are produced by `iosApp/scripts/screenshots.sh` into `store/screenshots/`
(6.9-inch iPhone 1320×2868, 13-inch iPad 2064×2752). Captions sit in the frame header; keep each
one ≤ 40 characters so it never wraps on the narrowest device.

### iPhone (6.9-inch) — 5 shots
| # | Route | Caption | Len |
|---|---|---|---|
| 1 | `today` | `It rings when the task is due.` | 30 |
| 2 | `chat` | `Type it the way you'd say it.` | 29 |
| 3 | `plan` | `Plan tomorrow in one minute.` | 28 |
| 4 | `add` | `Priority, time, repeat. Done.` | 29 |
| 5 | `settings` | `No account. Nothing leaves it.` | 30 |

### iPad (13-inch) — 5 shots
| # | Route | Caption | Len |
|---|---|---|---|
| 1 | `today` | `Today, ranked and counting down.` | 32 |
| 2 | `chat` | `On-device assistant, no cloud.` | 30 |
| 3 | `plan` | `A daily nudge to plan the day.` | 30 |
| 4 | `add` | `Recurring tasks, tracked daily.` | 31 |
| 5 | `settings` | `Private by construction.` | 24 |

Shot 1 on both devices is the App Store's "hero" — it is the one shown in search results, so it
must contain the due alert with the countdown visible.
