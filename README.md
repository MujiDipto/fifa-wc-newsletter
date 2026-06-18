# ⚽ World Cup 2026 Newsletter

A daily personalized email newsletter for the 2026 FIFA World Cup. Follow your team and your favorite player — every morning you get a match recap, live standings, player stats, and a preview of what's next, written by an AI.

---

## What it does

Each subscriber picks a **team** and a **player** to follow. Once a day the pipeline runs, pulls the latest World Cup data, and sends a personalized email that covers:

- **Match recap** — results from the previous day with scores
- **Team standing** — current group position, points, and goal difference
- **Player update** — goals, assists, appearances, and a short profile
- **Next match preview** — upcoming fixture with date and time
- **Latest news** — recent headlines about the followed team

---

## How it works

Data is pulled from three sources in parallel, stored locally, and assembled into a personalized context for each subscriber. An AI model then reads that context and writes a natural-language email — not a template, an actual composed newsletter.

```
  football-data.org       ESPN          TheSportsDB
  (matches, standings,  (news, match   (player bios,
   top scorers)          lineups)       positions)
        │                  │                │
        └──────────────────┴────────────────┘
                           │
                    Ingestion pipeline
                  (concurrent, rate-aware)
                           │
                       Local DB
                           │
                  Bundle per subscriber
                           │
                    AI Composer (Gemini)
                           │
                       📧 Email
```

**Concurrency** is a first-class concern — the pipeline fetches from all three sources simultaneously, respects each API's rate limits independently, prioritizes your subscribed teams and players over the rest of the field, and fans emails out to all subscribers in parallel. Transient failures (a source going down, a rate limit hit) are retried with backoff and never crash the whole run.

---

## Data sources

| Source | Provides | Cost |
|---|---|---|
| [football-data.org](https://football-data.org) | Match results, group standings, scorers | Free tier |
| [ESPN](https://espn.com) | News headlines, match player stats | No account needed |
| [TheSportsDB](https://thesportsdb.com) | Player bios, positions, nationalities | No account needed |

---

## Getting started

### Requirements

- Java 21+
- Maven 3.9+
- Python 3.11+ (for the AI composer)

### 1. Clone

```bash
git clone https://github.com/MujiDipto/fifa-wc-newsletter.git
cd fifa-wc-newsletter
```

### 2. Configure credentials

```bash
cp .env.example .env
```

Fill in your keys — all free, no credit card required:

```env
FOOTBALL_DATA_API_TOKEN=   # sign up at football-data.org
GEMINI_API_KEY=            # get one at aistudio.google.com
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```

### 3. Add your subscribers

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

### 4. Run

```bash
mvn compile && mvn exec:java -Dexec.mainClass=com.muji.worldcup.Main
```

---

## Project status

| Phase | Status |
|---|---|
| Data ingestion (3 sources) | ✅ Complete |
| Personalized bundle assembly | ✅ Complete |
| AI email composition (Gemini) | 🔲 In progress |
| Email delivery | 🔲 Upcoming |
| Elimination detection & lifecycle | 🔲 Upcoming |
| Scheduled daily runs | 🔲 Upcoming |

---

## License

MIT
