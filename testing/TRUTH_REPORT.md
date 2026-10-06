# Live truth audit

Run: 2026-10-06T10:07:08.694227500+05:30[Asia/Calcutta]

Pipelines: 80 articles from 14 sources (failed feeds: []), 1782 jobs (failed sources: []), radar: 0 candidates, 0 verified checks, 0 accepted / 0 rejected

## 1. News comes only from the publishers' own feeds
- Articles whose link is on the publisher's own site: 80/80
- Headlines identical to the publisher's feed right now: 80 (0 rotated out of the feed since fetch)
- Publish dates within the last 72 h and not in the future: 80/80

## 2. Live article pages: headline and summary checked on the page itself
- Pages checked: 36; reachable: 31; blocked/unverifiable: 5 (HTTP 405×5)
- Headline on the page matches the app's headline: 31/31
- On-device summaries found word-for-word in the publisher's page or feed text: 15/15

## 3. Job listings link to live postings
- Job pages checked: 35; reachable: 23; title found on page: 23
  - Greenhouse – page needs JavaScript × 1
  - Ashby – page needs JavaScript × 5
  - Remotive – HTTP 403 × 5
  - Arbeitnow – HTTP 403 × 1

## 4. Skills Radar: every explanation and number checked against its source

Rejected by verification (sample): 

## 5. Trending now: every item re-checked against the live API
- Trending items verified against the live source: 45/45

## Verdict
No fabricated, off-site, misdated or unverifiable-by-design content found.
