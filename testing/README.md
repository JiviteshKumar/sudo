# How sudo is tested

All suites live in `app/src/test`. Run all of them with:

```
set LIVE=1 && gradlew :app:testDebugUnitTest
```

Without `LIVE=1`, the two tests that need the internet are skipped.

| Suite | Kind | What it proves |
|---|---|---|
| `TrustRulesTest` | Unit, offline | **Fake or injected content is rejected:** links to another site, undated items, items dated in the future or older than 72 h, `javascript:` links, blank headlines, and coupon or deal posts. **The feed parsers** handle RSS (with CDATA and media tags), Atom, and broken feeds. **The Skills Radar is not hardcoded:** acronyms are learned from text, so "Cache-Augmented Generation (CAG)" is recognised but "2024 (USA)" isn't, and new terms rank above established ones. Explanations are quoted word-for-word from their source. **The grounding check** rejects AI text that adds a number, a name or a claim the source doesn't contain. |
| `SummarizerTest` | Unit, offline | **Sentence splitting** handles abbreviations, decimals and initials. **Every on-device summary is quoted word-for-word** from the article and stays within 20–60 words. Boilerplate (newsletter prompts, photo credits) is never chosen. Text that's too short to summarise falls back to the publisher's excerpt. |
| `LiveSourcesTest` | Integration, live | **All news feeds and job sources respond** and parse with dates and valid links. Prints which stories were clustered together so wrong merges can be spotted. |
| `AppUiTest` | UI (Robolectric + Compose) | Runs the real app against a seeded database and checks: the home feed, topic filters, the AI tab and Skills Radar, the internship filter, job detail with assisted apply and saving to the tracker, bookmarks appearing under Saved, and finishing onboarding. |
| `ScreenshotTest` | Visual | Renders the main screens in light and dark mode to `app/build/screens` for design review. |
| `LiveTruthTest` | **End-to-end truth audit, live** | Runs the real news, jobs and radar pipelines, then checks everything independently against the original sources. **Headlines** must be identical to the publisher's feed and match the live article page. **Links** must be on the publisher's own site. **Dates** must be valid. **On-device summaries** must appear word-for-word in the publisher's text. **Job links** must be live and show the job title. **Every Skills Radar term:** its definition must match its cited source (Wikipedia, the arXiv abstract page, or the PyPI/npm registry), its arXiv count must be reproducible, and its acronym expansion must really be used in papers. Writes `testing/TRUTH_REPORT.md`. |
| `TrainSummarizerTest` | Training (`TRAIN=1`) | Rebuilds the summarizer's datasets, retrains it and benchmarks it. See `ml/REPORT.md`. |

## What the tests cannot prove

- **Whether a publisher's own reporting is true.** The app only uses established outlets and official lab blogs, and shows when several outlets cover the same story. It never writes or rewrites news itself, but it can't fact-check the publisher.
- **Every page.** Some pages can't be checked automatically: sites that block automated requests (such as Remotive) and job boards that need JavaScript to load (Ashby). The audit reports these as "unverifiable", never as passed.
- **What a phrase meant when it first appeared.** "Earliest arXiv use of the phrase" can come from an older, unrelated meaning, which is why the app labels it that way.
