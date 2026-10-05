# sudo

An Android app for tech, AI/ML and data news from vetted sources, plus jobs and internships matched to your resume.

## Features

| Area | What it does |
|---|---|
| **Tech Pulse** | 10 major outlets (The Verge, Ars Technica, TechCrunch, Wired, BBC, MIT Tech Review…). ~50-word summaries made **on the phone by our own trained model** (no API). Tap a card to open the original article. |
| **AI & Data** | Official lab blogs (Google, DeepMind, OpenAI, NVIDIA, AWS, Hugging Face), AI desks, KDnuggets / TDS and arXiv. Also shows trending Hugging Face models, daily papers and new GitHub repos. |
| **Skills Radar** | New and rising AI/ML/DS terms and tools, mined from today's arXiv papers, news, GitHub and job posts. Nothing is hardcoded. A term is shown only if all of these hold. **It's recent:** OpenAlex's year-by-year publication counts show it took off within the last 3 years, with almost no presence 5–10 years earlier, so long-established ideas like SDKs, differential privacy or world models are excluded. **It's active in AI/ML research now:** at least 5 papers in the last 30 days in arXiv's AI/ML categories. **It's still growing.** **It can be explained by quoting a source:** Wikipedia, a paper, or the project's own description, always linked. Tools from job posts must be confirmed by both GitHub and PyPI/npm. |
| **Auto refresh** | Refreshes every 15 minutes in the background (Android's minimum; it may be delayed when the phone is idle). Pull down to refresh immediately. |
| **Story clustering** | The same story from several outlets shows as one card with a "+N outlets" label, so independent coverage is visible. |
| **Why it matters** | A one-line note on how a story relates to your skills (needs AI). |
| **Jobs & Internships** | Remote and on-site roles from Remotive, Remote OK, The Muse, Arbeitnow, Adzuna (optional key) and company career pages on Greenhouse, Lever and Ashby (direct from the employer). Filters for internships, remote, on-site, near me and direct from employer. |
| **Match score & skill gaps** | A score computed locally from a fixed skill vocabulary, with matched and missing skills listed. Missing skills link to a learning search. Optional AI analysis gives a fit score, strengths, gaps and eligibility notes. |
| **Assisted apply** | One-tap copy of your details, an AI cover letter and answers written only from your real resume, and a link to the employer's form. You review and submit yourself. |
| **Tracker** | Saved → Applied → Interview → Offer / Rejected, with notes and a follow-up reminder 7 days after you apply. |
| **Alerts & widget** | Notifications when a headline or new job mentions one of your keywords. Home-screen headlines widget. |
| **Saved** | Bookmarked stories are kept indefinitely and readable offline. |

## How the app stays reliable

- News comes only from a fixed list of established outlets and official blogs. The full list is shown in Settings.
- Items without a publish date are dropped, never shown as "latest". Coupon and deal posts are filtered out.
- Default summaries come from our own on-device model, which picks the article's key sentences **word for word**. It cannot invent facts. They are labelled "Key sentences · on-device". Training data, filters and benchmark are in [ml/REPORT.md](ml/REPORT.md).
- Optional Gemini summaries use only the article's own text, are checked before display, and are labelled "AI summary". If neither is possible, the publisher's excerpt is shown, labelled "Excerpt".
- AI never generates links. Every link comes from the source itself.
- Job listings show their source and posting date. Employer career-page listings are marked "Direct from employer". Aggregator listings older than 45 days are removed, and listings that disappear from their source are removed after 3 days.
- A scam filter flags postings that ask for fees, contact over WhatsApp/Telegram, ID or bank details, and similar red flags.

## Setup

1. Install the APK:
   - Debug build: `app/build/outputs/apk/debug/app-debug.apk`
   - Release build: `app/build/outputs/apk/release/app-release.apk`
2. Optional: get a free **Gemini API key** at https://aistudio.google.com/apikey and paste it in onboarding or Settings. It enables AI summaries, "why it matters", resume reading, match analysis and cover letters.
3. Optional: get free **Adzuna** keys at https://developer.adzuna.com for the widest coverage of local on-site jobs (19 countries, including India).
4. Upload your resume PDF under Profile, which is the avatar in the top right.

## Testing

See [testing/README.md](testing/README.md) for the test suites. The latest live truth audit is in [testing/TRUTH_REPORT.md](testing/TRUTH_REPORT.md).

## Build

Requirements: JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`).

```
gradlew assembleDebug          # debug APK
gradlew assembleRelease        # minified release APK (signed with the debug key for personal use)
gradlew :app:testDebugUnitTest --tests "*LiveSourcesTest*" -i   # checks every live source
gradlew :app:testDebugUnitTest --tests "*ScreenshotTest*"       # renders UI to app/build/screens
set TRAIN=1 && gradlew :app:testDebugUnitTest --tests "*TrainSummarizerTest*" -i   # rebuild datasets & retrain the summarizer
```

## Stack

Kotlin, Jetpack Compose (Material 3), Room, WorkManager, DataStore, OkHttp, kotlinx.serialization, Jsoup, Coil, Glance (widget), and the Gemini REST API.

## Code map

```
app/src/main/java/com/technewz/app/
  ml/        on-device summarizer: sentence splitter, features, model inference
  data/      Room entities & DAOs, settings/profile, News & Jobs repositories, AI prompts
  net/       HTTP, RSS/Atom parser, feed list, job & trending sources, Gemini client
  work/      15-min refresh worker, follow-up reminders, notifications
  widget/    Glance home-screen widget
  ui/        theme, shared components, screens (news, jobs, job detail + apply, tracker, saved, profile, settings, onboarding)
```
