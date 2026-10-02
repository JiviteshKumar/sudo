# Live truth audit

Run: 2026-10-02T21:54:23.823221400+05:30[Asia/Calcutta]

Pipelines: 80 articles from 18 sources (failed feeds: []), 1791 jobs (failed sources: []), radar: 55 candidates, 21 verified checks, 7 accepted / 14 rejected

## 1. News comes only from the publishers' own feeds
- Articles whose link is on the publisher's own site: 80/80
- Headlines identical to the publisher's feed right now: 80 (0 rotated out of the feed since fetch)
- Publish dates within the last 72 h and not in the future: 80/80

## 2. Live article pages: headline and summary checked on the page itself
- Pages checked: 42; reachable: 38; blocked/unverifiable: 4 (HTTP 405×4)
- Headline on the page matches the app's headline: 38/38
- On-device summaries found word-for-word in the publisher's page or feed text: 15/15

## 3. Job listings link to live postings
- Job pages checked: 35; reachable: 21; title found on page: 21
  - Ashby – page needs JavaScript × 5
  - Remotive – HTTP 403 × 5
  - Arbeitnow – HTTP 403 × 4

## 4. Skills Radar: every explanation and number checked against its source
- **Vision-Language-Action (VLA)** [Rising Concept] — arXiv: 312 papers in the last 30 days vs 829 in the 150 days before; 2089 all-time; earliest arXiv use of the phrase 3y ago
  - What: “In robot learning, a vision–language–action model (VLA) is a class of multimodal foundation models that integrates vision, language and actions. Given an input image of the robot's surroundings and a text instruction, a VLA directly outputs low-level robot actions that can be executed to accomplish the requested task.” — Wikipedia → verbatim ✓
  - Where: “Future prediction is increasingly used to improve vision-language-action (VLA) policies, based on the premise that anticipating scene evolution encourages representations useful for control.” — arXiv: Where Predictive Supervision Goes Shapes What VLA Policies Learn
  - All checks passed
- **recursive self-improvement (RSI)** [Rising Concept] — arXiv: 47 papers in the last 30 days vs 37 in the 150 days before; 107 all-time; earliest arXiv use of the phrase 12y ago
  - What: “Recursive self-improvement (RSI) is a hypothesized process in which artificial general intelligence (AGI) systems rewrite their own computer code, causing an intelligence explosion resulting from enhancing their own capabilities and intellectual capacity, theoretically resulting in superintelligence. Numerous attempts at RSI have been made, none so far showing any sign of intelligence explosion or superintelligence.” — Wikipedia → verbatim ✓
  - Where: “Recursive self-improvement (RSI) seeks to enable AI systems to participate in improving their own capabilities.” — arXiv: RSI-Master: Structuring Experiments to Guide Autonomous Model Improvement
  - All checks passed
- **Flow Matching (FM)** [Rising Concept] — arXiv: 285 papers in the last 30 days vs 930 in the 150 days before; 2799 all-time; earliest arXiv use of the phrase 24y ago
  - What: “Flow matching is a powerful tool for generative modeling, but emerging applications in robotics, planning, and control require inference-time constraints on generated outputs.” — arXiv: Constrained Flow Matching via Lagrangian Dual Flows → verbatim ✓
  - Where: “Flow matching is central to 3D generation, yet in practice its reinforcement learning (RL) methods are largely adapted from 2D visual generation.” — arXiv: Flow Matching Reinforcement for 3D Mesh Generation via Dynamic Homing Optimization
  - All checks passed
- **on-policy self-distillation (OPSD)** [New Concept] — arXiv: 56 papers in the last 30 days vs 125 in the 150 days before; 186 all-time; earliest arXiv use of the phrase 8mo ago
  - What: “On-Policy Self-Distillation (OPSD), which lets one model serve as both teacher and student with the teacher receiving additional privileged information such as the question and ground-truth (GT) answer, can supply such token-level signals.” — arXiv: EGSD: Event-Grounded Self-Distillation for Streaming Video Understanding → verbatim ✓
  - Where: “These results show that spatially grounded privileged information can induce broader perceptual capabilities through on-policy self-distillation, enabling substantial synthetic-to-real transfer beyond the task and data distribution used for post-training.” — arXiv: Where-OPD: Spatially Guided On-Policy Self-Distillation of MLLMs with Synthetic Scenes
  - All checks passed
