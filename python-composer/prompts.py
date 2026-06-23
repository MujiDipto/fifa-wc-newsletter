from datetime import datetime


def build_prompt(bundle: dict, coverage: list = None) -> str:
    subscriber    = bundle.get("subscriber", {})
    team          = subscriber.get("followedTeam")
    recap         = bundle.get("matchDayRecapText")
    team_update   = bundle.get("teamUpdate")
    next_preview  = bundle.get("nextMatchDayPreview")
    status        = bundle.get("eliminationStatus", "ACTIVE")
    is_knockout   = bundle.get("isKnockoutStage", False)
    stage_label   = bundle.get("stageLabel", "Group Stage")

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
    if next_preview:
        sections.append(f"NEXT FIXTURE:\n{next_preview}")

    if is_knockout and team_update:
        sections_with_label = [f"TOURNAMENT STAGE: {stage_label}"] + sections
    else:
        sections_with_label = sections

    context_block = "\n\n".join(sections_with_label) if sections_with_label else "No match data available."

    coverage_block = ""
    if coverage:
        lines = []
        for i, art in enumerate(coverage, 1):
            lines.append(f"{i}. {art['title']}")
            if art.get("summary"):
                lines.append(f"   {art['summary']}")
        coverage_block = "\n\nMEDIA COVERAGE (recent Guardian articles):\n" + "\n".join(lines)

    elimination_note = ""
    if status == "JUST_ELIMINATED":
        elimination_note = f"\nIMPORTANT: {team} has just been eliminated. Write a respectful send-off in the ANALYSIS section.\n"
    elif status == "ALREADY_HANDLED":
        elimination_note = f"\nNOTE: {team} is eliminated. Omit the ANALYSIS section entirely.\n"

    following_line = f"team: {team}" if team else "the World Cup"

    if is_knockout:
        analysis_instruction = f"""<Exactly two paragraphs, separated by a blank line.

Paragraph 1: The knockout stakes — what happened in the last match (if any), what winning or losing means at the {stage_label} stage, and where {team} stands in the bracket. Factual, analytical. Only use information from DATA above.

Paragraph 2: The conversation — draw on the MEDIA COVERAGE section to reflect what journalists and pundits are talking about. The articles may cover the broader World Cup rather than {team} specifically. Use them to situate {team} inside the wider tournament narrative: what themes are dominating coverage right now, what the football world is reacting to, what the mood of the tournament is. Pull in anything directly relevant to {team} where it exists. Make this paragraph feel alive and opinionated — like reading a quality sports column, not a summary.

No bullet points. No markdown. No headers within the section.>"""
        next_match_instruction = f"<One concise paragraph about the upcoming {stage_label} fixture. Name the opponent and what elimination at this stage would mean for both sides. If no fixture data is available write: No fixture data available.>"
    else:
        analysis_instruction = f"""<Exactly two paragraphs, separated by a blank line.

Paragraph 1: The football — what happened on the pitch, what the result means, where the team stands. Factual, analytical. Only use information from DATA above.

Paragraph 2: The conversation — draw on the MEDIA COVERAGE section to reflect what journalists and pundits are talking about. The articles may cover the broader World Cup rather than {team} specifically. Use them to situate {team} inside the wider tournament narrative: what themes are dominating coverage right now, what the football world is reacting to, what the mood of the tournament is. Pull in anything directly relevant to {team} where it exists. Make this paragraph feel alive and opinionated — like reading a quality sports column, not a summary.

No bullet points. No markdown. No headers within the section.>"""
        next_match_instruction = "<One concise paragraph about the upcoming fixture. Name the opponent, note their group position and what the match means for both sides. If no fixture data is available write: No fixture data available.>"

    return f"""You are writing a daily World Cup 2026 newsletter for a reader following {following_line}.

Tone: professional, analytical, journal-like. No filler phrases, no exclamation marks, no cheerleading. Write like a quality sports journalist — precise, warm where warranted, never hollow.

CRITICAL RULE: You must only state facts that appear in the DATA section below. Do not recall, infer, or invent any match results, scorelines, opponents, dates, or statistics from your training knowledge. If a piece of information is not in the DATA section, do not mention it. Stating a fabricated result would be a serious factual error.
{elimination_note}
--- DATA ---
{context_block}{coverage_block}
--- END DATA ---

Respond with exactly four labelled sections. Do not add any other text.

SUBJECT:
<A punchy email subject line, max 8 words. Specific — name a result, a player, a stakes moment. No generic phrases.>

OPENING:
<One sharp sentence (max 20 words) that captures the day's story for this subscriber. If no match was played, open on what the rest day means for the team's position or what to watch next.>

ANALYSIS:
{analysis_instruction}

NEXT_MATCH:
{next_match_instruction}
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


def format_kickoff(iso_string: str, timezone: str = "UTC") -> str:
    try:
        from zoneinfo import ZoneInfo
        dt = datetime.fromisoformat(iso_string.replace("Z", "+00:00"))
        dt_local = dt.astimezone(ZoneInfo(timezone))
        return dt_local.strftime("%A %-d %B, %H:%M %Z")
    except Exception:
        return iso_string
