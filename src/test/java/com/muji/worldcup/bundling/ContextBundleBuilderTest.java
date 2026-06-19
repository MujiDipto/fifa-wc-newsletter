package com.muji.worldcup.bundling;

import com.muji.worldcup.model.*;
import com.muji.worldcup.persistence.Database;
import com.muji.worldcup.persistence.SqliteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBundleBuilderTest {

    private SqliteRepository repo;
    private ContextBundleBuilder builder;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws SQLException {
        Database db = new Database(tempDir.resolve("test.db").toString());
        db.initSchema();
        repo = new SqliteRepository(db);
        builder = new ContextBundleBuilder(repo);
    }

    @Test
    void buildsActiveStatusByDefault() throws SQLException {
        Subscriber sub = new Subscriber("a@example.com", null);
        ContextBundle bundle = builder.build(sub);
        assertEquals(ContextBundle.EliminationStatus.ACTIVE, bundle.eliminationStatus());
    }

    @Test
    void teamUpdatePopulatedWhenStandingExists() throws SQLException {
        repo.upsertStandings(List.of(
                new GroupStanding("Group J", "Argentina", 1, 1, 0, 0, 3, 3, 1)));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        ContextBundle bundle = builder.build(sub);

        assertNotNull(bundle.teamUpdate());
        assertTrue(bundle.teamUpdate().contains("Argentina"));
        assertTrue(bundle.teamUpdate().contains("3 pts"));
    }

    @Test
    void teamUpdateFallbackWhenNoStanding() throws SQLException {
        Subscriber sub = new Subscriber("a@example.com", "Narnia FC");
        ContextBundle bundle = builder.build(sub);
        assertTrue(bundle.teamUpdate().contains("No standing data"));
    }

    @Test
    void matchRecapShowsFinishedMatch() throws SQLException {
        repo.upsertMatches(List.of(new Match(
                "1", "Argentina", "France", 2, 1,
                "FINISHED", "Group J", Instant.now().minusSeconds(3600), "src")));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        ContextBundle bundle = builder.build(sub);

        assertTrue(bundle.matchDayRecapText().contains("Argentina"));
        assertTrue(bundle.matchDayRecapText().contains("2"));
    }

    @Test
    void matchRecapFallbackWhenNoMatchToday() throws SQLException {
        Subscriber sub = new Subscriber("a@example.com", "Spain");
        ContextBundle bundle = builder.build(sub);
        assertTrue(bundle.matchDayRecapText().contains("have not played"));
    }

    @Test
    void nextMatchPreviewShowsUpcomingFixture() throws SQLException {
        repo.upsertMatches(List.of(new Match(
                "99", "Argentina", "Brazil", null, null,
                "TIMED", "Group J", Instant.now().plusSeconds(7200), "src")));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        ContextBundle bundle = builder.build(sub);

        assertTrue(bundle.nextMatchDayPreview().contains("Argentina"));
        assertTrue(bundle.nextMatchDayPreview().contains("Brazil"));
    }

    @Test
    void nextMatchFallbackWhenNoUpcoming() throws SQLException {
        Subscriber sub = new Subscriber("a@example.com", "Iceland");
        ContextBundle bundle = builder.build(sub);
        assertTrue(bundle.nextMatchDayPreview().contains("No upcoming"));
    }

    @Test
    void eliminationStatusIsJustEliminatedOnFirstDetection() throws SQLException {
        repo.upsertMatches(List.of(new Match(
                "1", "Argentina", "France", 2, 1,
                "FINISHED", "Group J", Instant.now().minusSeconds(3600), "src")));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        ContextBundle bundle = builder.build(sub);
        assertEquals(ContextBundle.EliminationStatus.JUST_ELIMINATED, bundle.eliminationStatus());
    }

    @Test
    void eliminationStatusIsAlreadyHandledOnSecondRun() throws SQLException {
        repo.upsertMatches(List.of(new Match(
                "1", "Argentina", "France", 2, 1,
                "FINISHED", "Group J", Instant.now().minusSeconds(3600), "src")));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        builder.build(sub);
        ContextBundle second = builder.build(sub);
        assertEquals(ContextBundle.EliminationStatus.ALREADY_HANDLED, second.eliminationStatus());
    }

    @Test
    void eliminationStatusIsActiveWhenUpcomingMatchExists() throws SQLException {
        repo.upsertMatches(List.of(
                new Match("1", "Argentina", "France", 2, 1,
                        "FINISHED", "Group J", Instant.now().minusSeconds(3600), "src"),
                new Match("2", "Argentina", "Brazil", null, null,
                        "SCHEDULED", "Group J", Instant.now().plusSeconds(7200), "src")));

        Subscriber sub = new Subscriber("a@example.com", "Argentina");
        ContextBundle bundle = builder.build(sub);
        assertEquals(ContextBundle.EliminationStatus.ACTIVE, bundle.eliminationStatus());
    }

    @Test
    void nullTeamProducesNullSections() throws SQLException {
        Subscriber sub = new Subscriber("a@example.com", null);
        ContextBundle bundle = builder.build(sub);
        assertNull(bundle.teamUpdate());
        assertNull(bundle.matchDayRecapText());
        assertNull(bundle.nextMatchDayPreview());
    }
}
