# World Cup 2026 Daily Newsletter

**Your team. One email. Every morning.**

The World Cup only comes around every four years. Don't miss a moment of it — wake up each day to a personalised recap of everything that happened, written just for you.

---

## What You Get

Every morning of the tournament, one email built around the team you care about.

**Yesterday's result.** Full match recap with the scoreline and what it means for your team's campaign.

**Where your team stands.** Live group standings with goal difference, points, and what they need to advance.

**What the media is saying.** A second read on the day — what journalists and pundits are actually writing about your team right now.

**What's coming next.** Fixture preview with kickoff time in your local timezone.

If your team gets knocked out, you'll receive one final send-off email — then your newsletter stops quietly.

---

## Sign Up

Visit **[http://152.67.100.118](http://152.67.100.118)**, pick your team and timezone, and you're done.

No accounts. No app. Just your inbox.

---

## How It Works

Each morning, live match data, standings, and recent media coverage are pulled from multiple sources. An AI then writes a fresh, natural-sounding email — not a stats dump, but something worth reading over coffee.

---

## Self-Hosting

**Prerequisites:** Java 21+, Python 3.11+, Maven, an SMTP account

**1. Clone and configure**

```bash
git clone https://github.com/MujiDipto/fifa-wc-newsletter.git
cd fifa-wc-newsletter
cp .env.example .env
```

Fill in `.env`:

```env
FOOTBALL_DATA_API_TOKEN=   # free at football-data.org
GROQ_API_KEY=              # free at console.groq.com
GUARDIAN_API_KEY=          # free at open-platform.theguardian.com
SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```

**2. Start the AI composer**

```bash
cd python-composer
python3.11 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --port 8000
```

**3. Run the pipeline**

```bash
mvn package -DskipTests
java -jar target/worldcup-newsletter-1.0-SNAPSHOT.jar
```

Schedule step 3 daily with cron and you're done.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Pipeline | Java 21, Maven |
| AI composition | Python 3.11, FastAPI, Llama 3.3 70B via Groq |
| Data sources | football-data.org, ESPN, TheSportsDB, The Guardian |
| Storage | SQLite |
| Email delivery | Jakarta Mail, Gmail SMTP |
| Hosting | Oracle Cloud (Always Free) |

---

## License

MIT
