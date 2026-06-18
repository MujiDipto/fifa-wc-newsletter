# ⚽ World Cup 2026 Newsletter

A daily personalized email newsletter for the 2026 FIFA World Cup. Each subscriber follows a team and/or a player and receives a tailored recap every morning — match results, standings, player stats, and a look-ahead to the next fixture — composed by an LLM.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Daily cron / systemd              │
└───────────────────────┬─────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│                  Java Pipeline (Main)                │
│                                                     │
│  ┌──────────────┐   ┌──────────────┐               │
│  │ football-    │   │     ESPN     │               │
│  │ data.org     │   │  (no auth)   │               │
│  │ (matches /   │   │ (news / match│               │
│  │  standings / │   │  lineups)    │               │
│  │  scorers)    │   └──────┬───────┘               │
│  └──────┬───────┘          │                       │
│         │         ┌────────┴──────┐                │
│         │         │ TheSportsDB   │                │
│         │         │ (player bios) │                │
│         │         └──────┬────────┘                │
│         └────────────────┘                         │
│                    │                               │
│            BoundedWorkerPool                       │
│         PrioritisedJobScheduler                    │
│              FanOutQueue                           │
│                    │                               │
│              SQLite (local)                        │
│                    │                               │
│          ContextBundleBuilder                      │
│                    │                               │
└────────────────────┼────────────────────────────────┘
                     │ HTTP (localhost)
                     ▼
┌─────────────────────────────────────────────────────┐
│           Python Composer (FastAPI)                 │
│              Gemini 2.5 Flash                       │
└────────────────────┬────────────────────────────────┘
                     │
                     ▼
              📧 Email (SMTP)
```

### Key design constraint
This project intentionally uses **hand-rolled concurrency primitives** — a bounded worker pool, a prioritized job scheduler, and a fan-out queue with dead-letter handling. These are explicit implementations, not wrappers around `ExecutorService` or reactive libraries, because applying them to real rate-limited external dependencies is the point.

---

## Data Sources

| Source | What it provides | Auth |
|---|---|---|
| [football-data.org](https://football-data.org) | Matches, group standings, top scorers | Free API token |
| [ESPN (unofficial)](https://site.api.espn.com) | News headlines, match lineups | None required |
| [TheSportsDB](https://thesportsdb.com) | Player bios, positions, nationalities | None required |

---

## Tech Stack

| Layer | Technology |
|---|---|
| Core pipeline | Java 21, Maven |
| HTTP | `java.net.http.HttpClient` (no extra library) |
| JSON | Jackson |
| Persistence | SQLite via `sqlite-jdbc`, plain JDBC |
| Email | Jakarta Mail |
| Logging | SLF4J + Logback |
| LLM composer | Python 3.11, FastAPI, `google-genai` (Gemini) |
| Tests | JUnit 5 |

---

## Project Structure

```
worldcup-newsletter/
├── config/
│   └── subscribers.yaml          # Subscriber list (edit this)
├── src/main/java/com/muji/worldcup/
│   ├── Main.java
│   ├── concurrency/
│   │   ├── BoundedWorkerPool.java
│   │   ├── PrioritisedJobScheduler.java
│   │   ├── FanOutQueue.java
│   │   ├── RateLimiter.java
│   │   └── RetryWithBackoff.java
│   ├── ingestion/
│   │   ├── FootballDataClient.java
│   │   ├── EspnClient.java
│   │   └── TheSportsDbClient.java
│   ├── model/                    # Plain Java records
│   ├── persistence/              # SQLite schema + repository
│   ├── bundling/                 # ContextBundleBuilder
│   ├── composer/                 # HTTP client → Python service
│   ├── delivery/                 # EmailSender
│   └── config/                   # EnvLoader, SubscriberConfigLoader
├── python-composer/
│   ├── main.py                   # FastAPI app
│   ├── gemini_client.py
│   └── prompts.py
└── .env                          # Secrets — never committed
```

---

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- `sqlite3` (for inspecting the DB)
- Python 3.11+ (for Phase 3 onwards)

### 1. Clone and configure

```bash
git clone https://github.com/MujiDipto/fifa-wc-newsletter.git
cd fifa-wc-newsletter
```

Copy the env template and fill in your credentials:

```bash
cp .env.example .env   # then edit .env
```

```env
FOOTBALL_DATA_API_TOKEN=   # free at football-data.org
GEMINI_API_KEY=            # free at aistudio.google.com
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```

### 2. Configure subscribers

Edit [`config/subscribers.yaml`](config/subscribers.yaml):

```yaml
subscribers:
  - email: you@example.com
    team: Argentina
    player: Messi
  - email: friend@example.com
    team: Spain
    player: null
```

### 3. Run

```bash
mvn compile && mvn exec:java -Dexec.mainClass=com.muji.worldcup.Main
```

### 4. Inspect the database

```bash
sqlite3 worldcup.db
```

```sql
SELECT home_team, away_team, status, kickoff_time FROM matches ORDER BY kickoff_time;
SELECT position, team_name, played, won, lost, points FROM group_standings WHERE group_name = 'Group J' ORDER BY position;
SELECT name, team, goals, position, nationality FROM players ORDER BY goals DESC;
SELECT headline, related_team FROM news ORDER BY published_at DESC;
```

---

## Running Tests

```bash
mvn test
```

46 tests across concurrency primitives, config loading, persistence, and bundle assembly. Each test gets an isolated SQLite database via JUnit 5 `@TempDir`.

---

## Secrets & Security

- All credentials live in `.env` at the repo root — **never committed** (`.gitignore` enforced)
- `worldcup.db` is gitignored — contains ingested data, not secrets, but kept local
- Two of the three data sources (ESPN, TheSportsDB) require no credentials at all
- No credentials are hardcoded anywhere in source — loaded at startup via `EnvLoader`

---

## Build Phases

| Phase | Status | Description |
|---|---|---|
| 0 — Scaffolding | ✅ Done | Maven project, env loading, subscriber config |
| 1 — Ingestion | ✅ Done | 3 data sources, SQLite persistence, rate limiting |
| 2 — Bundling | ✅ Done | Prioritized scheduling, ContextBundle assembly |
| 3 — LLM Composer | 🔲 Next | FastAPI + Gemini, retry on 429 |
| 4 — Delivery | 🔲 | Fan-out email send, dead-letter log |
| 5 — Lifecycle | 🔲 | Elimination detection, knockout bracket |
| 6 — Deploy | 🔲 | Cron/systemd on OCI VM |

---

## License

MIT
