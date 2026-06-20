"""Assembles the HTML email from Groq's text sections + structured bundle data."""

from prompts import format_kickoff

_TEAM_FLAGS = {
    "Argentina": "🇦🇷", "Australia": "🇦🇺", "Austria": "🇦🇹", "Belgium": "🇧🇪",
    "Bolivia": "🇧🇴", "Brazil": "🇧🇷", "Canada": "🇨🇦", "Chile": "🇨🇱",
    "Colombia": "🇨🇴", "Costa Rica": "🇨🇷", "Croatia": "🇭🇷", "Czechia": "🇨🇿",
    "DR Congo": "🇨🇩", "Congo DR": "🇨🇩", "Ecuador": "🇪🇨", "Egypt": "🇪🇬",
    "England": "🏴󠁧󠁢󠁥󠁮󠁧󁿢", "France": "🇫🇷", "Germany": "🇩🇪", "Ghana": "🇬🇭",
    "Honduras": "🇭🇳", "Hungary": "🇭🇺", "Indonesia": "🇮🇩", "Iraq": "🇮🇶",
    "Japan": "🇯🇵", "Kenya": "🇰🇪", "Mali": "🇲🇱", "Mexico": "🇲🇽",
    "Morocco": "🇲🇦", "Netherlands": "🇳🇱", "New Zealand": "🇳🇿", "Nigeria": "🇳🇬",
    "Norway": "🇳🇴", "Panama": "🇵🇦", "Paraguay": "🇵🇾", "Peru": "🇵🇪",
    "Portugal": "🇵🇹", "Qatar": "🇶🇦", "Saudi Arabia": "🇸🇦", "Senegal": "🇸🇳",
    "Serbia": "🇷🇸", "Slovenia": "🇸🇮", "South Korea": "🇰🇷", "Spain": "🇪🇸",
    "Switzerland": "🇨🇭", "Tanzania": "🇹🇿", "Turkey": "🇹🇷",
    "United States": "🇺🇸", "Uruguay": "🇺🇾", "Venezuela": "🇻🇪",
    "Algeria": "🇩🇿", "Bosnia-Herzegovina": "🇧🇦", "Cape Verde Islands": "🇨🇻",
    "Curaçao": "🇨🇼", "Cuba": "🇨🇺", "Jamaica": "🇯🇲", "Sweden": "🇸🇪",
    "Ukraine": "🇺🇦", "Zimbabwe": "🇿🇼",
}


def build_html(bundle: dict, sections: dict, base_url: str = "") -> str:
    if not base_url:
        import os
        base_url = os.getenv("BASE_URL", "http://localhost:8000")
    subscriber  = bundle.get("subscriber", {})
    email       = subscriber.get("email", "")
    team        = subscriber.get("followedTeam")
    timezone    = subscriber.get("timezone", "UTC")
    group_table = bundle.get("groupTable", [])
    next_match  = bundle.get("nextMatch")

    opening   = sections.get("opening", "")
    analysis  = sections.get("analysis", "")
    next_text = sections.get("next_match", "")

    # Header: "🇫🇷 France" or fallback
    flag = _TEAM_FLAGS.get(team, "⚽")
    header_label = f"{flag} {team}" if team else "World Cup 2026"

    # Standings table HTML
    standings_html = _build_standings_table(group_table, team)

    # Next fixture HTML
    fixture_html = _build_fixture_block(next_match, team, next_text, timezone)

    # Analysis paragraphs
    analysis_html = "".join(
        f"<p style='margin:0 0 14px 0'>{p.strip()}</p>"
        for p in analysis.split("\n\n") if p.strip()
    )

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
</head>
<body style="margin:0;padding:0;background:#f5f5f5;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif">
<table width="100%" cellpadding="0" cellspacing="0" style="background:#f5f5f5;padding:32px 16px">
<tr><td align="center">
<table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%;background:#ffffff;border-radius:8px;overflow:hidden">

  <!-- Header bar -->
  <tr>
    <td style="background:#111;padding:24px 32px">
      <div style="color:#888;font-size:11px;letter-spacing:2px;text-transform:uppercase;margin-bottom:6px">World Cup 2026 · Daily</div>
      <div style="color:#ffffff;font-size:22px;font-weight:600">{header_label}</div>
    </td>
  </tr>

  <!-- Opening statement -->
  <tr>
    <td style="padding:28px 32px 0 32px">
      <p style="margin:0;font-size:17px;line-height:1.5;color:#111;font-weight:500">{opening}</p>
    </td>
  </tr>

  <!-- Divider -->
  <tr><td style="padding:20px 32px"><hr style="border:none;border-top:1px solid #eee;margin:0"></td></tr>

  <!-- Analysis -->
  <tr>
    <td style="padding:0 32px">
      <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#888;margin-bottom:14px">Analysis</div>
      <div style="font-size:15px;line-height:1.7;color:#333">
        {analysis_html}
      </div>
    </td>
  </tr>

  {standings_html}

  {fixture_html}

  <!-- Footer -->
  <tr>
    <td style="padding:28px 32px;border-top:1px solid #eee;margin-top:8px">
      <p style="margin:0;font-size:12px;color:#aaa">World Cup Desk &nbsp;&middot;&nbsp; &#x270D;&#xFE0F; Muji (&amp; Claude)</p>
      <p style="margin:8px 0 0 0;font-size:11px;color:#ccc"><a href="{base_url}/unsubscribe?email={email}" style="color:#ccc">Unsubscribe</a></p>
    </td>
  </tr>

