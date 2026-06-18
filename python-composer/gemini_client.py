import os
from groq import Groq
from prompts import build_prompt, parse_response
from email_builder import build_html

_client = None


def _get_client() -> Groq:
    global _client
    if _client is None:
        api_key = os.environ.get("GROQ_API_KEY")
        if not api_key:
            raise RuntimeError("GROQ_API_KEY environment variable is not set")
        _client = Groq(api_key=api_key)
    return _client


def compose_newsletter(bundle: dict) -> dict:
    prompt = build_prompt(bundle)

    response = _get_client().chat.completions.create(
        model="llama-3.3-70b-versatile",
        messages=[{"role": "user", "content": prompt}],
        temperature=0.7,
        max_tokens=1024,
    )

    raw_text = response.choices[0].message.content
    sections = parse_response(raw_text)
    html_body = build_html(bundle, sections)

    subject = sections.get("subject", "").strip() or "Your World Cup 2026 Update"

    return {"subject": subject, "body": html_body}
