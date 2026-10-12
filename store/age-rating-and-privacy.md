# Jukiza-Reminder — age rating, App Privacy, export compliance, content rights

Everything App Store Connect will ask on the way to submission, answered. Version 1.0.

---

## 1. Age rating questionnaire (Apple's 2025+ form)

App Store Connect › App Information › Age Rating › Edit. Answer exactly as below.

### Content

| Question | Answer | Why |
|---|---|---|
| Cartoon or Fantasy Violence | **None** | The app shows tasks and a countdown. |
| Realistic Violence | **None** | — |
| Prolonged Realistic or Sadistic Violence | **None** | — |
| Sexual Content or Nudity | **None** | — |
| Graphic Sexual Content and Nudity | **None** | — |
| Profanity or Crude Humour | **None** | All UI copy is written by the developer and is plain. |
| Alcohol, Tobacco or Drug Use or References | **None** | — |
| Mature or Suggestive Themes | **None** | — |
| Horror or Fear Themes | **None** | The due alert is a doorbell chime, not a scare. |
| Medical or Treatment Information | **None** | A user may type "dentist 10am", but the app gives no medical guidance. |
| Simulated Gambling | **None** | — |
| Real Gambling (money) | **No** | — |
| Contests | **No** | — |

### Capabilities and features

| Question | Answer | Why |
|---|---|---|
| Unrestricted Web Access (built-in browser / open web view) | **No** | No web view. The only outbound request possible is to a LAN address the user types in themselves. |
| User-Generated Content (content created by users and shared with or visible to other users) | **No** | The user types task titles and notes, but there is no server, no sharing, no feed, no profile, and no other user can ever see them. Content never leaves the device. |
| Messaging / chat between users | **No** | The "chat" tab is a text box that talks to an on-device language model. There is no communication with another person anywhere in the app. |
| In-App Purchases | **No** | The app is free with no purchases, no subscriptions, no tipping. |
| Advertising (does the app display ads?) | **No** | No ad SDK, no house ads, no promotions. |
| Does the app include parental controls? | **No** | Not applicable — there is nothing to restrict. |
| Age Assurance / age verification feature | **No** | — |
| Does the app share the user's location with other users? | **No** | The app never requests location. |
| Does the app allow users to share personal information with third parties? | **No** | — |
| Loot boxes or randomised items | **No** | — |
| Does the app provide access to a storefront or marketplace? | **No** | — |

### Expected result

**4+** on the Apple age-rating scale, in all storefronts.

If App Store Connect shows anything other than 4+, re-check the three questions that most often
get mis-answered for an app like this: *User-Generated Content*, *Messaging*, and *Unrestricted
Web Access*. All three are **No** here, for the reasons given above, and the reasoning is repeated
in the App Review notes in `store/listing.md`.

---

## 2. App Privacy ("nutrition label")

App Store Connect › App Privacy.

### Answer

**"Data Not Collected."**

On the first question — *Do you or your third-party partners collect data from this app?* — answer
**No**. Apple then records the label as "Data Not Collected" and asks nothing further.

### Why this is the correct answer, category by category

Apple defines "collect" as transmitting data off the device in a way that makes it accessible to
the developer or a partner for longer than needed to service the request. Jukiza-Reminder transmits
nothing to the developer under any circumstance — there is no developer-operated server, no
account system, no analytics SDK and no advertising SDK in the binary.

| Apple category | Collected? | Note |
|---|---|---|
| Contact Info (name, email, phone, address) | No | The app asks for a first name once, only to say "Good morning, <name>" on the Today screen. It is written to the local database and never transmitted. |
| Health & Fitness | No | Not requested, not read. |
| Financial Info | No | No purchases, no payment code. |
| Location | No | No location permission is declared in the Info.plist. |
| Sensitive Info | No | — |
| Contacts | No | The Contacts framework is not linked. |
| User Content (task titles, notes, photos, chat text) | No | Tasks, notes and assistant chat text are written to a local SQLite database in the App Group container and stay there. |
| Browsing History | No | No web view. |
| Search History | No | In-app search, if used, is local and not stored off device. |
| Identifiers (user ID, device ID, IDFA) | No | No IDFA, no advertising framework, no device identifier is read or stored. |
| Purchases | No | No StoreKit. |
| Usage Data (product interaction, ad data) | No | No analytics of any kind — no first-party telemetry, no crash reporter, no SDK. |
| Diagnostics (crash, performance, other logs) | No | Logs stay in the console on the device. Apple's own aggregated crash reporting (opt-in by the user at the OS level) is not developer collection and is not declared here. |
| Other Data | No | — |

