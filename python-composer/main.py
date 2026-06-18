import logging
import os
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

import gemini_client

# Load .env from the repo root (one level up from python-composer/)
load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), "..", ".env"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("Python composer starting — model: gemini-2.5-flash")
    yield
    log.info("Python composer shutting down")


app = FastAPI(title="WC Newsletter Composer", lifespan=lifespan)


class ComposeRequest(BaseModel):
    # Mirrors ContextBundle fields sent from Java as JSON.
    # Extra fields are ignored so Java-side model changes don't break this.
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
        # Surface Gemini rate limits as 429 so the Java client knows to retry
        if "429" in err or "quota" in err.lower() or "rate" in err.lower():
            log.warning("Gemini rate limit hit: %s", err)
            raise HTTPException(status_code=429, detail="Gemini rate limit — retry later")
        log.error("Composition failed: %s", err)
        raise HTTPException(status_code=500, detail=f"Composition failed: {err}")


@app.get("/health")
async def health():
    return {"status": "ok"}
