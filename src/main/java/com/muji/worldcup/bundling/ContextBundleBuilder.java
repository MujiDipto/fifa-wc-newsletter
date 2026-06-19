package com.muji.worldcup.bundling;

import com.muji.worldcup.model.*;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ContextBundleBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContextBundleBuilder.class);
    private static DateTimeFormatter timeFmt(ZoneId zone) {
        return DateTimeFormatter.ofPattern("HH:mm z").withZone(zone);
    }

    private static DateTimeFormatter dateFmt(ZoneId zone) {
        return DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone);
    }

    private static ZoneId zoneFor(Subscriber subscriber) {
        try {
            return ZoneId.of(subscriber.timezone());
        } catch (Exception e) {
            return ZoneId.of("UTC");
        }
    }

    private final SqliteRepository repository;

    public ContextBundleBuilder(SqliteRepository repository) {
        this.repository = repository;
    }

    public ContextBundle build(Subscriber subscriber) throws SQLException {
        String matchDayRecap = buildMatchDayRecap(subscriber.followedTeam());
        String teamUpdate    = buildTeamUpdate(subscriber.followedTeam());
        ZoneId zone          = zoneFor(subscriber);
        Match nextMatch      = findNextMatch(subscriber.followedTeam());
        Match lastResult     = findLastResult(subscriber.followedTeam());
        String nextPreview   = formatNextMatch(subscriber.followedTeam(), nextMatch, zone);
        List<GroupStanding> groupTable = buildGroupTable(subscriber.followedTeam());

        ContextBundle.EliminationStatus eliminationStatus =
                detectEliminationStatus(subscriber.followedTeam());

        log.info("Built bundle for {} (elimination={})", subscriber.email(), eliminationStatus);
        return new ContextBundle(
                subscriber,
                matchDayRecap,
                teamUpdate,
                nextPreview,
                eliminationStatus,
                groupTable,
                nextMatch,
                lastResult
        );
    }

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

    private Match findLastResult(String teamName) throws SQLException {
        if (teamName == null) return null;
        return repository.findMatchesByTeam(teamName).stream()
                .filter(m -> "FINISHED".equals(m.status()))
                .findFirst()  // findMatchesByTeam orders by kickoff DESC
                .orElse(null);
    }

    private Match findNextMatch(String teamName) throws SQLException {
        if (teamName == null) return null;

        return repository.findUpcomingMatches().stream()
                .filter(m -> teamName.equals(m.homeTeam()) || teamName.equals(m.awayTeam()))
                .findFirst()
                .orElse(null);
    }

    private String formatNextMatch(String teamName, Match next, ZoneId zone) {
        if (teamName == null) return null;
        if (next == null) return "No upcoming match scheduled for " + teamName + ".";

        String opponent = teamName.equals(next.homeTeam()) ? next.awayTeam() : next.homeTeam();
        String venue    = teamName.equals(next.homeTeam()) ? "vs" : "at";

        return String.format("%s %s %s — %s, %s",
                teamName, venue, opponent,
                dateFmt(zone).format(next.kickoffTime()),
                timeFmt(zone).format(next.kickoffTime()));
    }

    private List<GroupStanding> buildGroupTable(String teamName) throws SQLException {
        if (teamName == null) return List.of();

        List<GroupStanding> teamStandings = repository.findStandingsByTeam(teamName);
        if (teamStandings.isEmpty()) return List.of();

        String groupName = teamStandings.get(0).group();
        return repository.findStandingsByGroup(groupName);
    }

    private ContextBundle.EliminationStatus detectEliminationStatus(String teamName) throws SQLException {
        if (teamName == null) return ContextBundle.EliminationStatus.ACTIVE;
        // A team is only considered eliminated once they've played matches but have none left scheduled.
        if (!repository.hasPlayedMatches(teamName)) return ContextBundle.EliminationStatus.ACTIVE;
        if (repository.hasUpcomingMatches(teamName)) return ContextBundle.EliminationStatus.ACTIVE;
        // No upcoming matches — eliminated. Check if we've already sent the send-off.
        if (repository.wasEliminationNotified(teamName)) return ContextBundle.EliminationStatus.ALREADY_HANDLED;
        repository.markEliminationNotified(teamName);
        return ContextBundle.EliminationStatus.JUST_ELIMINATED;
    }

    private String formatMatchResult(Match m) {
        if (m.homeScore() != null && m.awayScore() != null) {
            return String.format("%s %d–%d %s",
                    m.homeTeam(), m.homeScore(), m.awayScore(), m.awayTeam());
        }
        return String.format("%s vs %s (score unavailable)", m.homeTeam(), m.awayTeam());
    }
}
