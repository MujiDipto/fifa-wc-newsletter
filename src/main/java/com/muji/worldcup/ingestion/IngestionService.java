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
 * football-data.org and ESPN run in parallel; their results are merged
 * and persisted to SQLite.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final BoundedWorkerPool pool;
    private final FootballDataClient footballDataClient;
    private final EspnClient espnClient;
    private final SqliteRepository repository;
    private final RetryWithBackoff retry;

    public IngestionService(
            BoundedWorkerPool pool,
            FootballDataClient footballDataClient,
            EspnClient espnClient,
            SqliteRepository repository
    ) {
        this.pool = pool;
        this.footballDataClient = footballDataClient;
        this.espnClient = espnClient;
        this.repository = repository;
        // 3 attempts, 5s initial delay, 2x backoff — covers transient errors and rate limits
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

        Future<List<Player>> espnScorersFuture = pool.submit(
                () -> retry.execute("espnTopScorers", espnClient::fetchTopScorers));

        // Collect — propagate any failure immediately
        List<Match> matches = matchesFuture.get();
        List<GroupStanding> standings = standingsFuture.get();
        List<Player> fdScorers = scorersFuture.get();
        List<Player> espnScorers = espnScorersFuture.get();

        // Merge: football-data.org scorers are the base; ESPN enriches where it can
        List<Player> mergedPlayers = mergePlayers(fdScorers, espnScorers);

        repository.upsertMatches(matches);
        repository.upsertStandings(standings);
        repository.upsertPlayers(mergedPlayers);

        log.info("Ingestion complete: {} matches, {} standings, {} players",
                matches.size(), standings.size(), mergedPlayers.size());
    }

    private List<Player> mergePlayers(List<Player> base, List<Player> overlay) {
        Map<String, Player> merged = new HashMap<>();
        for (Player p : base) merged.put(p.name().toLowerCase(), p);
        // Overlay adds any ESPN-only entries; existing entries keep football-data.org's goal counts
        // since that source is authoritative for scorers
        for (Player p : overlay) merged.putIfAbsent(p.name().toLowerCase(), p);
        return new ArrayList<>(merged.values());
    }
}