### The one thing worth being explicit about: the optional local-server backend

If — and only if — the user opens Settings and types the address of an LLM server they run
themselves (Ollama, or any OpenAI-compatible endpoint, typically something like
`http://192.168.1.20:11434`), then the text they type into the chat tab is sent to **that**
server. That server belongs to the user. It is on their own network. The developer does not
operate it, cannot reach it and never learns it exists.

Under Apple's definition this is not developer collection, so it does not change the "Data Not
Collected" answer. It is nevertheless stated plainly in the privacy policy (`docs/privacy.html`,
section "The optional local server") and in the App Review notes, because a reviewer who sees an
`INTERNET`-capable settings screen should be able to find the explanation immediately.

The default assistant backend on iOS is Apple Intelligence (on-device Foundation Models), which
performs inference locally and sends nothing anywhere.

---

## 3. Export compliance

`iosApp/iosApp/Info.plist` already declares:

```xml
<key>ITSAppUsesNonExemptEncryption</key>
<false/>
```

With that key present, App Store Connect stops asking per build and the build becomes available to
TestFlight and submission immediately.

**What to answer if asked anyway** (e.g. in the Xcode Organizer upload sheet, or if the key is
ever removed):

| Question | Answer |
|---|---|
| Does your app use encryption? | **Yes** — only in the exempt sense below. |
| Does your app qualify for any of the exemptions provided in Category 5, Part 2 of the US Export Administration Regulations? | **Yes** |
| Which exemption? | *(b)(1) — the app uses, accesses, implements or incorporates encryption that is limited to standard encryption algorithms provided by Apple's operating system (HTTPS / TLS via URLSession), and performs no proprietary or non-standard cryptography.* |
| Does your app implement any proprietary encryption algorithms? | **No** |

Jukiza-Reminder uses TLS only for two user-initiated things: fetching a model file the user asked for,
and talking to an LLM endpoint the user configured. Both go through the system networking stack.
There is no custom crypto, no key exchange of the developer's own, no DRM.

France declaration: not required for apps relying solely on OS-provided exempt encryption.

---

## 4. Content rights

**"Does your app contain, show, or access third-party content?" → No.**

| Asset | Origin |
|---|---|
| App icon and all brand artwork | Original, drawn for this app (`store/brand/icon.svg`). |
| In-app illustrations | Original, drawn in Compose `Canvas` code (`shared/.../ui/Illustrations.kt`) — no raster assets, no stock art. |
| Doorbell sound (`androidApp/.../raw/doorbell.wav` and the iOS equivalent) | **Confirm before submitting** that this file is original or carries a licence permitting commercial redistribution (CC0 / public domain is fine; a "non-commercial" or "attribution required" licence is not, without the attribution). See the open item at the bottom of `docs/APP_STORE.md`. |
| Fonts | System fonts only (SF on Apple platforms). No licensed typefaces are bundled. |
| App name "Jukiza-Reminder" | Coined name. Do a quick trademark search before submitting; no third-party mark is used. |

### Open-source components and their licences

None of these require an in-app acknowledgements screen under their licences (Apache-2.0 and MIT
require the notice to travel with *redistributed source or binaries of the library*, which an App
Store binary satisfies by including the licence text in the repository). Keeping this table in the
repo is the record.

| Component | Use | Licence |
|---|---|---|
| Compose Multiplatform / Jetpack Compose | Shared UI on every platform | Apache-2.0 |
| AndroidX Room (KMP) | Local SQLite storage | Apache-2.0 |
| AndroidX Lifecycle / ViewModel | UI state | Apache-2.0 |
| Koog | Assistant agent framework | Apache-2.0 |
| Ktor | HTTP client (model download, local LLM endpoint) | Apache-2.0 |
| kotlinx.coroutines / kotlinx.serialization / kotlinx.datetime | Kotlin stdlib extensions | Apache-2.0 |
| SQLite | Embedded database | Public domain |
| llama.cpp | On-device inference, **macOS/desktop build only** | MIT |
| LiteRT-LM | On-device inference, **Android build only** | Apache-2.0 |
| Apple Foundation Models | On-device inference on iOS | Part of the OS; no third-party licence |

The iOS binary submitted to the App Store contains **no** llama.cpp and **no** LiteRT-LM — on iOS
the on-device model is the OS's own.
