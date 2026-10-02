# On-device summarizer: training report

Generated 2026-10-02 by `TrainSummarizerTest`.

## What the model does
For each sentence of an article it predicts how likely that sentence belongs in a summary. The app then picks
the top sentences (up to ~50 words, no repeated content) and shows them **verbatim, in original order**.
The model never writes new text, so it cannot introduce facts that the publisher did not write. It is a
20-feature → 24-unit neural network (≈ 530 weights) and runs in milliseconds on the phone, with no network
and no API.

## Training data (provenance)
1. **CNN/DailyMail 3.0.0** (abisee/cnn_dailymail on Hugging Face, train split): a standard research dataset of
   news articles paired with bullet-point highlights written by the publishers' journalists. Only CNN stories
   are used; Daily Mail text contains layout artifacts and was excluded.
2. **Tech articles from the app's own vetted feeds** (The Verge, Ars Technica, Wired, BBC, MIT Technology Review,
   Google/DeepMind/NVIDIA/AWS blogs, …). Article text comes from the publisher's page; the reference summary is
   the publisher's **own** summary line (dek) from its RSS feed. arXiv was excluded because its "summary" is the
   paper itself.

### Quality filters applied to every pair
- Feeds' summary lines that were truncated (“…”, “Read more”) or outside 12–70 words were dropped.
- Pages that were too short, paywalled or blocked (fewer than 3 clean sentences / 60 words) were dropped.
- Pairs whose reference is **not supported by the article text** (unigram recall < 35%) were dropped.
- Publisher lines that just repeat the article's first sentence were dropped (they aren't independent summaries).
- Duplicates by URL/ID and by identical opening text were removed.
- Captions, credits, newsletter/cookie boilerplate and fragments are removed before any sentence is scored,
  both in training and in the app.
- Train / validation / test splits are deterministic (hash of article ID), so test articles are never trained on.

## Data log
```
Tech candidates with a usable publisher summary: 67
CNN/DailyMail (CNN stories): 4732 docs -> 4730 kept (dropped: 1 too short/paywalled, 1 reference not supported by text, 0 dek copies lead)
Tech feeds (publisher deks): 230 docs -> 194 kept (dropped: 0 too short/paywalled, 0 reference not supported by text, 36 dek copies lead)
Splits — CNN train/val/test: 3789/489/452; Tech: 120/41/33
Training sentences: 115957 (9177 positive)
lead prior 0.00 -> val 20.90
lead prior 0.05 -> val 20.79
lead prior 0.10 -> val 20.37
lead prior 0.20 -> val 20.03
lead prior 0.30 -> val 20.07
lead prior 0.50 -> val 19.78
lead prior 0.80 -> val 19.74

| Method | Tech R-1 | Tech R-2 | Tech R-L | CNN R-1 | CNN R-2 | CNN R-L | Avg words |
|---|---|---|---|---|---|---|---|
| Lead (first sentences) | 27.4 | 11.1 | 20.6 | 31.6 | 11.6 | 21.0 | 50 |
| TextRank | 24.9 | 7.7 | 17.8 | 29.5 | 9.0 | 19.2 | 52 |
| **sudo model (ours)** | 28.4 | 11.8 | 21.1 | 33.0 | 12.6 | 22.0 | 51 |
| Oracle (upper bound) | 38.4 | 21.0 | 29.8 | 45.4 | 24.2 | 32.8 | 50 |
```

## Benchmark (held-out test sets, ROUGE F1 ×100, 50-word budget)
| Method | Tech R-1 | Tech R-2 | Tech R-L | CNN R-1 | CNN R-2 | CNN R-L | Avg words |
|---|---|---|---|---|---|---|---|
| Lead (first sentences) | 27.4 | 11.1 | 20.6 | 31.6 | 11.6 | 21.0 | 50 |
| TextRank | 24.9 | 7.7 | 17.8 | 29.5 | 9.0 | 19.2 | 52 |
| **sudo model (ours)** | 28.4 | 11.8 | 21.1 | 33.0 | 12.6 | 22.0 | 51 |
| Oracle (upper bound) | 38.4 | 21.0 | 29.8 | 45.4 | 24.2 | 32.8 | 50 |

