package com.muji.worldcup.ingestion;

import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.concurrency.RetryWithBackoff;
import com.muji.worldcup.model.GroupStanding;
import com.muji.worldcup.model.Match;
import com.muji.worldcup.model.NewsItem;
import com.muji.worldcup.model.Player;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;

/**
 * Orchestrates concurrent ingestion from all three data sources via the worker pool.
 *
 * football-data.org — authoritative for matches, standings, scorers.
 * ESPN              — per-match player stats (finished matches) + news headlines.
 * TheSportsDB       — player biographical data (position, nationality, description).
 *
 * ESPN and TheSportsDB failures are non-fatal; the pipeline continues with
 * whatever football-data.org provided.
 */
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final BoundedWorkerPool pool;
    private final FootballDataClient footballDataClient;
    private final EspnClient espnClient;
    private final TheSportsDbClient theSportsDbClient;
    private final SqliteRepository repository;
    private final RetryWithBackoff retry;

    public IngestionService(
            BoundedWorkerPool pool,
            FootballDataClient footballDataClient,
            EspnClient espnClient,
            TheSportsDbClient theSportsDbClient,
            SqliteRepository repository
    ) {
        this.pool = pool;
        this.footballDataClient = footballDataClient;
        this.espnClient = espnClient;
        this.theSportsDbClient = theSportsDbClient;
        this.repository = repository;
        this.retry = new RetryWithBackoff(3, 5_000, 2.0);
    }

    public void ingest(LocalDate date) throws Exception {
        log.info("Starting ingestion for {}", date);

        // Fan out football-data.org fetches + ESPN news concurrently
        // Fetch today + recent past (last 7 days) so recent results are always in the DB
        Future<List<Match>> matchesFuture = pool.submit(() -> {
            List<Match> all = new java.util.ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                final int daysBack = i;
                all.addAll(retry.execute("fetchMatches-" + daysBack,
                        () -> footballDataClient.fetchMatches(date.minusDays(daysBack))));
            }
            return all;
        });

        Future<List<Match>> scheduledFuture = pool.submit(
                () -> retry.execute("fetchScheduledMatches", footballDataClient::fetchScheduledMatches));

        Future<List<GroupStanding>> standingsFuture = pool.submit(
                () -> retry.execute("fetchStandings", footballDataClient::fetchStandings));

        Future<List<Player>> scorersFuture = pool.submit(
                () -> retry.execute("fetchScorers", footballDataClient::fetchScorers));

        Future<List<NewsItem>> newsFuture = pool.submit(
                () -> retry.execute("fetchNews", espnClient::fetchNews));

        // Collect football-data.org results — these are required
        List<Match> matches = matchesFuture.get();
        List<Match> scheduledMatches = scheduledFuture.get();
        List<GroupStanding> standings = standingsFuture.get();
        List<Player> players = scorersFuture.get();

        repository.upsertMatches(matches);
        repository.upsertMatches(scheduledMatches);
        repository.upsertStandings(standings);
        repository.upsertPlayers(players);

        // ESPN news — non-fatal
        try {
            List<NewsItem> news = newsFuture.get();
            repository.upsertNews(news);
        } catch (Exception e) {
            log.warn("ESPN news fetch failed (non-fatal): {}", e.getMessage());
        }

        // ESPN per-match player stats for finished matches — non-fatal
        enrichWithEspnMatchStats();

        // TheSportsDB player bios for all known scorers — non-fatal, runs sequentially
        // to avoid hammering the free tier with parallel lookups
        enrichWithPlayerBios(players);

        log.info("Ingestion complete: {} matches, {} standings, {} players",
                matches.size(), standings.size(), players.size());
    }

    private void enrichWithEspnMatchStats() {
        try {
            List<String> finishedEventIds = espnClient.fetchFinishedEventIds();
            if (finishedEventIds.isEmpty()) {
                log.info("No finished matches on ESPN scoreboard today, skipping player enrichment");
                return;
            }
            for (String eventId : finishedEventIds) {
                List<Player> matchPlayers = espnClient.fetchMatchPlayers(eventId);
                if (!matchPlayers.isEmpty()) repository.upsertPlayers(matchPlayers);
            }
        } catch (Exception e) {
            log.warn("ESPN match enrichment failed (non-fatal): {}", e.getMessage());
        }
    }

    private void enrichWithPlayerBios(List<Player> players) {
        for (Player player : players) {
            try {
                Optional<TheSportsDbClient.PlayerBio> bio =
                        theSportsDbClient.fetchPlayerBio(player.name());
                bio.ifPresent(b -> {
                    try {
                        repository.upsertPlayerBio(b);
                        log.debug("Stored bio for {}", player.name());
                    } catch (Exception e) {
                        log.warn("Failed to store bio for {} (non-fatal): {}", player.name(), e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("TheSportsDB bio fetch failed for {} (non-fatal): {}", player.name(), e.getMessage());
            }
        }
    }
}
