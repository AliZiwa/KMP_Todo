# Jukiza-Reminder — Privacy Policy

**Effective 12 October 2026.**

This is the source text for `docs/privacy.html`. Edit here, then mirror the change into the HTML
page (the HTML is self-contained on purpose — no build step, no generator, so GitHub Pages serves
it as-is).

---

**Short version.** Jukiza-Reminder has no account system, no analytics, no advertising and no server of
its own. Everything you put into it stays in a database on your device. The developer never
receives your data and has no way to.

## What Jukiza-Reminder stores, and where

On your device only, in a local SQLite database inside the app's own storage (shared with the
home-screen widget through an app group so the widget can show your next task):

- **Your tasks** — titles, notes, priority, dates and due times, whether they are done, and
  recurring-task templates.
- **Your settings** — the assistant backend you chose, the time of the daily planning prompt, sound
  and notification preferences, and, if you pointed the assistant at your own LLM server, the
  address you typed.
- **The name you type for the greeting** — if you give Jukiza-Reminder a name so it can say "Good morning,
  …", that name is stored in the same local database. It is never sent anywhere. You can change or
  clear it in Settings.
- **What you type into the assistant** — your messages and the tasks the assistant proposed, kept
  locally so the conversation is still there when you come back.

## What Jukiza-Reminder collects

Nothing. There is no sign-in, no email address, no device identifier, no advertising identifier, no
crash reporter, no analytics SDK and no third-party SDK that reports anything. The app contains no
code that sends your data to the developer, because there is no developer-run server for it to send
data to.

## Notifications

Jukiza-Reminder asks for permission to send notifications, because a reminder that cannot reach you is not
a reminder. Notifications are created and delivered on your device by the operating system. They
are not pushed from a server, so their contents — your task titles — are never transmitted over the
internet. If you decline the permission the rest of the app still works; you simply will not be
alerted when the app is closed.

## The assistant and Apple Intelligence

When you type something like "gym tomorrow at 7", Jukiza-Reminder has to understand it. On iPhone and iPad
it uses Apple Intelligence — Apple's Foundation Models, which run on the device itself. Your text is
processed locally and is not sent to Apple, to the developer, or to anyone else. On a device without
Apple Intelligence, a small rules-based parser built into the app does the same job, also entirely
on the device.

## The optional local server

If you run your own language-model server — Ollama, or anything with an OpenAI-compatible API — you
can go into Settings and type its address so the assistant uses it instead. This is off by default
and nothing happens unless you set it up yourself.

If you do set it up, then **the text you type into the assistant, plus a short summary of the tasks
already on the relevant day, is sent to that server** so it can answer. That server is yours. It is
normally a machine on your own network. The developer does not operate it, cannot see it, and
receives nothing. What happens to data on that server is governed by whatever software you chose to
run on it.

The same applies if you ever use the model-download feature on a platform that offers it: the app
downloads the file from the address you gave it, and nothing else.

Apart from those two things — a server you configured, and a download you started — Jukiza-Reminder makes
no network requests at all. Every other feature works in airplane mode.

## No analytics, no advertising, no tracking

Jukiza-Reminder shows no ads, contains no advertising or attribution SDK, does not track you across apps or
websites, does not build a profile of you, and does not sell or share anything with anyone, because
it has nothing to sell or share.

## Children

Jukiza-Reminder does not collect personal information from anyone, of any age. It is rated 4+ and has no
accounts, no social features, no user-to-user messaging, no purchases and no web browsing. If you
are a parent or guardian and have a question, write to the address below.

## Deleting your data

Delete the app. The database and settings live inside the app's container, so removing the app
removes all of it from your device. There is no copy anywhere else to request, export or erase, and
there is no account to close.

## Changes to this policy

If this policy changes, the new version is posted on this page with a new effective date. If a
change ever meant the app started collecting something, it would be announced in the app's release
notes as well, not quietly.

## Contact

Ali Ziwa — ziwaali7@gmail.com. A person reads it.

© 2026 Ali Ziwa
