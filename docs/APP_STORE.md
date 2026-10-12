# Shipping Doorbell to the App Store

Everything needed to get build 1 of **Doorbell** (`dev.aliziwa.todo`, team `487Q4488BH`) from this
Mac into App Store review, in order. Tick as you go.

Source material:

| What | Where |
|---|---|
| Listing copy, keywords, review notes, screenshot captions | `store/listing.md` |
| Age rating, App Privacy, export compliance, licences | `store/age-rating-and-privacy.md` |
| Icon master and exports | `store/brand/` |
| Screenshots | `store/screenshots/` (produced by `iosApp/scripts/screenshots.sh`) |
| Export options for `xcodebuild` | `store/ExportOptions.plist` |
| Privacy / support pages | `docs/privacy.html`, `docs/support.html`, `docs/index.html` |

Always `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` first — `xcode-select`
points at the Command Line Tools on this machine and must not be changed.

---

## 0. Before anything else: publish the policy URLs

App Store Connect will not accept the listing without a reachable privacy-policy URL, so do this
first and let DNS settle while you do the rest.

1. Create the GitHub repository (it has no remote yet):
   ```sh
   cd /Users/aliziwa/Documents/personal/personal-todo-kmp
   gh repo create aliziwa/personal-todo-kmp --public --source=. --remote=origin --push
   ```
   (If the repo should stay private, GitHub Pages on a private repo needs a paid plan — use
   `--public`, or host the two pages somewhere else and change the URLs in `store/listing.md`.)
2. GitHub › the repo › **Settings › Pages** › Source: **Deploy from a branch** › Branch **`main`**,
   folder **`/docs`** › Save.
3. Wait for the Pages deployment to go green, then check all three in a browser:
   - `https://aliziwa.github.io/personal-todo-kmp/`
   - `https://aliziwa.github.io/personal-todo-kmp/privacy.html`
   - `https://aliziwa.github.io/personal-todo-kmp/support.html`

`docs/.nojekyll` is already in place so Pages serves the files verbatim instead of running Jekyll
over them. `docs/ARCHITECTURE.md` and `docs/PLAN.md` also live in `docs/` and will be publicly
readable once Pages is on — that is fine (they contain no secrets), but check once before enabling.

- [ ] Repo pushed
- [ ] Pages enabled on `main` / `/docs`
- [ ] All three URLs load, on a phone as well as a desktop browser

---

## 1. Accounts and signing

- [x] Apple Developer Program membership — active, team **487Q4488BH** (Ali Ziwa, individual).
- [ ] Xcode › Settings › Accounts — the Apple ID for that team is signed in, and "Download Manual
      Profiles" has been run at least once.
- [ ] `iosApp/project.yml` already carries `DEVELOPMENT_TEAM: 487Q4488BH` and
      `CODE_SIGN_STYLE: Automatic`. Re-run `cd iosApp && xcodegen generate` after any change to it.
- [ ] Open `iosApp/iosApp.xcodeproj` once and confirm both targets (`iosApp` → product **Doorbell**,
      and `TodoWidgets`) show "Automatically manage signing" with no red text.

### App ID capabilities

Certificates, Identifiers & Profiles › Identifiers. Two identifiers are needed, and automatic
signing will create them on first device build — but check the capabilities are ticked:

| Identifier | Capabilities |
|---|---|
| `dev.aliziwa.todo` | App Groups (`group.dev.aliziwa.todo`), Time Sensitive Notifications, Push Notifications *(only if you ever add remote push — not needed today)* |
| `dev.aliziwa.todo.TodoWidgets` | App Groups (`group.dev.aliziwa.todo`) |

- [ ] App Group `group.dev.aliziwa.todo` exists and is assigned to **both** identifiers.
- [ ] Time Sensitive Notifications ticked on `dev.aliziwa.todo` (it is in
      `iosApp/iosApp/iosApp.entitlements` and the build will fail to sign without it).
- **Live Activities need no capability and no entitlement** — `NSSupportsLiveActivities` in
  `Info.plist` is the whole requirement, and it is already there.

---

## 2. Create the app record

App Store Connect › My Apps › **+** › New App.

| Field | Value |
|---|---|
| Platforms | iOS |
| Name | **Doorbell** |
| Primary language | **English (U.S.)** |
| Bundle ID | `dev.aliziwa.todo` |
| SKU | `DOORBELL-IOS-001` |
| User Access | Full Access |

Note on the language: pick **English (U.S.)**, not U.K. It is the default storefront language and
avoids a second localisation later. The listing copy in `store/listing.md` is written to read
naturally in both — it avoids "-ise/-ize" words entirely.

If the name **Doorbell** is already reserved by someone else, App Store Connect will say so here.
Fallbacks in order of preference: *Doorbell — Tasks That Ring*, *Doorbell Tasks*, *Ring My Task*.
A changed name means changing the title in `store/listing.md`, the keyword list (the name's words
must not be repeated in keywords), and the screenshot captions.

- [ ] App record created
- [ ] Name confirmed available

---

## 3. Fill in App Information

App Store Connect › the app › **App Information**:

