package com.muji.worldcup.persistence;

import com.muji.worldcup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteRepositoryTest {

    private SqliteRepository repo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        // TempDir is unique per test method — gives full isolation without in-memory quirks
        Database db = new Database(tempDir.resolve("test.db").toString());
        db.initSchema();
        repo = new SqliteRepository(db);
    }

    // --- Matches ---

    @Test
    void upsertAndFindMatchByTeam() throws SQLException {
        Match m = new Match("1", "Argentina", "France", 2, 1,
                "FINISHED", "Group J", Instant.parse("2026-06-18T18:00:00Z"), "football-data.org", null);
        repo.upsertMatches(List.of(m));

        List<Match> found = repo.findMatchesByTeam("Argentina");
        assertEquals(1, found.size());
        assertEquals("Argentina", found.get(0).homeTeam());
        assertEquals(2, found.get(0).homeScore());
    }

    @Test
    void upsertMatchIsIdempotent() throws SQLException {
        Match m = new Match("1", "Brazil", "Germany", 1, 0,
                "FINISHED", "Group A", Instant.now(), "football-data.org", null);
        repo.upsertMatches(List.of(m));
        repo.upsertMatches(List.of(m)); // second upsert

        assertEquals(1, repo.findMatchesByTeam("Brazil").size());
    }

    @Test
    void upsertUpdatesScoreOnConflict() throws SQLException {
        Match initial = new Match("42", "Spain", "Italy", null, null,
                "TIMED", "Group H", Instant.now(), "football-data.org", null);
        repo.upsertMatches(List.of(initial));

        Match updated = new Match("42", "Spain", "Italy", 3, 1,
                "FINISHED", "Group H", Instant.now(), "football-data.org", null);
        repo.upsertMatches(List.of(updated));

        List<Match> found = repo.findMatchesByTeam("Spain");
        assertEquals(3, found.get(0).homeScore());
    }

    @Test
    void findUpcomingMatchesReturnsScheduledOnly() throws SQLException {
        repo.upsertMatches(List.of(
                new Match("1", "A", "B", null, null, "TIMED",     "G1", Instant.now().plusSeconds(3600), "src", null),
                new Match("2", "C", "D", 1,    0,    "FINISHED",  "G1", Instant.now().minusSeconds(3600), "src", null),
                new Match("3", "E", "F", null, null, "SCHEDULED", "G2", Instant.now().plusSeconds(7200), "src", null)
        ));
        List<Match> upcoming = repo.findUpcomingMatches();
        assertEquals(2, upcoming.size());
        assertTrue(upcoming.stream().noneMatch(m -> "FINISHED".equals(m.status())));
    }

    // --- Standings ---

    @Test
    void upsertAndFindStandingByTeam() throws SQLException {
        GroupStanding s = new GroupStanding("Group J", "Argentina", 1, 1, 0, 0, 3, 3, 1);
        repo.upsertStandings(List.of(s));

        List<GroupStanding> found = repo.findStandingsByTeam("Argentina");
        assertEquals(1, found.size());
        assertEquals(3, found.get(0).points());
        assertEquals(1, found.get(0).position());
    }

    @Test
    void upsertStandingUpdatesOnConflict() throws SQLException {
        repo.upsertStandings(List.of(new GroupStanding("Group A", "Brazil", 1, 1, 0, 0, 2, 3, 1)));
        repo.upsertStandings(List.of(new GroupStanding("Group A", "Brazil", 2, 1, 1, 0, 2, 4, 2)));

        List<GroupStanding> found = repo.findStandingsByTeam("Brazil");
        assertEquals(4, found.get(0).points());
        assertEquals(2, found.get(0).played());
    }

    // --- Players ---

    @Test
    void upsertAndFindPlayerByExactName() throws SQLException {
        Player p = new Player("Lionel Messi", "Argentina", 3, 1, 2, null, "Forward", "Argentine", null);
        repo.upsertPlayers(List.of(p));

        Player found = repo.findPlayer("Lionel Messi");
        assertNotNull(found);
        assertEquals(3, found.goals());
    }

    @Test
    void findPlayerByPartialName() throws SQLException {
        repo.upsertPlayers(List.of(
                new Player("Lionel Messi", "Argentina", 3, 0, 1, null, null, null, null)));
        Player found = repo.findPlayer("Messi");
        assertNotNull(found);
        assertEquals("Lionel Messi", found.name());
    }

    @Test
    void upsertPlayerPreservesLastMatchSummaryWhenNull() throws SQLException {
        repo.upsertPlayers(List.of(
                new Player("Harry Kane", "England", 2, 0, 1, "1 goal, 0 assists", null, null, null)));
        // Second upsert has null summary
        repo.upsertPlayers(List.of(
                new Player("Harry Kane", "England", 2, 0, 1, null, null, null, null)));

        Player found = repo.findPlayer("Harry Kane");
        assertEquals("1 goal, 0 assists", found.lastMatchSummary());
    }

    @Test
    void findPlayerReturnsNullWhenNotFound() throws SQLException {
        assertNull(repo.findPlayer("Nobody"));
    }

    // --- News ---

    @Test
    void upsertAndFindNewsByTeam() throws SQLException {
        NewsItem item = new NewsItem("n1", "Argentina Win!", "Great match",
                Instant.now(), "Argentina");
        repo.upsertNews(List.of(item));

        List<NewsItem> found = repo.findNewsByTeam("Argentina");
        assertEquals(1, found.size());
        assertEquals("Argentina Win!", found.get(0).headline());
    }

    @Test
    void findNewsByTeamReturnsOnlyMatchingTeam() throws SQLException {
        repo.upsertNews(List.of(
                new NewsItem("1", "Argentina news", null, Instant.now(), "Argentina"),
                new NewsItem("2", "Spain news",     null, Instant.now(), "Spain")
        ));
        assertEquals(1, repo.findNewsByTeam("Argentina").size());
        assertEquals(1, repo.findNewsByTeam("Spain").size());
    }

    @Test
    void newsUpsertIsIdempotent() throws SQLException {
        NewsItem item = new NewsItem("x1", "headline", "desc", Instant.now(), "France");
        repo.upsertNews(List.of(item));
        repo.upsertNews(List.of(item));
        assertEquals(1, repo.findNewsByTeam("France").size());
    }
}