- **System One Model (SOM)** [Rising Concept] — arXiv: 13 papers in the last 30 days vs 1 in the 150 days before; 18 all-time; earliest arXiv use of the phrase 14y ago
  - What: “Jev is a non-generative "System One" model that assigns probabilities to predefined answer options and cannot answer outside them.” — arXiv: Jev in Medicine: A Benchmark Evaluation → verbatim ✓
  - Where: “Each item is queried through the three Jev primitives - Noul, Choice and Score - and evaluated by total variation distance from the ground-truth distribution, which can be used to estimate a soft accuracy of System One Models.” — arXiv: Jev thinks "I don't know'', but doesn't say it: Introducing Sys1Cal-v1 Dataset for Probabi
  - All checks passed
- **World Action Models (WAMs)** [Rising Concept] — arXiv: 105 papers in the last 30 days vs 177 in the 150 days before; 300 all-time; earliest arXiv use of the phrase 2y ago
  - What: “World action models (WAMs) are large embodied policies that jointly predict future video and the actions to execute, emitting a fixed-length action chunk per inference call.” — arXiv: SplineWAM: Adaptive Action Horizons for World Action Models via B-Spline Representations → verbatim ✓
  - All checks passed
- **tabular foundation models (TFMs)** [Rising Concept] — arXiv: 44 papers in the last 30 days vs 127 in the 150 days before; 261 all-time; earliest arXiv use of the phrase 3y ago
  - What: “Tabular foundation models (TFMs) are increasingly popular because they deliver strong predictions on new datasets through in-context learning, without task-specific training or extensive tuning.” — arXiv: Architecture Alignment With Sparse Priors in Tabular Foundation Models → verbatim ✓
  - Where: “While conventional methods rely on dataset-specific training and configuration search, recent tabular foundation models (TFMs) enable zero-shot anomaly detection on unseen datasets via in-context learning.” — arXiv: TaskBridge: Bridging Unsupervised Tabular Anomaly Detection and In-Context Learning via Vi
  - All checks passed

Rejected by verification (sample): Reinforcement learning with verifiable rewards (RLVR) — Not new or rising — arXiv: 79 papers in the last 30 days vs 374 in the 150 days before; 98; out-of-distribution (OOD) — Not new or rising — arXiv: 231 papers in the last 30 days vs 1099 in the 150 days before; ; fixed points (FPs) — Not new or rising — arXiv: 324 papers in the last 30 days vs 1082 in the 150 days before; ; continuous integration (CI) — Not new or rising — arXiv: 17 papers in the last 30 days vs 83 in the 150 days before; 651; Kurdyka--\L ojasiewicz (KL) — Not found in arXiv; principal component analysis (PCA) — Not new or rising — arXiv: 72 papers in the last 30 days vs 270 in the 150 days before; 51; false discovery rate (FDR) — Not new or rising — arXiv: 23 papers in the last 30 days vs 91 in the 150 days before; 101; In-context learning (ICL) — Not new or rising — arXiv: 104 papers in the last 30 days vs 432 in the 150 days before; 3; SaaS — Asked for by 43 employers in 218 current postings; GitHub async-labs/saas: 4517 stars; not; TypeScript — Asked for by 41 employers in 140 current postings; GitHub microsoft/TypeScript: 111308 sta; DevOps — Asked for by 33 employers in 90 current postings; no matching GitHub project; GitHub — Asked for by 32 employers in 79 current postings; no matching GitHub project

## Verdict
No fabricated, off-site, misdated or unverifiable-by-design content found.
