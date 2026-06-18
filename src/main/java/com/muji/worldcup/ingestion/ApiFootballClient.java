package com.muji.worldcup.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muji.worldcup.concurrency.RateLimiter;
import com.muji.worldcup.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for api-football via RapidAPI. Free tier: 100 req/day.
 * Used specifically to fill the player-stats gap that football-data.org's
 * free tier does not cover (lineups, per-match player performance).
 */
public class ApiFootballClient implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(ApiFootballClient.class);
    private static final String BASE_URL = "https://api-football-v1.p.rapidapi.com/v3";
    private static final String HOST = "api-football-v1.p.rapidapi.com";
    private static final String SOURCE = "api-football";

    // WC 2026 league ID in API-Football. Verify after signup; this is the standard FIFA WC id.
    private static final int WC_LEAGUE_ID = 1;
    private static final int WC_SEASON = 2026;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String rapidApiKey;
    // Conservative: stay well within 100 req/day — treat as 4 req/hour
    private final RateLimiter rateLimiter = new RateLimiter("api-football", 4, 3_600_000);

    public ApiFootballClient(String rapidApiKey) {
        this.rapidApiKey = rapidApiKey;
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    /**
     * Fetches top player stats for the WC from API-Football.
     * Returns a list of Players with goals, assists, and appearances populated.
     */
    public List<Player> fetchPlayerStats() throws Exception {
        String url = BASE_URL + "/players/topscorers?league=" + WC_LEAGUE_ID + "&season=" + WC_SEASON;
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();
        for (JsonNode entry : root.path("response")) {
            JsonNode p = entry.path("player");
            JsonNode stats = entry.path("statistics").path(0);
            players.add(new Player(
                    p.path("name").asText(),
                    stats.path("team").path("name").asText(),
                    stats.path("goals").path("total").asInt(0),
                    stats.path("goals").path("assists").asInt(0),
                    stats.path("games").path("appearences").asInt(0),
                    null
            ));
        }
        log.info("Fetched {} player stat records from api-football", players.size());
        return players;
    }

    /**
     * Fetches players for a specific team fixture (for last-match summary).
     * fixtureId comes from the match data stored in SQLite.
     */
    public List<Player> fetchFixturePlayers(int fixtureId) throws Exception {
        String url = BASE_URL + "/fixtures/players?fixture=" + fixtureId;
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();
        for (JsonNode teamEntry : root.path("response")) {
            String teamName = teamEntry.path("team").path("name").asText();
            for (JsonNode playerEntry : teamEntry.path("players")) {
                JsonNode p = playerEntry.path("player");
                JsonNode stats = playerEntry.path("statistics").path(0);
                String summary = buildSummary(stats);
                players.add(new Player(
                        p.path("name").asText(),
                        teamName,
                        stats.path("goals").path("total").asInt(0),
                        stats.path("goals").path("assists").asInt(0),
                        1,
                        summary
                ));
            }
        }
        log.info("Fetched {} player records for fixture {}", players.size(), fixtureId);
        return players;
    }

    private String buildSummary(JsonNode stats) {
        int goals = stats.path("goals").path("total").asInt(0);
        int assists = stats.path("goals").path("assists").asInt(0);
        int rating = stats.path("games").path("rating").asInt(0);
        return String.format("%d goal(s), %d assist(s), rating: %d", goals, assists, rating);
    }

    private JsonNode get(String url) throws Exception {
        rateLimiter.acquire();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-RapidAPI-Key", rapidApiKey)
                .header("X-RapidAPI-Host", HOST)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new RuntimeException("Rate limited by api-football (429)");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("api-football returned HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }
}
