package com.muji.worldcup.ingestion;

import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.concurrency.RetryWithBackoff;
import com.muji.worldcup.model.GroupStanding;
import com.muji.worldcup.model.Match;
import com.muji.worldcup.model.Player;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Orchestrates concurrent ingestion from both data sources via the worker pool.
 * football-data.org and api-football run in parallel; their results are merged
 * and persisted to SQLite.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final BoundedWorkerPool pool;
    private final FootballDataClient footballDataClient;
    private final ApiFootballClient apiFootballClient;
    private final SqliteRepository repository;
    private final RetryWithBackoff retry;

    public IngestionService(
            BoundedWorkerPool pool,
            FootballDataClient footballDataClient,
            ApiFootballClient apiFootballClient,
            SqliteRepository repository
    ) {
        this.pool = pool;
        this.footballDataClient = footballDataClient;
        this.apiFootballClient = apiFootballClient;
        this.repository = repository;
        // 3 attempts, 5s initial delay, 2x backoff — covers transient 429s and network blips
        this.retry = new RetryWithBackoff(3, 5_000, 2.0);
    }

    public void ingest(LocalDate date) throws Exception {
        log.info("Starting ingestion for {}", date);

        // Fan out all fetches concurrently into the worker pool
        Future<List<Match>> matchesFuture = pool.submit(
                () -> retry.execute("fetchMatches", () -> footballDataClient.fetchMatches(date)));

        Future<List<GroupStanding>> standingsFuture = pool.submit(
                () -> retry.execute("fetchStandings", footballDataClient::fetchStandings));

        Future<List<Player>> scorersFuture = pool.submit(
                () -> retry.execute("fetchScorers", footballDataClient::fetchScorers));

        Future<List<Player>> playerStatsFuture = pool.submit(
                () -> retry.execute("fetchPlayerStats", apiFootballClient::fetchPlayerStats));

        // Collect — propagate any failure immediately
        List<Match> matches = matchesFuture.get();
        List<GroupStanding> standings = standingsFuture.get();
        List<Player> scorers = scorersFuture.get();
        List<Player> apiPlayers = playerStatsFuture.get();

        // Merge player data: api-football stats take precedence for goals/assists/appearances;
        // football-data.org scorers fill in anyone api-football missed
        List<Player> mergedPlayers = mergePlayers(scorers, apiPlayers);

        repository.upsertMatches(matches);
        repository.upsertStandings(standings);
        repository.upsertPlayers(mergedPlayers);

        log.info("Ingestion complete: {} matches, {} standings, {} players",
                matches.size(), standings.size(), mergedPlayers.size());
    }

    private List<Player> mergePlayers(List<Player> scorers, List<Player> apiPlayers) {
        Map<String, Player> merged = new HashMap<>();
        // Start with scorers from football-data.org as the base
        for (Player p : scorers) {
            merged.put(p.name().toLowerCase(), p);
        }
        // Overlay with api-football data which has richer stats
        for (Player p : apiPlayers) {
            merged.put(p.name().toLowerCase(), p);
        }
        return new ArrayList<>(merged.values());
    }
}