- [ ] Subtitle, from `store/listing.md`
- [ ] Privacy Policy URL: `https://aliziwa.github.io/personal-todo-kmp/privacy.html`
- [ ] Category: Primary **Productivity**, Secondary **Utilities**
- [ ] Content Rights: *"No, it does not contain, show, or access third-party content"* (see
      `store/age-rating-and-privacy.md` §4 — but settle the doorbell sound licence first, open item
      at the bottom of this file)
- [ ] Age Rating › Edit — answer every question per `store/age-rating-and-privacy.md` §1. Confirm
      the result reads **4+**.

### Pricing and availability

- [ ] Price: **Free**, no in-app purchases
- [ ] Availability: all territories
- [ ] No pre-order

---

## 4. Fill in the version page (1.0)

- [ ] Promotional text, Description, Keywords, What's New — all from `store/listing.md`
- [ ] Support URL: `https://aliziwa.github.io/personal-todo-kmp/support.html`
- [ ] Marketing URL: `https://aliziwa.github.io/personal-todo-kmp/`
- [ ] Copyright: `© 2026 Ali Ziwa`
- [ ] Version: `1.0`
- [ ] Release: *Manually release this version* (so you choose the moment it goes live)
- [ ] Routing App Coverage File: none
- [ ] Sign-In Information: **not required** — tick "Sign-in required" **off**. There is no account.
- [ ] Notes for review: paste the App Review notes block from `store/listing.md`
- [ ] Contact: Ali Ziwa, `ziwaali7@gmail.com`, phone number on the account

### App icon

App Store Connect reads the 1024 icon out of the uploaded build's asset catalog, so there is
usually nothing to upload by hand. The master is `store/brand/icon-1024.png` — 1024×1024, PNG, no
alpha channel, sRGB, flat (no rounded corners baked in; Apple applies the mask). If the upload is
ever rejected for "contains an alpha channel", that file is the one to re-upload.

- [ ] Icon visible on the version page after the build is processed

### Screenshots

Required sets for a universal app:

| Device | Size | Count |
|---|---|---|
| iPhone 6.9-inch | 1320 × 2868 (portrait) | 3–10 (use 5) |
| iPad 13-inch | 2064 × 2752 (portrait) | 3–10 (use 5) |

Generate them, then drag them in in the order given in the caption plan at the end of
`store/listing.md`:

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd /Users/aliziwa/Documents/personal/personal-todo-kmp
./iosApp/scripts/screenshots.sh          # writes store/screenshots/
```

- [ ] 5 iPhone 6.9-inch shots, exact pixel size, first one is the due-alert hero
- [ ] 5 iPad 13-inch shots, exact pixel size
- [ ] No device bezels drawn into the image unless they are pixel-accurate (Apple rejects
      inaccurate hardware mock-ups), no placeholder/lorem text, no other platform's hardware
- [ ] App Previews: skipped for 1.0

---

## 5. App Privacy

App Store Connect › **App Privacy** › Get Started.

- [ ] First question — *"Do you or your third-party partners collect data from this app?"* → **No**
- [ ] Label shows **Data Not Collected**
- [ ] Publish

Reasoning, per category, is in `store/age-rating-and-privacy.md` §2 — including why the optional
user-configured LLM server does not make this a "collection".

---

## 6. Build, archive, upload

```sh
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
cd /Users/aliziwa/Documents/personal/personal-todo-kmp/iosApp
xcodegen generate

# ExportOptions lives in store/ (the brand agent's area); copy it in once:
cp ../store/ExportOptions.plist ./ExportOptions.plist

xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/Doorbell.xcarchive \
  archive | tee /tmp/doorbell-archive.log

xcodebuild -exportArchive \
  -archivePath build/Doorbell.xcarchive \
  -exportOptionsPlist ExportOptions.plist \
  -exportPath build/export | tee /tmp/doorbell-export.log
```

Grep the logs for `error:` rather than reading them. Then upload the `.ipa`:

```sh
xcrun altool --upload-app -f build/export/Doorbell.ipa -t ios \
  --apiKey <KEY_ID> --apiIssuer <ISSUER_ID>
