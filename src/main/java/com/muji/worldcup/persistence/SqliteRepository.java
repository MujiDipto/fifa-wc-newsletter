package com.muji.worldcup.persistence;

import com.muji.worldcup.ingestion.TheSportsDbClient;
import com.muji.worldcup.model.GroupStanding;
import com.muji.worldcup.model.Match;
import com.muji.worldcup.model.NewsItem;
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

    public List<GroupStanding> findStandingsByGroup(String groupName) throws SQLException {
        String sql = """
            SELECT group_name, team_name, played, won, drawn, lost,
                   goal_difference, points, position
            FROM group_standings
            WHERE group_name = ?
            ORDER BY position ASC
            """;
        List<GroupStanding> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupName);
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
                last_match_summary, position, nationality, bio, source, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(name, team) DO UPDATE SET
                goals              = excluded.goals,
                assists            = excluded.assists,
                appearances        = excluded.appearances,
                last_match_summary = COALESCE(excluded.last_match_summary, players.last_match_summary),
                position           = COALESCE(excluded.position, players.position),
                nationality        = COALESCE(excluded.nationality, players.nationality),
                bio                = COALESCE(excluded.bio, players.bio),
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
                ps.setString(7, p.position());
                ps.setString(8, p.nationality());
                ps.setString(9, p.bio());
                ps.setString(10, p.bio() != null ? "thesportsdb" : "football-data.org");
                ps.setString(11, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            log.info("Upserted {} player row(s)", counts.length);
        }
    }

    public void upsertPlayerBio(TheSportsDbClient.PlayerBio bio) throws SQLException {
        // Update by name only — TheSportsDB returns club team, not national team, so we
        // must not use team as part of the match key or we'd create duplicate rows.
        String sql = """
            UPDATE players SET
                position    = ?,
                nationality = ?,
                bio         = ?,
                fetched_at  = ?
            WHERE name = ?
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bio.position());
            ps.setString(2, bio.nationality());
            ps.setString(3, bio.description());
            ps.setString(4, Instant.now().toString());
            ps.setString(5, bio.name());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                log.debug("No existing player row for '{}' to attach bio to — skipping", bio.name());
            }
        }
    }

    // --- Subscribers ---

    public void upsertSubscriber(com.muji.worldcup.model.Subscriber s) throws SQLException {
        String sql = """
            INSERT INTO subscribers (email, followed_team, timezone, created_at, active)
            VALUES (?, ?, ?, ?, 1)
            ON CONFLICT(email) DO UPDATE SET
                followed_team = excluded.followed_team,
                timezone      = excluded.timezone,
                active        = 1
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, s.email());
            ps.setString(2, s.followedTeam());
            ps.setString(3, s.timezone());
            ps.setString(4, java.time.Instant.now().toString());
            ps.executeUpdate();
        }
    }

    public List<com.muji.worldcup.model.Subscriber> getActiveSubscribers() throws SQLException {
        String sql = """
            SELECT email, followed_team, timezone
            FROM subscribers
            WHERE active = 1
            ORDER BY created_at ASC
            """;
        List<com.muji.worldcup.model.Subscriber> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.muji.worldcup.model.Subscriber(
                            rs.getString("email"),
                            rs.getString("followed_team"),
                            rs.getString("timezone")
                    ));
                }
            }
        }
        return results;
    }

    public List<String> getDistinctTeams() throws SQLException {
        String sql = "SELECT DISTINCT team_name FROM group_standings ORDER BY team_name ASC";
        List<String> teams = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) teams.add(rs.getString("team_name"));
            }
        }
        return teams;
    }

    // --- Elimination tracking ---

    public boolean wasEliminationNotified(String team) throws SQLException {
        String sql = "SELECT 1 FROM team_eliminations WHERE team = ?";
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void markEliminationNotified(String team) throws SQLException {
        String sql = """
            INSERT INTO team_eliminations (team, notified_at)
            VALUES (?, datetime('now'))
            ON CONFLICT(team) DO NOTHING
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, team);
            ps.executeUpdate();
            log.info("Marked elimination notified for {}", team);
        }
    }

    public boolean hasPlayedMatches(String team) throws SQLException {
        String sql = """
            SELECT 1 FROM matches
            WHERE (home_team = ? OR away_team = ?) AND status = 'FINISHED'
            LIMIT 1
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, team);
            ps.setString(2, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean hasUpcomingMatches(String team) throws SQLException {
        String sql = """
            SELECT 1 FROM matches
            WHERE (home_team = ? OR away_team = ?)
              AND (status = 'SCHEDULED' OR status = 'TIMED')
            LIMIT 1
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, team);
            ps.setString(2, team);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --- News ---

    public void upsertNews(List<NewsItem> items) throws SQLException {
        String sql = """
            INSERT INTO news (id, headline, description, published_at, related_team, fetched_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                headline     = excluded.headline,
                description  = excluded.description,
                fetched_at   = excluded.fetched_at
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            String now = Instant.now().toString();
            for (NewsItem item : items) {
                ps.setString(1, item.id());
                ps.setString(2, item.headline());
                ps.setString(3, item.description());
                ps.setString(4, item.publishedAt() != null ? item.publishedAt().toString() : null);
                ps.setString(5, item.relatedTeam());
                ps.setString(6, now);
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            log.info("Upserted {} news item(s)", counts.length);
        }
    }

    public List<NewsItem> findNewsByTeam(String teamName) throws SQLException {
        String sql = """
            SELECT id, headline, description, published_at, related_team
            FROM news
            WHERE related_team = ?
            ORDER BY published_at DESC
            LIMIT 5
            """;
        List<NewsItem> results = new ArrayList<>();
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, teamName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pub = rs.getString("published_at");
                    results.add(new NewsItem(
                            rs.getString("id"),
                            rs.getString("headline"),
                            rs.getString("description"),
                            pub != null ? Instant.parse(pub) : null,
                            rs.getString("related_team")
                    ));
                }
            }
        }
        return results;
    }

    public Player findPlayer(String name) throws SQLException {
        String sql = """
            SELECT name, team, goals, assists, appearances, last_match_summary,
                   position, nationality, bio
            FROM players
            WHERE LOWER(name) = LOWER(?)
               OR LOWER(name) LIKE LOWER('%' || ? || '%')
            ORDER BY goals DESC
            LIMIT 1
            """;
        try (Connection conn = db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Player(
                            rs.getString("name"),
                            rs.getString("team"),
                            rs.getInt("goals"),
                            rs.getInt("assists"),
                            rs.getInt("appearances"),
                            rs.getString("last_match_summary"),
                            rs.getString("position"),
                            rs.getString("nationality"),
                            rs.getString("bio")
                    );
                }
            }
        }
        return null;
    }
}