</table>
</td></tr>
</table>
</body>
</html>"""


def _build_standings_table(group_table: list, followed_team: str) -> str:
    if not group_table:
        return ""

    # Get group letter from first row if available — we don't store it separately
    # Use the bundle's teamUpdate which has "Group X" in it; fall back gracefully
    group_label = "Group Standings"

    rows_html = ""
    for row in group_table:
        team      = row.get("team", "")
        is_followed = team == followed_team
        bg = "#f8f8f8" if is_followed else "#ffffff"
        weight = "600" if is_followed else "400"
        gd = row.get("goalDifference", 0)
        gd_str = f"+{gd}" if gd > 0 else str(gd)
        rows_html += f"""
        <tr style="background:{bg}">
          <td style="padding:9px 12px;color:#888;font-size:13px;width:28px">{row.get('position')}</td>
          <td style="padding:9px 12px;font-size:14px;font-weight:{weight};color:#111">{team}</td>
          <td style="padding:9px 12px;font-size:13px;color:#555;text-align:center">{row.get('played')}</td>
          <td style="padding:9px 12px;font-size:13px;color:#555;text-align:center">{row.get('won')}</td>
          <td style="padding:9px 12px;font-size:13px;color:#555;text-align:center">{row.get('drawn')}</td>
          <td style="padding:9px 12px;font-size:13px;color:#555;text-align:center">{row.get('lost')}</td>
          <td style="padding:9px 12px;font-size:13px;color:#555;text-align:center">{gd_str}</td>
          <td style="padding:9px 12px;font-size:14px;font-weight:600;color:#111;text-align:center">{row.get('points')}</td>
        </tr>"""

    return f"""
  <tr><td style="padding:24px 32px 0 32px">
    <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#888;margin-bottom:14px">{group_label}</div>
    <table width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;border:1px solid #eee;border-radius:6px;overflow:hidden">
      <tr style="background:#fafafa;border-bottom:1px solid #eee">
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:left">#</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:left">Team</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">P</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">W</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">D</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">L</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">GD</th>
        <th style="padding:8px 12px;font-size:11px;color:#888;font-weight:500;text-align:center">Pts</th>
      </tr>
      {rows_html}
    </table>
  </td></tr>"""


def _build_fixture_block(next_match: dict, followed_team: str, next_text: str, timezone: str = "UTC") -> str:
    if not next_match:
        return ""

    home  = next_match.get("homeTeam", "")
    away  = next_match.get("awayTeam", "")
    kickoff_raw = next_match.get("kickoffTime", "")
    kickoff = format_kickoff(kickoff_raw, timezone) if kickoff_raw else "Date TBC"

    return f"""
  <tr><td style="padding:24px 32px 0 32px">
    <div style="font-size:11px;letter-spacing:2px;text-transform:uppercase;color:#888;margin-bottom:14px">Next Fixture</div>
    <div style="border:1px solid #eee;border-radius:6px;padding:16px 20px">
      <div style="font-size:16px;font-weight:600;color:#111;margin-bottom:6px">{home} <span style="color:#aaa;font-weight:400">vs</span> {away}</div>
      <div style="font-size:13px;color:#888;margin-bottom:12px">{kickoff}</div>
      <p style="margin:0;font-size:14px;line-height:1.6;color:#555">{next_text}</p>
    </div>
  </td></tr>"""
