# ⚽ World Cup 2026 Daily Newsletter

**Your team. Your player. One email. Every morning.**

The World Cup only comes around every four years. Don't miss a moment of it — wake up each day to a personalized recap of everything that happened, written just for you.

---

## What You Get

Every morning of the tournament, you receive a single email built around the team and player you care about most.

**Yesterday's results.** How did your team play? Full match recap with the key moments.

**Your player's performance.** Goals, assists, match rating, and a rundown of how they showed up on the pitch.

**Where your team stands.** Live group standings, goal difference, and what they need to advance.

**What's coming next.** Fixtures preview so you know exactly when to set your alarm.

**Tournament news.** The headlines worth knowing from across the competition.

If your team gets knocked out, you'll get one final send-off email — then your newsletter quietly shifts to cover the rest of the tournament for you.

---

## How It Works

You tell us who you support. We handle everything else.

Each day, the latest match data, standings, and player stats are pulled from multiple live sources. An AI then writes you a fresh, natural-sounding email — not a stats dump, but something worth reading over your morning coffee.

No accounts. No app to download. Just your inbox.

---

## Sign Up

Add yourself to [`config/subscribers.yaml`](config/subscribers.yaml):

```yaml
subscribers:
  - email: you@example.com
    team: Argentina
    player: Messi
```

That's it. You can follow a team, a player, or both.

---

## Self-Hosting

Want to run your own instance for friends and family? It takes about five minutes to set up.

**Prerequisites:** Java 21+, Python 3.11+, Maven, an SMTP account

**1. Clone and configure**

```bash
git clone https://github.com/MujiDipto/fifa-wc-newsletter.git
cd fifa-wc-newsletter
cp .env.example .env
```

Fill in `.env` with your credentials:

```env
FOOTBALL_DATA_API_TOKEN=   # free at football-data.org
GROQ_API_KEY=              # free at console.groq.com
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```

**2. Add your subscribers** to `config/subscribers.yaml`

**3. Start the AI composer**

```bash
cd python-composer
pip install -r requirements.txt
uvicorn main:app
```

**4. Run the pipeline**

```bash
mvn compile && mvn exec:java -Dexec.mainClass=com.muji.worldcup.Main
```

Schedule it daily and you're done.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Pipeline | Java 21, Maven |
| AI composition | Python 3.11, FastAPI, Llama 3.3 70B via Groq |
| Data | football-data.org, ESPN, TheSportsDB |
| Storage | SQLite |
| Email | Jakarta Mail |

---

## License

MIT
