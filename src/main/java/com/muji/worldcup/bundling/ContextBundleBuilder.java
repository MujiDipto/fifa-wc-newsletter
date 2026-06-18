package com.muji.worldcup.bundling;

import com.muji.worldcup.model.*;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Assembles a ContextBundle for one subscriber by pulling all relevant data
 * from SQLite and formatting it into text sections for the LLM composer.
 *
 * One bundle is built per subscriber; the underlying data queries are shared
 * across subscribers following the same team/player (SQLite reads are cheap).
 */
public class ContextBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBundleBuilder.class);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.of("UTC"));

    private final SqliteRepository repository;

    public ContextBundleBuilder(SqliteRepository repository) {
        this.repository = repository;
    }

    public ContextBundle build(Subscriber subscriber) throws SQLException {
        String matchDayRecap = buildMatchDayRecap(subscriber.followedTeam());
        String teamUpdate    = buildTeamUpdate(subscriber.followedTeam());
        String playerUpdate  = buildPlayerUpdate(subscriber.followedPlayer());
        String nextPreview   = buildNextMatchPreview(subscriber.followedTeam());

        log.info("Built bundle for {}", subscriber.email());
        return new ContextBundle(
                subscriber,
                matchDayRecap,
                teamUpdate,
                playerUpdate,
                nextPreview,
                ContextBundle.EliminationStatus.ACTIVE
        );
    }

    // --- Section builders ---

    private String buildMatchDayRecap(String teamName) throws SQLException {
        if (teamName == null) return null;

        List<Match> matches = repository.findMatchesByTeam(teamName);
        List<Match> finished = matches.stream()
                .filter(m -> "FINISHED".equals(m.status()))
                .toList();

        if (finished.isEmpty()) {
            return teamName + " have not played a match yet today.";
        }

        StringBuilder sb = new StringBuilder();
        for (Match m : finished) {
            sb.append(formatMatchResult(m)).append("\n");
        }
        return sb.toString().strip();
    }

    private String buildTeamUpdate(String teamName) throws SQLException {
        if (teamName == null) return null;

        List<GroupStanding> standings = repository.findStandingsByTeam(teamName);
        if (standings.isEmpty()) {
            return "No standing data available for " + teamName + " yet.";
        }

        GroupStanding s = standings.get(0);
        return String.format(
                "%s — %s | P%d W%d D%d L%d | GD %+d | %d pts (position %d)",
                s.teamName(), s.group(),
                s.played(), s.won(), s.drawn(), s.lost(),
                s.goalDifference(), s.points(), s.position()
        );
    }

    private String buildPlayerUpdate(String playerName) throws SQLException {
        if (playerName == null) return null;

        Player player = repository.findPlayer(playerName);
        if (player == null) {
            return "No stats found for " + playerName + " yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(player.name())
          .append(" (").append(player.team()).append(")");

        if (player.position() != null)    sb.append(" — ").append(player.position());
        if (player.nationality() != null) sb.append(", ").append(player.nationality());

        sb.append("\n");
        sb.append(String.format("Goals: %d | Assists: %d | Appearances: %d",
                player.goals(), player.assists(), player.appearances()));

        if (player.lastMatchSummary() != null) {
            sb.append("\nLast match: ").append(player.lastMatchSummary());
        }
        if (player.bio() != null && !player.bio().isBlank()) {
            // Truncate long bios — the LLM gets the flavour without flooding the context
            String bio = player.bio().length() > 300
                    ? player.bio().substring(0, 300) + "…"
                    : player.bio();
            sb.append("\nBackground: ").append(bio);
        }

        return sb.toString();
    }

    private String buildNextMatchPreview(String teamName) throws SQLException {
        if (teamName == null) return null;

        List<Match> upcoming = repository.findUpcomingMatches().stream()
                .filter(m -> teamName.equals(m.homeTeam()) || teamName.equals(m.awayTeam()))
                .toList();

        if (upcoming.isEmpty()) {
            return "No upcoming matches scheduled for " + teamName + ".";
        }

        Match next = upcoming.get(0);
        String opponent = teamName.equals(next.homeTeam()) ? next.awayTeam() : next.homeTeam();
        String venue    = teamName.equals(next.homeTeam()) ? "vs" : "at";

        return String.format("%s %s %s — %s, %s",
                teamName, venue, opponent,
                DATE_FMT.format(next.kickoffTime()),
                TIME_FMT.format(next.kickoffTime()));
    }

    private String formatMatchResult(Match m) {
        if (m.homeScore() != null && m.awayScore() != null) {
            return String.format("%s %d–%d %s",
                    m.homeTeam(), m.homeScore(), m.awayScore(), m.awayTeam());
        }
        return String.format("%s vs %s (score unavailable)", m.homeTeam(), m.awayTeam());
    }
}
