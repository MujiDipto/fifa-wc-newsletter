import logging
import os
import urllib.parse
import urllib.request
import json

log = logging.getLogger(__name__)

_BASE = "https://content.guardianapis.com/search"


def fetch_team_coverage(team: str, max_articles: int = 5) -> list[dict]:
    """Return recent Guardian WC 2026 football articles for LLM to extract team-relevant content from."""
    api_key = os.getenv("GUARDIAN_API_KEY", "")
    if not api_key:
        log.warning("GUARDIAN_API_KEY not set — skipping media coverage fetch")
        return []

    # Two passes: team-specific first, then general WC news to pad out
    articles = _search(api_key, f'"{team}" "World Cup 2026"', "football", max_articles)
    if len(articles) < 3:
        general = _search(api_key, "World Cup 2026", "football", max_articles)
        seen = {a["title"] for a in articles}
        for a in general:
            if a["title"] not in seen:
                articles.append(a)
            if len(articles) >= max_articles:
                break

    log.info("Guardian: %d article(s) for %s", len(articles), team)
    return articles


def _search(api_key: str, q: str, section: str, page_size: int) -> list[dict]:
    query = urllib.parse.urlencode({
        "q": q,
        "section": section,
        "page-size": page_size,
        "order-by": "newest",
        "show-fields": "trailText",
        "api-key": api_key,
    })
    url = f"{_BASE}?{query}"

    try:
        with urllib.request.urlopen(url, timeout=8) as resp:
            data = json.loads(resp.read())
        results = data.get("response", {}).get("results", [])
        articles = []
        for r in results:
            title = r.get("webTitle", "").strip()
            trail = _strip_tags(r.get("fields", {}).get("trailText", "").strip())
            if title:
                articles.append({"title": title, "summary": trail})
        return articles
    except Exception as e:
        log.warning("Guardian search failed (q=%r): %s", q, e)
        return []


def _strip_tags(text: str) -> str:
    import re
    return re.sub(r"<[^>]+>", "", text).strip()
