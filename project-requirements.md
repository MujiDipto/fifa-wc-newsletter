# World Cup Newsletter — Project Spec

This is an implementation-ready spec. Read this fully before writing any code. Where a decision has been made, it is final for v1; where something is flagged as a v1 simplification, treat it as deliberate, not a gap to "fix" with extra abstraction.

## What this is
A daily personalized email newsletter for a small, manually-managed list of subscribers, built from live 2026 FIFA World Cup data, composed with an LLM. Each subscriber follows one team and/or one player and gets a recap, a personalized update, and a look-ahead.

## Why concurrency matters here (read before touching java.util.concurrent)
This project exists specifically to apply hand-built concurrency primitives (bounded worker pools, prioritized scheduling, fan-out with retry/backoff) to a real pipeline with genuine external dependencies: two independent rate-limited data APIs, a rate-limited LLM API, and an email send step. **Do not replace the hand-rolled concurrency utilities with a library** (no Reactor, RxJava, or similar) and **do not use Java virtual threads/structured concurrency as a shortcut** that hides the manual coordination. The point of the project is the explicit implementation, not the cleanest possible solution. If a library would obviously be "more correct" in production, that's fine to note in a comment, but build the hand-rolled version anyway.

I (the author) already have working implementations of a bounded worker pool, a fan-out queue with dead-letter handling, and a prioritized job scheduler from prior practice. **Bring those existing classes into this repo as the starting point for the `concurrency` package rather than rewriting from scratch.** Adapt them to this domain, don't redesign them.

## Goals (v1)
- Ingest match results, group standings, and player data from two independent sources daily.
- Each subscriber follows one team and/or one player, configured manually.
- Generate one personalized email per subscriber: match day recap, followed player update, followed team update + standing, next match day preview.
- Run unattended on a daily schedule.
- Survive real failure modes: a data source down, an LLM rate limit hit, an email bounce, none of these should kill the whole run.

## Non-Goals (v1) — do not build these
- No public sign-up flow, no accounts, no auth beyond reading a config file.
- No web UI. No payment. No multi-tournament support.
- No Vault/secrets-manager integration yet (see Secrets section, this is a deliberate v1 simplification).
- No retry of the entire pipeline if it fails outright, just resilience within a single run.

---

## Tech Stack

**Java (core pipeline)**
- Java 21
- Build tool: Maven
- HTTP calls to external APIs: built-in `java.net.http.HttpClient`, no extra HTTP library needed
- JSON: Jackson (`com.fasterxml.jackson.core`)
- Persistence: SQLite via `org.xerial:sqlite-jdbc`, plain JDBC, no ORM
- Email sending: Jakarta Mail (`com.sun.mail:jakarta.mail`), configured against any SMTP relay via env vars
- Logging: SLF4J + Logback
- Testing: JUnit 5

**Python (LLM composer, small internal service)**
- Python 3.11+
- FastAPI + uvicorn, exposing a single internal endpoint
- Google's official `google-genai` SDK for Gemini calls
- `requirements.txt`, no need for poetry/uv at this size

**Hosting**
- OCI Always Free Ampere A1 instance (2 OCPU / 12 GB as of the current free tier)
- Java app and Python service both run on the same VM, Java calls Python over `localhost` HTTP
- Daily trigger via cron or a systemd timer, not a long-running web server

---

## External APIs

### 1. football-data.org (primary: matches, standings)
- Base URL: `https://api.football-data.org/v4/`
- Auth: `X-Auth-Token` header, free token from signup at football-data.org
- Free tier confirmed to include the World Cup (competition code `WC`), 10 requests/minute, no credit card
- Free tier does **not** include detailed player stats/lineups — that gap is covered by source #2
- Key endpoints: `/competitions/WC/matches?dateFrom=...&dateTo=...`, `/competitions/WC/standings`, `/competitions/WC/scorers`

### 2. API-Football (secondary: player stats/lineups)
- Accessed via RapidAPI, host `api-football-v1.p.rapidapi.com`
- Auth: `X-RapidAPI-Key` + `X-RapidAPI-Host` headers
- Free tier: 100 requests/day. Verify at signup whether a card is required, this has varied by RapidAPI listing
- Used specifically to fill the player-stat gap football-data.org's free tier doesn't cover

### 3. Gemini API (LLM composition)
- SDK: `google-genai` (Python)
- Auth: API key from Google AI Studio (aistudio.google.com), no card required
- Model: start with `gemini-2.5-flash`. Pro-tier Gemini models are **not** free as of April 2026, do not use them. If rate limits become an issue, fall back to a Flash-Lite variant
- Free tier limits fluctuate, verify current RPM/RPD in AI Studio rather than trusting any hardcoded assumption in this doc

### 4. Email delivery
- Generic SMTP via Jakarta Mail, configured against OCI's Email Delivery service (3000 free emails/month) by default, but not hardcoded to OCI specifically, any SMTP relay works via env var config

---

## Secrets (v1 simplification)
Store all credentials in a single gitignored `.env` file at the repo root, loaded into environment variables at startup. Required keys:
```
FOOTBALL_DATA_API_TOKEN=
RAPIDAPI_KEY=
GEMINI_API_KEY=
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```
Migrating these to OCI Vault is a deliberate future hardening step, not part of v1. Do not build Vault integration now.