```

…or, simpler for a first submission and strongly recommended: **Xcode › Product › Archive**, then
**Distribute App › App Store Connect › Upload** in the Organizer. It handles signing, symbols and
the two-factor prompt without an API key.

- [ ] `MARKETING_VERSION` is `1.0` and `CURRENT_PROJECT_VERSION` is `1` (both in `iosApp/project.yml`)
- [ ] Archive succeeds for `generic/platform=iOS` (a real device archive, not the simulator)
- [ ] Upload accepted, no ITMS errors by email
- [ ] Build finishes processing in App Store Connect (10–45 min) and appears under TestFlight

---

## 7. TestFlight

- [ ] TestFlight › Internal Testing › add yourself to a group, add the build
- [ ] Install on a real device and walk the five checks:
  1. Add a task due in 2 minutes → the doorbell rings and a time-sensitive notification arrives
  2. The notification's **Done / +15 / Dismiss / Delete** actions all work with the app force-quit
  3. A Live Activity appears on the lock screen and in the Dynamic Island for a task due today
  4. The widget shows the next task and refreshes after a change
  5. The chat tab parses "gym tomorrow at 7" — on Apple Intelligence if available, on the rules
     parser if not, with the fallback message visible in Settings
- [ ] Export compliance question on the build → already answered by `ITSAppUsesNonExemptEncryption`
      in `Info.plist`; if it is asked, answer per `store/age-rating-and-privacy.md` §3

---

## 8. Submit

- [ ] Version page › select the processed build
- [ ] Re-read the Notes for Review
- [ ] **Add for Review** → **Submit to App Review**
- [ ] Expect a result in 24–48 h. If rejected, the Resolution Center reply thread is the only place
      to argue it; attach a screen recording for anything behavioural.

---

## Pre-flight: what to check inside the app before submitting

These are the Apple requirements that bite an app like this one. Confirm each against the actual
Release build on a device.

### Required and already in place
- [x] **Privacy manifest** — `iosApp/iosApp/PrivacyInfo.xcprivacy` and
      `iosApp/TodoWidgets/PrivacyInfo.xcprivacy` declare no tracking, no collected data, and
      reasons for UserDefaults / file-timestamp / disk-space / boot-time API use. Required since
      spring 2024; a missing one is an automatic upload warning.
- [x] **Launch screen** — `UILaunchScreen` in `Info.plist` (colour `LaunchBackground` + image
      `LaunchIcon`). An app without one is rejected, and on iPad an app without one is letterboxed.
- [x] **iPad orientations** — `UISupportedInterfaceOrientations~ipad` lists all four. iPadOS 26
      requires apps to resize; `UIRequiresFullScreen` is deprecated and deliberately not set.
- [x] **Export compliance key** — `ITSAppUsesNonExemptEncryption = NO`.
- [x] **Usage strings** — `NSUserNotificationsUsageDescription` and
      `NSLocalNetworkUsageDescription` both present and both say *why* in plain English. A generic
      or missing string is a guaranteed rejection for the local-network prompt.
- [x] **Category** — `LSApplicationCategoryType = public.app-category.productivity`, matching the
      store listing.

### To verify by hand
- [ ] **No placeholder content anywhere** — no "Lorem ipsum", no "TODO", no debug menu reachable in
      Release, no sample tasks that look like leftovers. Seed data, if any, must read as a genuine
      welcome.
- [ ] **No hard-coded personal names** — the greeting must come from what the user typed, and start
      empty. Grep the Release build's strings for "Ali", "Ziwa", and any family names used while
      testing: `strings build/export/Payload/Doorbell.app/Doorbell | grep -i -E 'ziwa|lorem|TODO'`.
- [ ] **No references to other platforms** — guideline 2.3.10. Nothing in the UI, the settings
      screen, the empty states, or the screenshots may mention Android or link to another mobile
      store. Mentions of the Mac version are fine. The description in `store/listing.md` is already
      written this way.
- [ ] **Model-download UI is not shown on iOS** — on iOS the model is the OS's own, so any
      "download a model" or Hugging Face URL field inherited from the desktop/Android builds must
      be hidden. A visible download field would also raise a 2.5.2 question about downloading
      executable code.
- [ ] **Accessibility basics** — VoiceOver labels on the icon-only buttons (extend chips, the add
      button, the nav rail items), and a check that the app is usable with Increase Contrast and
      Reduce Motion on. **Known limitation:** Compose Multiplatform does not fully honour Dynamic
      Type on iOS, so text does not scale with the system size setting. This is not an automatic
      rejection, but it is the most likely accessibility complaint from a user; note it as a known
      issue and plan a text-scale setting for 1.1.
- [ ] **Deployment target** — `iosApp/project.yml` sets `iOS 26.0`, because Foundation Models is an
      iOS 26 framework. The listing must therefore say, and does:
      *"Requires iOS 26 and an Apple Intelligence-capable device for the assistant."* App Store
      Connect shows the minimum OS automatically from the build; the sentence in the description
      exists for the devices that meet iOS 26 but not the Apple Intelligence hardware bar.
- [ ] **Permission prompts are not stacked at launch** — ask for notifications when it is obviously
      relevant, with context, not as a wall of system alerts on first run.
- [ ] **Nothing crashes with permissions denied** — deny notifications, deny local network, and
      confirm the app degrades with a message rather than a dead screen.
- [ ] **Light and dark** — both themes, on device, including the due-alert screen and the widget.

---

## Open items the developer must settle

1. **The doorbell sound's licence.** `androidApp/src/main/res/raw/doorbell.wav` (and the iOS copy)
   must be original or carry a licence that permits commercial redistribution. If it came from a
   sample pack or a free-sounds site, find the licence page and keep a copy of it in `store/`. If
   it cannot be established, replace it — it is the one asset in this app that is not demonstrably
   original.
2. **The app name.** "Doorbell" is a common word; check availability at the moment of creating the
   app record, and search the store for an existing app of the same name to avoid a 4.1 or
   trademark complaint later.
3. **Screenshots** are generated by the iOS agent's script and are not yet in `store/screenshots/`.
   They are the last blocking asset.
