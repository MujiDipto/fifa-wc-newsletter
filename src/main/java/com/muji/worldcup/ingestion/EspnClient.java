package com.muji.worldcup.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muji.worldcup.model.NewsItem;
import com.muji.worldcup.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for ESPN's unofficial public API. No key or signup required.
 * Used specifically to enrich player records with per-match performance
 * (goals, assists, last-match summary) after matches have finished.
 *
 * Base: https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world
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
     * Returns ESPN event IDs for matches that finished today.
     * These are used to fetch per-player stats via fetchMatchPlayers().
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
        log.info("Found {} finished event(s) on ESPN scoreboard", ids.size());
        return ids;
    }

    /**
     * Fetches per-player stats from a finished match summary.
     * Returns an empty list (not an error) if the match hasn't finished yet.
     */
    public List<Player> fetchMatchPlayers(String eventId) throws Exception {
        String url = BASE + "/summary?event=" + eventId;
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();
        for (JsonNode section : root.path("boxscore").path("players")) {
            String teamName = section.path("team").path("displayName").asText();
            JsonNode statsSection = section.path("statistics").path(0);
            JsonNode names = statsSection.path("names");

            for (JsonNode playerEntry : statsSection.path("athletes")) {
                JsonNode athlete = playerEntry.path("athlete");
                String name = athlete.path("displayName").asText();
                if (name.isBlank()) continue;

                // Stats are a positional array aligned to the "names" array
                int goals = 0, assists = 0;
                JsonNode stats = playerEntry.path("stats");
                for (int i = 0; i < names.size(); i++) {
                    String statName = names.path(i).asText();
                    int val = stats.path(i).asInt(0);
                    if ("G".equalsIgnoreCase(statName) || "goals".equalsIgnoreCase(statName)) goals = val;
                    if ("A".equalsIgnoreCase(statName) || "assists".equalsIgnoreCase(statName)) assists = val;
                }

                String summary = goals + " goal(s), " + assists + " assist(s)";
                players.add(new Player(name, teamName, goals, assists, 1, summary, null, null, null));
            }
        }
        log.info("Fetched {} player record(s) for ESPN event {}", players.size(), eventId);
        return players;
    }

    /**
     * Fetches the latest WC news articles from ESPN.
     * Each article is tagged with a related team where ESPN provides one.
     */
    public List<NewsItem> fetchNews() throws Exception {
        String url = BASE + "/news";
        JsonNode root = get(url);

        List<NewsItem> items = new ArrayList<>();
        for (JsonNode article : root.path("articles")) {
            String id = article.path("dataSourceIdentifier").asText(
                    String.valueOf(article.path("id").asLong()));
            String headline = article.path("headline").asText();
            String description = article.path("description").asText(null);
            String publishedStr = article.path("published").asText(null);
            Instant publishedAt = publishedStr != null ? Instant.parse(publishedStr) : Instant.now();

            // Extract first team category if present
            String relatedTeam = null;
            for (JsonNode cat : article.path("categories")) {
                if ("team".equalsIgnoreCase(cat.path("type").asText())) {
                    relatedTeam = cat.path("description").asText(null);
                    break;
                }
            }
            items.add(new NewsItem(id, headline, description, publishedAt, relatedTeam));
        }
        log.info("Fetched {} news article(s) from ESPN", items.size());
        return items;
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
