package com.muji.worldcup.persistence;

import com.muji.worldcup.model.GroupStanding;
import com.muji.worldcup.model.Match;
import com.muji.worldcup.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SqliteRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteRepository.class);

    private final Database db;

    public SqliteRepository(Database db) {
        this.db = db;
    }

    // --- Matches ---

    public void upsertMatches(List<Match> matches) throws SQLException {
        String sql = """
            INSERT INTO matches (id, home_team, away_team, home_score, away_score,
                status, group_name, kickoff_time, source, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                home_score   = excluded.home_score,
                away_score   = excluded.away_score,
                status       = excluded.status,
                fetched_at   = excluded.fetched_at
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (Match m : matches) {
                ps.setString(1, m.id());
                ps.setString(2, m.homeTeam());
                ps.setString(3, m.awayTeam());
                ps.setObject(4, m.homeScore());
                ps.setObject(5, m.awayScore());
                ps.setString(6, m.status());
                ps.setString(7, m.group());
                ps.setString(8, m.kickoffTime() != null ? m.kickoffTime().toString() : null);
                ps.setString(9, m.source());
                ps.setString(10, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            log.info("Upserted {} match row(s)", counts.length);
        }
    }

    public List<Match> findMatchesByTeam(String teamName) throws SQLException {
        String sql = """
            SELECT id, home_team, away_team, home_score, away_score,
                   status, group_name, kickoff_time, source
            FROM matches
            WHERE home_team = ? OR away_team = ?
            ORDER BY kickoff_time DESC
            """;
        List<Match> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamName);
            ps.setString(2, teamName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(matchFromRs(rs));
            }
        }
        return results;
    }

    public List<Match> findUpcomingMatches() throws SQLException {
        String sql = """
            SELECT id, home_team, away_team, home_score, away_score,
                   status, group_name, kickoff_time, source
            FROM matches
            WHERE status = 'SCHEDULED' OR status = 'TIMED'
            ORDER BY kickoff_time ASC
            LIMIT 20
            """;
        List<Match> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(matchFromRs(rs));
            }
        }
        return results;
    }

    private Match matchFromRs(ResultSet rs) throws SQLException {
        String kickoff = rs.getString("kickoff_time");
        return new Match(
                rs.getString("id"),
                rs.getString("home_team"),
                rs.getString("away_team"),
                (Integer) rs.getObject("home_score"),
                (Integer) rs.getObject("away_score"),
                rs.getString("status"),
                rs.getString("group_name"),
                kickoff != null ? Instant.parse(kickoff) : null,
                rs.getString("source")
        );
    }

    // --- Standings ---

    public void upsertStandings(List<GroupStanding> standings) throws SQLException {
        String sql = """
            INSERT INTO group_standings (group_name, team_name, played, won, drawn, lost,
                goal_difference, points, position, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(group_name, team_name) DO UPDATE SET
                played          = excluded.played,
                won             = excluded.won,
                drawn           = excluded.drawn,
                lost            = excluded.lost,
                goal_difference = excluded.goal_difference,
                points          = excluded.points,
                position        = excluded.position,
                fetched_at      = excluded.fetched_at
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (GroupStanding s : standings) {
                ps.setString(1, s.group());
                ps.setString(2, s.teamName());
                ps.setInt(3, s.played());
                ps.setInt(4, s.won());
                ps.setInt(5, s.drawn());
                ps.setInt(6, s.lost());
                ps.setInt(7, s.goalDifference());
                ps.setInt(8, s.points());
                ps.setInt(9, s.position());
                ps.setString(10, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            log.info("Upserted {} standing row(s)", counts.length);
        }
    }

    public List<GroupStanding> findStandingsByTeam(String teamName) throws SQLException {
        String sql = """
            SELECT group_name, team_name, played, won, drawn, lost,
                   goal_difference, points, position
            FROM group_standings
            WHERE team_name = ?
            """;
        List<GroupStanding> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new GroupStanding(
                            rs.getString("group_name"),
                            rs.getString("team_name"),
                            rs.getInt("played"),
                            rs.getInt("won"),
                            rs.getInt("drawn"),
                            rs.getInt("lost"),
                            rs.getInt("goal_difference"),
                            rs.getInt("points"),
                            rs.getInt("position")
                    ));
                }
            }
        }
        return results;
    }

    // --- Players ---

    public void upsertPlayers(List<Player> players) throws SQLException {
        String sql = """
            INSERT INTO players (name, team, goals, assists, appearances,
                last_match_summary, source, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name, team) DO UPDATE SET
                goals              = excluded.goals,
                assists            = excluded.assists,
                appearances        = excluded.appearances,
                last_match_summary = COALESCE(excluded.last_match_summary, players.last_match_summary),
                fetched_at         = excluded.fetched_at
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (Player p : players) {
                ps.setString(1, p.name());
                ps.setString(2, p.team());
                ps.setInt(3, p.goals());
                ps.setInt(4, p.assists());
                ps.setInt(5, p.appearances());
                ps.setString(6, p.lastMatchSummary());
                ps.setString(7, "football-data.org");
                ps.setString(8, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            log.info("Upserted {} player row(s)", counts.length);
        }
    }

    public Player findPlayer(String name) throws SQLException {
        String sql = """
            SELECT name, team, goals, assists, appearances, last_match_summary
            FROM players
            WHERE name = ?
            LIMIT 1
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Player(
                            rs.getString("name"),
                            rs.getString("team"),
                            rs.getInt("goals"),
                            rs.getInt("assists"),
                            rs.getInt("appearances"),
                            rs.getString("last_match_summary")
                    );
                }
            }
        }
        return null;
    }
}
