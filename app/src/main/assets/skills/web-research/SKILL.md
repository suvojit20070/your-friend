---
name: Web research
description: Answer questions that need current or detailed information from the web, with sources.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [search, look up, latest, news, compare, research, find out]
required_tools: [py_web_search, py_web_read]
---

# Web research

Use this when the answer depends on facts you are not sure about or that may have changed.

1. Rewrite the question into 1–3 short search queries (keywords, not sentences). Add the current year for time-sensitive topics.
2. Call `py_web_search` with the best query. Read the titles and snippets; pick the 2–4 most authoritative results (official sites, documentation, reputable news, Wikipedia for background).
3. Call `py_web_read` on each chosen URL. Read only what you need; if a page is long, look for the relevant section.
4. If the sources disagree or are thin, run one more search with a different query. Stop after at most 6 page reads.
5. Answer in the user's language. Lead with the direct answer, then the key details.
6. End with a short "Sources" list (title — URL) for the pages you actually used.

Rules:
- Never invent a source or a quote. If you could not find something, say so.
- Do not open login pages, downloads or forms during research.
- Summarize in your own words; do not paste long passages.