*Lead* = first sentences (a strong baseline for news). *Oracle* = best possible extractive choice given the
reference, i.e. the ceiling for any sentence-picking method.

## What the model pays attention to (feature importance)
- relPosition: 1.84
- invPosition: 1.29
- textRank: 1.21
- tooShort: 1.12
- leadSimilarity: 1.10
- attribution: 1.07
- docLength: 1.03
- capitalRatio: 1.00
- numberRatio: 0.95
- titleOverlap: 0.94

## Sample outputs (tech test set, for human review)
**MIT welcomes David Siegel SM ’86, PhD ’91 as its next Innovation Fellow** — MIT News — AI

- Publisher summary: Computer scientist, entrepreneur, and philanthropist will collaborate with the MIT Schwarzman College of Computing to advance AI and scientific discovery.
- Ours (48 words): David Siegel SM ’86, PhD ’91, a computer scientist, entrepreneur, and philanthropist, will serve as the next MIT Innovation Fellow during the 2026-27 academic year. Working with the MIT Schwarzman College of Computing, Siegel will explore how artificial intelligence can accelerate scientific discovery at the Institute and beyond.

**Did AI Just Solve One of Mathematics’ Biggest Problems?** — KDnuggets

- Publisher summary: OpenAI’s agents reached a proposed solution in 88 hours. But the human research that came before, and the controversy that followed, raise a harder question: what actually counts as an AI discovery?
- Ours (42 words): OpenAI’s agents reached a proposed solution in 88 hours. More precisely, the system constructed a finite-time singularity for the three-dimensional Navier-Stokes equations with a smooth external force, one of the routes permitted by the official Clay Mathematics Institute formulation of the problem.

**Measles Is Forcing Hospitals to Adapt to a New Normal** — Wired

- Publisher summary: The US resurgence of measles is forcing hospitals and health systems to adopt new protocols and procedures to deal with a disease they thought was in the past.
- Ours (54 words): John Goldman had not seen a case of measles in 30 years. His health system, like others, has had to adapt to a new normal as measles makes a comeback in the United States. The University of Pittsburgh Medical Center’s community hospital in Lititz is in Lancaster County, the center of Pennsylvania’s current outbreak.

**3 Questions: A new resource to empower young entrepreneurs** — MIT News — AI

- Publisher summary: Martin Trust Center Managing Director Bill Aulet introduces Dear Dreamer, a free platform for middle and high school students who want to learn about entrepreneurship.
- Ours (42 words): The book “Disciplined Entrepreneurship” by Bill Aulet, managing director of the Martin Trust Center for MIT Entrepreneurship and the Ethernet Inventors Professor of the Practice at the MIT Sloan School of Management, walks readers through the 24 steps of starting a venture.

**Walter Torous named executive director of MIT Center for Real Estate** — MIT News — AI

- Publisher summary: The senior lecturer, already director of the degree program, will now oversee all aspects of the center’s activities and operations.
- Ours (52 words): Walter Torous, senior lecturer in the MIT Department of Urban Studies and Planning (DUSP) and the MIT Sloan School of Management, and director of the Master of Science in Real Estate Development Program (MSRED), was recently named executive director of the MIT Center for Real Estate (CRE) — effective July 1, 2026.

**GraphRAG with TypeSafe Jev: A System One Approach to Scalable Knowledge Graphs** — Towards Data Science

- Publisher summary: How calibrated decision models can handle high-frequency graph decisions while LLMs remain focused on reasoning, synthesis, and open-ended generation.
- Ours (44 words): Over the past three years, Retrieval-Augmented Generation (RAG) has evolved from simple vector similarity search over chunked documents to include complex, graph-native architectures known as GraphRAG. Historically, engineers have defaulted to calling general-purpose, autoregressive LLMs (such as gpt, claude etc) for making these micro-decisions.

