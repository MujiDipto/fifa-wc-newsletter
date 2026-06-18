package com.muji.worldcup.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Client for ESPN's unofficial public API. No key or signup required.
 * Covers WC rosters, top scorers, and per-match player performance (lineups).
 *
 * League slug for FIFA World Cup 2026: fifa.world
 * Base: https://site.api.espn.com/apis/site/v2/sports/soccer/
 */
public class EspnClient implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(EspnClient.class);
    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world";
    private static final String SOURCE = "espn";

    private final HttpClient http;
    private final ObjectMapper mapper;

    public EspnClient() {
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    /**
     * Fetches the current top scorers / leaders for the WC.
     * ESPN leaders endpoint returns goal, assist, and appearance counts.
     */
    public List<Player> fetchTopScorers() throws Exception {
        String url = BASE + "/leaders";
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();

        // ESPN leaders groups stats by category; find "goals" category
        for (JsonNode category : root.path("leaders")) {
            String catName = category.path("name").asText("");
            if (!catName.equalsIgnoreCase("goals") && !catName.equalsIgnoreCase("score")) continue;

            for (JsonNode leader : category.path("leaders")) {
                JsonNode athlete = leader.path("athlete");
                String name = athlete.path("displayName").asText();
                String team = athlete.path("team").path("displayName").asText();
                int goals = (int) leader.path("value").asDouble(0);
                players.add(new Player(name, team, goals, 0, 0, null));
            }
        }

        log.info("Fetched {} top scorer(s) from ESPN", players.size());
        return players;
    }

    /**
     * Fetches per-player stats from a match summary (lineup + performance).
     * eventId is ESPN's event ID, mapped from the scoreboard response.
     */
    public List<Player> fetchMatchPlayers(String eventId) throws Exception {
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world/summary?event=" + eventId;
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();
        for (JsonNode boxscore : root.path("boxscore").path("players")) {
            String teamName = boxscore.path("team").path("displayName").asText();
            for (JsonNode playerEntry : boxscore.path("statistics").path(0).path("athletes")) {
                JsonNode athlete = playerEntry.path("athlete");
                String name = athlete.path("displayName").asText();
                if (name.isBlank()) continue;

                int goals = statInt(playerEntry, "goals");
                int assists = statInt(playerEntry, "goalAssists");
                String summary = buildSummary(playerEntry);

                players.add(new Player(name, teamName, goals, assists, 1, summary));
            }
        }

        log.info("Fetched {} player record(s) for ESPN event {}", players.size(), eventId);
        return players;
    }

    /**
     * Fetches today's scoreboard to get ESPN event IDs for finished matches.
     * These IDs can be used with fetchMatchPlayers().
     */
    public List<String> fetchFinishedEventIds() throws Exception {
        String url = BASE + "/scoreboard";
        JsonNode root = get(url);

        List<String> ids = new ArrayList<>();
        for (JsonNode event : root.path("events")) {
            String status = event.path("status").path("type").path("name").asText();
            if ("STATUS_FINAL".equals(status)) {
                ids.add(event.path("id").asText());
            }
        }
        log.info("Found {} finished event(s) on today's scoreboard", ids.size());
        return ids;
    }

    private int statInt(JsonNode playerEntry, String statName) {
        for (JsonNode stat : playerEntry.path("stats")) {
            // ESPN stats are positional arrays; we match by the "names" array on the parent
            // Fallback: try direct field name
        }
        // Try direct path for common mappings
        JsonNode val = playerEntry.path(statName);
        return val.isMissingNode() ? 0 : val.asInt(0);
    }

    private String buildSummary(JsonNode playerEntry) {
        // Build a readable summary from whatever stat fields are present
        int goals = statInt(playerEntry, "goals");
        int assists = statInt(playerEntry, "goalAssists");
        int shots = statInt(playerEntry, "shots");
        return String.format("%d goal(s), %d assist(s), %d shot(s)", goals, assists, shots);
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("ESPN API returned HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }
}
