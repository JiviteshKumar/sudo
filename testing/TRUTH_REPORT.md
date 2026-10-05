# Live truth audit

Run: 2026-10-05T21:26:35.814669100+05:30[Asia/Calcutta]

Pipelines: 80 articles from 17 sources (failed feeds: []), 1533 jobs (failed sources: [Arbeitnow]), radar: 68 candidates, 21 verified checks, 5 accepted / 16 rejected

## 1. News comes only from the publishers' own feeds
- Articles whose link is on the publisher's own site: 80/80
- Headlines identical to the publisher's feed right now: 80 (0 rotated out of the feed since fetch)
- Publish dates within the last 72 h and not in the future: 80/80

## 2. Live article pages: headline and summary checked on the page itself
- Pages checked: 42; reachable: 33; blocked/unverifiable: 9 (HTTP 405×4, InterruptedIOException×3, HTTP 403×2)
- Headline on the page matches the app's headline: 33/33
- On-device summaries found word-for-word in the publisher's page or feed text: 14/14

## 3. Job listings link to live postings
- Job pages checked: 30; reachable: 19; title found on page: 19
  - Greenhouse – page needs JavaScript × 1
  - Ashby – page needs JavaScript × 5
  - Remotive – HTTP 403 × 5

## 4. Skills Radar: every explanation and number checked against its source
  - Age check — OpenAlex took off: 2024; arXiv AI/ML papers 4–5 years ago: 11 vs ~1860/yr now
- **On-policy distillation (OPD)** [Rising Concept] — Took off in 2024 (OpenAlex works per year: 2021: 13, 2022: 21, 2023: 27, 2024: 41, 2025: 95, 2026: 632). arXiv AI/ML: 155 papers in the last 30 days vs 316 in the 150 days before
  - What: “On-policy distillation (OPD) is a promising approach for training language agents, providing dense teacher supervision on student-generated trajectories.” — arXiv: PivotOPD: Learning to Recover from Pivotal Mistakes in Multi-Turn Agents → verbatim ✓
  - All checks passed
  - Age check — OpenAlex took off: 2026; arXiv AI/ML papers 4–5 years ago: 0 vs ~1320/yr now
- **World action models (WAMs)** [New Concept] — Took off in 2026 (OpenAlex works per year: 2021: 0, 2022: 0, 2023: 0, 2024: 1, 2025: 3, 2026: 334). arXiv AI/ML: 110 papers in the last 30 days vs 181 in the 150 days before
  - What: “World action models (WAMs) are large embodied policies that jointly predict future video and the actions to execute, emitting a fixed-length action chunk per inference call.” — arXiv: SplineWAM: Adaptive Action Horizons for World Action Models via B-Spline Representations → verbatim ✓
  - All checks passed
  - Age check — OpenAlex took off: 2025; arXiv AI/ML papers 4–5 years ago: 1 vs ~516/yr now
- **recursive self-improvement (RSI)** [New Concept] — Took off in 2025 (OpenAlex works per year: 2021: 2, 2022: 8, 2023: 4, 2024: 10, 2025: 70, 2026: 650). arXiv AI/ML: 43 papers in the last 30 days vs 36 in the 150 days before
  - What: “Recursive self-improvement (RSI) is a hypothesized process in which artificial general intelligence (AGI) systems rewrite their own computer code, causing an intelligence explosion resulting from enhancing their own capabilities and intellectual capacity, theoretically resulting in superintelligence. RSI research continues into 2026, with major increases in AI self-coding, but no sign of an intelligence explosion or superintelligence.” — Wikipedia → verbatim ✓
  - Where: “Recursive self-improvement (RSI) seeks to enable AI systems to participate in improving their own capabilities.” — arXiv: RSI-Master: Structuring Experiments to Guide Autonomous Model Improvement
  - All checks passed
  - Age check — OpenAlex took off: 2024; arXiv AI/ML papers 4–5 years ago: 0 vs ~408/yr now
- **Joint-Embedding Predictive Architecture (JEPA)** [Rising Concept] — Took off in 2024 (OpenAlex works per year: 2021: 0, 2022: 1, 2023: 6, 2024: 46, 2025: 84, 2026: 573). arXiv AI/ML: 34 papers in the last 30 days vs 106 in the 150 days before
  - What: “Joint Embedding Predictive Architectures (JEPAs) are a promising paradigm for learning task-agnostic latent world models without visual reconstruction.” — arXiv: MotionJEPA: Preventing Temporal Feature Collapse by Capturing Visual Changes in Latent Spa → verbatim ✓
  - Where: “Joint-Embedding Predictive Architectures (JEPAs) enable agents to plan in latent space by imagining the outcomes of candidate actions, yet task specification remains a bottleneck.” — arXiv: Latent Goal Prediction from Language for Model-Based Planning
  - All checks passed
  - Age check — OpenAlex took off: 2024; arXiv AI/ML papers 4–5 years ago: 0 vs ~3624/yr now
- **Vision-Language-Action (VLA)** [Rising Concept] — Took off in 2024 (OpenAlex works per year: 2021: 0, 2022: 1, 2023: 9, 2024: 63, 2025: 590, 2026: 2395). arXiv AI/ML: 302 papers in the last 30 days vs 823 in the 150 days before
  - What: “In robot learning, a vision–language–action model (VLA) is a class of multimodal foundation models that integrates vision, language and actions. Given an input image of the robot's surroundings and a text instruction, a VLA directly outputs low-level robot actions that can be executed to accomplish the requested task.” — Wikipedia → verbatim ✓
  - Where: “Vision-language-action (VLA) policies enable diverse robotic manipulation but can fail during execution without recognizing their own errors.” — arXiv: SocialVLA: A Social Perception Gateway for Human-Reaction-Based Failure Detection and Reco
  - All checks passed

Rejected by verification (sample): World models (WMs) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 354, 2; area under the receiver operating characteristic (AUROC) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 5382, ; in-distribution (ID) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 333161; Product, Design & Engineering (PDE) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 19, 20; differential privacy (DP) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 1421, ; Gaussian Process (GP) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 4049, ; out-of-distribution (OOD) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 1104, ; key-value (KV) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 778, 2; Measurement Partner (MMP) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 0, 202; equirectangular projection (ERP) — Established or too little evidence — never surged above its own history. OpenAlex works per year: 2021: 33, 20; TypeScript — Asked for by 23 employers in 124 current postings; GitHub microsoft/TypeScript: 111351 stars; confirmed by npm; RMTUyLjU4LjQyLjE2Mw — Asked for by 20 employers in 24 current postings; no matching GitHub project

## 5. Trending now: every item re-checked against the live API
- Trending items verified against the live source: 45/45

## Verdict
No fabricated, off-site, misdated or unverifiable-by-design content found.
