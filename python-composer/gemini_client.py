import os
from google import genai
from google.genai import types
from prompts import build_prompt, parse_response

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
    """
    Takes a ContextBundle dict, calls Gemini, and returns
    {"subject": str, "body": str}.

    Raises RuntimeError on non-retryable failures.
    Raises google.genai.errors.ClientError with status 429 on rate limit —
    the caller (FastAPI) surfaces this as HTTP 429 so the Java client can retry.
    """
    prompt = build_prompt(bundle)

    response = _get_client().models.generate_content(
        model="gemini-2.5-flash",
        contents=prompt,
        config=types.GenerateContentConfig(
            temperature=0.8,
            max_output_tokens=8192,
            thinking_config=types.ThinkingConfig(thinking_budget=0),
        ),
    )

    raw_text = response.text
    return parse_response(raw_text)
