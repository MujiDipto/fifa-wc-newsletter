def build_prompt(bundle: dict) -> str:
    subscriber   = bundle.get("subscriber", {})
    team         = subscriber.get("followedTeam")
    player       = subscriber.get("followedPlayer")
    recap        = bundle.get("matchDayRecapText")
    team_update  = bundle.get("teamUpdate")
    player_update = bundle.get("playerUpdate")
    next_preview = bundle.get("nextMatchDayPreview")
    status       = bundle.get("eliminationStatus", "ACTIVE")

    sections = []

    if recap:
        sections.append(f"MATCH RECAP:\n{recap}")
    if team_update:
        sections.append(f"TEAM STANDING:\n{team_update}")
    if player_update:
        sections.append(f"PLAYER UPDATE:\n{player_update}")
    if next_preview:
        sections.append(f"NEXT MATCH:\n{next_preview}")

    context_block = "\n\n".join(sections) if sections else "No match data available today."

    elimination_note = ""
    if status == "JUST_ELIMINATED":
        elimination_note = (
            f"\nIMPORTANT: {team} has just been eliminated from the tournament. "
            "Write a warm, respectful send-off for the team in the team section. "
            "This should feel like a proper goodbye, not a dry stat summary.\n"
        )
    elif status == "ALREADY_HANDLED":
        elimination_note = (
            f"\nNOTE: {team} has already been eliminated. "
            "Do not include a team section — focus only on the player and general content.\n"
        )

    followed = []
    if team:
        followed.append(f"team: {team}")
    if player:
        followed.append(f"player: {player}")
    following_line = ", ".join(followed) if followed else "the World Cup generally"

    return f"""You are writing a daily World Cup 2026 newsletter email for a fan following {following_line}.

Write a personalized, engaging email based on the data below. The tone should be warm, conversational, and enthusiastic — like a knowledgeable friend who watched every game. Avoid dry stat recitation; weave the numbers into a narrative.
{elimination_note}
--- DATA ---
{context_block}
--- END DATA ---

Output format — respond with exactly two sections, nothing else:

SUBJECT: <a punchy, specific email subject line — no more than 10 words>

BODY:
<the full email body in plain text, 150–250 words. Use short paragraphs. No markdown, no bullet points, no HTML. Sign off as "Your World Cup Desk".>
"""


def parse_response(text: str) -> dict:
    """Extract subject and body from the model's raw text output."""
    subject = ""
    body = ""

    lines = text.strip().splitlines()
    body_lines = []
    in_body = False

    for line in lines:
        if line.startswith("SUBJECT:"):
            subject = line.removeprefix("SUBJECT:").strip()
        elif line.strip() == "BODY:":
            in_body = True
        elif in_body:
            body_lines.append(line)

    body = "\n".join(body_lines).strip()

    # Fallback: if parsing failed, use the whole response as the body
    if not subject:
        subject = "Your Daily World Cup Update"
    if not body:
        body = text.strip()

    return {"subject": subject, "body": body}
