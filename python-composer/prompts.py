from datetime import datetime, timezone


def build_prompt(bundle: dict) -> str:
    subscriber    = bundle.get("subscriber", {})
    team          = subscriber.get("followedTeam")
    player        = subscriber.get("followedPlayer")
    recap         = bundle.get("matchDayRecapText")
    team_update   = bundle.get("teamUpdate")
    player_update = bundle.get("playerUpdate")
    next_preview  = bundle.get("nextMatchDayPreview")
    status        = bundle.get("eliminationStatus", "ACTIVE")
    next_match    = bundle.get("nextMatch")

    last_result = bundle.get("lastResult")

    sections = []
    if recap:
        sections.append(f"TODAY'S RESULT:\n{recap}")
    if last_result and last_result.get("homeScore", -1) >= 0:
        hs = last_result["homeScore"]
        as_ = last_result["awayScore"]
        kickoff = format_kickoff(last_result.get("kickoffTime", ""))
        sections.append(
            f"MOST RECENT RESULT:\n"
            f"{last_result['homeTeam']} {hs}–{as_} {last_result['awayTeam']} ({kickoff})"
        )
    if team_update:
        sections.append(f"TEAM STANDING:\n{team_update}")
    if player_update:
        sections.append(f"PLAYER STATS:\n{player_update}")
    if next_preview:
        sections.append(f"NEXT FIXTURE:\n{next_preview}")

    context_block = "\n\n".join(sections) if sections else "No match data available."

    match_played = recap and "did not play" not in recap

    elimination_note = ""
    if status == "JUST_ELIMINATED":
        elimination_note = f"\nIMPORTANT: {team} has just been eliminated. Write a respectful send-off in the ANALYSIS section.\n"
    elif status == "ALREADY_HANDLED":
        elimination_note = f"\nNOTE: {team} is eliminated. Omit the ANALYSIS section entirely.\n"

    following = []
    if team:
        following.append(f"team: {team}")
    if player:
        following.append(f"player: {player}")
    following_line = ", ".join(following) if following else "the World Cup"

    return f"""You are writing a daily World Cup 2026 newsletter for a reader following {following_line}.

Tone: professional, analytical, journal-like. No filler phrases, no exclamation marks, no cheerleading. Write like a quality sports journalist — precise, warm where warranted, never hollow.

CRITICAL RULE: You must only state facts that appear in the DATA section below. Do not recall, infer, or invent any match results, scorelines, opponents, dates, or statistics from your training knowledge. If a piece of information is not in the DATA section, do not mention it. Stating a fabricated result would be a serious factual error.
{elimination_note}
--- DATA ---
{context_block}
--- END DATA ---

Respond with exactly four labelled sections. Do not add any other text.

SUBJECT:
<A punchy email subject line, max 8 words. Specific — name a result, a player, a stakes moment. No generic phrases.>

OPENING:
<One sharp sentence (max 20 words) that captures the day's story for this subscriber. If no match was played, open on what the rest day means for the team's position or what to watch next.>

ANALYSIS:
<Two or three tight paragraphs. If a match was played, analyse what happened and what it means. If no match, briefly situate the team in the tournament — form, what they need, who they face next — without padding it out. Draw on the player data if available. No bullet points. No markdown.>

NEXT_MATCH:
<One concise paragraph about the upcoming fixture. Name the opponent, note their group position and what the match means for both sides. If no fixture data is available write: No fixture data available.>
"""


def parse_response(text: str) -> dict:
    """Extract SUBJECT, OPENING, ANALYSIS, NEXT_MATCH sections from the model's output."""
    order = ["subject", "opening", "analysis", "next_match"]
    markers = {"SUBJECT:": "subject", "OPENING:": "opening", "ANALYSIS:": "analysis", "NEXT_MATCH:": "next_match"}
    sections = {k: "" for k in order}
    current = None
    buffer = []

    for line in text.strip().splitlines():
        # Strip markdown heading prefixes Llama sometimes adds (e.g. "## SUBJECT:")
        stripped = line.strip().lstrip('#').strip()
        matched = False
        for marker, key in markers.items():
            if stripped.startswith(marker):
                if current is not None:
                    sections[current] = "\n".join(buffer).strip()
                current = key
                buffer = []
                # Inline content after the label (e.g. "SUBJECT: text here")
                inline = stripped[len(marker):].strip()
                if inline:
                    buffer.append(inline)
                matched = True
                break
        if not matched and current is not None:
            buffer.append(line)

    if current is not None:
        sections[current] = "\n".join(buffer).strip()

    return sections


def format_kickoff(iso_string: str) -> str:
    try:
        dt = datetime.fromisoformat(iso_string.replace("Z", "+00:00"))
        return dt.strftime("%A %-d %B, %H:%M UTC")
    except Exception:
        return iso_string