---

## Repository Structure
```
worldcup-newsletter/
├── pom.xml
├── README.md
├── .env                          (gitignored)
├── config/
│   └── subscribers.yaml
├── src/main/java/com/muji/worldcup/
│   ├── Main.java
│   ├── ingestion/
│   │   ├── DataSource.java            (interface)
│   │   ├── FootballDataClient.java
│   │   ├── ApiFootballClient.java
│   │   └── IngestionService.java
│   ├── model/
│   │   ├── Match.java
│   │   ├── Team.java
│   │   ├── Player.java
│   │   ├── GroupStanding.java
│   │   ├── Subscriber.java
│   │   └── ContextBundle.java
│   ├── concurrency/
│   │   ├── BoundedWorkerPool.java     (ported from prior practice)
│   │   ├── PrioritisedJobScheduler.java (ported from prior practice)
│   │   ├── FanOutQueue.java           (ported from prior practice)
│   │   └── RetryWithBackoff.java
│   ├── persistence/
│   │   ├── Database.java
│   │   └── SqliteRepository.java
│   ├── bundling/
│   │   └── ContextBundleBuilder.java
│   ├── composer/
│   │   └── PythonComposerClient.java
│   ├── delivery/
│   │   └── EmailSender.java
│   ├── lifecycle/
│   │   └── TournamentLifecycle.java
│   └── config/
│       └── SubscriberConfigLoader.java
├── src/test/java/...
└── python-composer/
    ├── requirements.txt
    ├── main.py                       (FastAPI app)
    ├── gemini_client.py
    └── prompts.py
```

## Subscriber Config Format
`config/subscribers.yaml`, flat list, no DB needed for this:
```yaml
subscribers:
  - email: friend1@example.com
    team: Argentina
    player: Messi
  - email: friend2@example.com
    team: Spain
    player: null
```

## Core Data Models (fields, not full code)
- `Match`: id, homeTeam, awayTeam, homeScore, awayScore, status, group, kickoffTime, source
- `Team`: name, fifaCode, group, flagUrl
- `GroupStanding`: group, teamName, played, won, drawn, lost, goalDifference, points, position
- `Player`: name, team, goals, assists, appearances, lastMatchSummary (nullable)
- `Subscriber`: email, followedTeam (nullable), followedPlayer (nullable)
- `ContextBundle`: subscriber, matchDayRecapText, teamUpdate (nullable), playerUpdate (nullable), nextMatchDayPreview, eliminationStatus (enum: ACTIVE, JUST_ELIMINATED, ALREADY_HANDLED)

---

## Build Order

**Phase 0 — Scaffolding.** Maven project, directory structure, `.env` loading, subscriber config loader. Done when: `mvn compile` succeeds and the app starts, logs "loaded N subscribers," exits cleanly.

**Phase 1 — Ingestion.** `FootballDataClient` and `ApiFootballClient` implementing a shared `DataSource` interface, both fetched concurrently through the bounded worker pool respecting each source's own rate limit. Normalize into the model classes. SQLite schema + repository for persistence. Done when: running ingestion populates SQLite with that day's matches and standings, verifiable by a direct query.

**Phase 2 — Bundling + prioritization.** Build one shared content bundle per team and per player per day (not per subscriber). Run subscribed teams/players through the prioritized scheduler ahead of the other ~40 teams nobody's tracking. Assemble per-subscriber `ContextBundle` objects. Done when: given the sample `subscribers.yaml`, the app prints a correctly populated bundle per subscriber.

**Phase 3 — Python composer.** FastAPI service, single `POST /compose` endpoint taking a `ContextBundle` JSON, calling Gemini, returning subject + body text. Java `PythonComposerClient` calls this over local HTTP, bounded by the worker pool, wrapped in retry-with-backoff for 429s. Done when: a sample bundle produces a real composed newsletter from Gemini end to end.

**Phase 4 — Delivery.** `EmailSender` via Jakarta Mail/SMTP, sends fanned out concurrently to all subscribers, dead-letter log on repeated failure. Done when: a full run sends real emails to the configured subscriber list.

**Phase 5 — Tournament lifecycle.** Elimination detection: when a followed team is eliminated, switch to a one-time wrap-up message, then suppress that section for future runs (player/general content continues). Handle the structural shift from group standings to knockout bracket without redeploying. Done when: a simulated elimination produces the wrap-up email instead of a broken section.

**Phase 6 — Scheduling + deploy.** Cron or systemd timer triggering the full pipeline daily, deployed to the OCI VM. Done when: a real scheduled run completes unattended.

Build in this order. Don't start Phase 3 before Phase 1 and 2 work, there's nothing to compose without real bundled data.

---

## Open Items to Resolve During Build (not blockers, just flagged)
- Exact wrap-up message copy/tone for an eliminated team (write this when Phase 5 starts, not now).
- Whether Flash or a Flash-Lite variant ends up being the better Gemini choice in practice, decide once real rate-limit behavior is observed in Phase 3.
