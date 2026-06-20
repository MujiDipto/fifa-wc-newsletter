import logging
import os
import sqlite3
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from typing import Optional

import gemini_client

# Load .env from the repo root (one level up from python-composer/)
ROOT = Path(__file__).parent.parent
load_dotenv(dotenv_path=ROOT / ".env")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

DB_PATH = ROOT / "worldcup.db"


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("WC Newsletter service starting — composer: llama-3.3-70b-versatile (Groq)")
    yield
    log.info("WC Newsletter service shutting down")


app = FastAPI(title="WC Newsletter", lifespan=lifespan)


# --- Composer endpoints ---

class ComposeRequest(BaseModel):
    model_config = {"extra": "allow"}


class ComposeResponse(BaseModel):
    subject: str
    body: str


@app.post("/compose", response_model=ComposeResponse)
async def compose(request: ComposeRequest):
    bundle = request.model_dump()
    log.info("Composing email for %s", bundle.get("subscriber", {}).get("email", "unknown"))
    try:
        result = gemini_client.compose_newsletter(bundle)
        log.info("Composed: subject=%r", result["subject"])
        return ComposeResponse(**result)
    except Exception as e:
        err = str(e)
        if "429" in err or "quota" in err.lower() or "rate" in err.lower():
            log.warning("LLM rate limit hit: %s", err)
            raise HTTPException(status_code=429, detail="LLM rate limit — retry later")
        log.error("Composition failed: %s", err)
        raise HTTPException(status_code=500, detail=f"Composition failed: {err}")


# --- Signup / web endpoints ---

class SubscribeRequest(BaseModel):
    email: str
    followed_team: Optional[str] = None
    timezone: str = "UTC"


@app.post("/subscribe")
async def subscribe(req: SubscribeRequest):
    if not req.email or "@" not in req.email:
        raise HTTPException(status_code=400, detail="Invalid email address")
    try:
        with get_db() as conn:
            conn.execute("""
                INSERT INTO subscribers (email, followed_team, timezone, created_at, active)
                VALUES (?, ?, ?, datetime('now'), 1)
                ON CONFLICT(email) DO UPDATE SET
                    followed_team = excluded.followed_team,
                    timezone      = excluded.timezone,
                    active        = 1
            """, (req.email, req.followed_team, req.timezone))
        log.info("Subscriber upserted: %s (team=%s)", req.email, req.followed_team)
        return {"status": "subscribed", "email": req.email}
    except Exception as e:
        log.error("Subscribe failed: %s", e)
        raise HTTPException(status_code=500, detail="Failed to save subscription")


@app.get("/unsubscribe", response_class=HTMLResponse)
async def unsubscribe(email: str = Query(...)):
    try:
        with get_db() as conn:
            conn.execute("UPDATE subscribers SET active = 0 WHERE email = ?", (email,))
        log.info("Unsubscribed: %s", email)
        return HTMLResponse(content=f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Unsubscribed</title>
<style>body{{font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#f5f5f5}}
.box{{background:#fff;padding:40px;border-radius:8px;text-align:center;max-width:400px}}
h2{{margin:0 0 12px}}p{{color:#666;margin:0}}</style></head>
<body><div class="box"><h2>Unsubscribed</h2><p>{email} has been removed from the newsletter.</p></div></body></html>""")
    except Exception as e:
        log.error("Unsubscribe failed: %s", e)
        raise HTTPException(status_code=500, detail="Failed to unsubscribe")


@app.get("/api/teams")
async def get_teams():
    try:
        with get_db() as conn:
            rows = conn.execute(
                "SELECT DISTINCT team_name FROM group_standings ORDER BY team_name ASC"
            ).fetchall()
        teams = [r["team_name"] for r in rows]
        return {"teams": teams}
    except Exception:
        return {"teams": []}


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/", response_class=HTMLResponse)
async def signup_page():
    html_path = Path(__file__).parent / "static" / "index.html"
    return HTMLResponse(content=html_path.read_text())


# Serve static assets
static_dir = Path(__file__).parent / "static"
static_dir.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=str(static_dir)), name="static")
