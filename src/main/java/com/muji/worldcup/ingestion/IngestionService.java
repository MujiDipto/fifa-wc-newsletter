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
import java.util.List;
import java.util.concurrent.Future;

/**
 * Orchestrates concurrent ingestion from both data sources via the worker pool.
 *
 * football-data.org is authoritative for matches, standings, and scorer stats.
 * ESPN supplements with per-player match performance for finished matches —
 * its failure is non-fatal; the pipeline continues with football-data.org data.
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
        this.retry = new RetryWithBackoff(3, 5_000, 2.0);
    }

    public void ingest(LocalDate date) throws Exception {
        log.info("Starting ingestion for {}", date);

        // Fan out the three football-data.org fetches concurrently
        Future<List<Match>> matchesFuture = pool.submit(
                () -> retry.execute("fetchMatches", () -> footballDataClient.fetchMatches(date)));

        Future<List<GroupStanding>> standingsFuture = pool.submit(
                () -> retry.execute("fetchStandings", footballDataClient::fetchStandings));

        Future<List<Player>> scorersFuture = pool.submit(
                () -> retry.execute("fetchScorers", footballDataClient::fetchScorers));

        // Collect football-data.org results — propagate failure, these are required
        List<Match> matches = matchesFuture.get();
        List<GroupStanding> standings = standingsFuture.get();
        List<Player> players = new ArrayList<>(scorersFuture.get());

        repository.upsertMatches(matches);
        repository.upsertStandings(standings);
        repository.upsertPlayers(players);

        // ESPN: fetch per-player stats for finished matches — non-fatal if unavailable
        enrichWithEspnMatchStats(players);

        log.info("Ingestion complete: {} matches, {} standings, {} players",
                matches.size(), standings.size(), players.size());
    }

    private void enrichWithEspnMatchStats(List<Player> existingPlayers) {
        try {
            List<String> finishedEventIds = espnClient.fetchFinishedEventIds();
            if (finishedEventIds.isEmpty()) {
                log.info("No finished matches on ESPN scoreboard today, skipping player enrichment");
                return;
            }
            List<Player> enriched = new ArrayList<>();
            for (String eventId : finishedEventIds) {
                enriched.addAll(espnClient.fetchMatchPlayers(eventId));
            }
            if (!enriched.isEmpty()) {
                repository.upsertPlayers(enriched);
                log.info("Enriched {} player record(s) from ESPN match data", enriched.size());
            }
        } catch (Exception e) {
            log.warn("ESPN enrichment failed (non-fatal): {}", e.getMessage());
        }
    }
}
