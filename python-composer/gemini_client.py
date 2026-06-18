import os
from google import genai
from google.genai import types
from prompts import build_prompt, parse_response
from email_builder import build_html

_client = None


def _get_client() -> genai.Client:
    global _client
    if _client is None:
        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY environment variable is not set")
        _client = genai.Client(api_key=api_key)
    return _client


def compose_newsletter(bundle: dict) -> dict:
    prompt = build_prompt(bundle)

    response = _get_client().models.generate_content(
        model="gemini-2.5-flash",
        contents=prompt,
        config=types.GenerateContentConfig(
            temperature=0.7,
            max_output_tokens=8192,
            thinking_config=types.ThinkingConfig(thinking_budget=0),
        ),
    )

    raw_text = response.text
    sections = parse_response(raw_text)
    html_body = build_html(bundle, sections)

    subject = sections.get("subject", "").strip() or "Your World Cup 2026 Update"

    return {"subject": subject, "body": html_body}
