package com.muji.worldcup.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muji.worldcup.concurrency.RateLimiter;
import com.muji.worldcup.model.GroupStanding;
import com.muji.worldcup.model.Match;
import com.muji.worldcup.model.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for football-data.org v4 API. Free tier: 10 req/min, covers WC matches,
 * standings, and scorers. Does NOT include detailed player stats (covered by ApiFootballClient).
 */
public class FootballDataClient implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(FootballDataClient.class);
    private static final String BASE_URL = "https://api.football-data.org/v4";
    private static final String SOURCE = "football-data.org";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiToken;
    // 10 requests per 60 seconds
    private final RateLimiter rateLimiter = new RateLimiter("football-data.org", 10, 60_000);

    public FootballDataClient(String apiToken) {
        this.apiToken = apiToken;
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    public List<Match> fetchMatches(LocalDate date) throws Exception {
        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String url = BASE_URL + "/competitions/WC/matches?dateFrom=" + dateStr + "&dateTo=" + dateStr;
        JsonNode root = get(url);

        List<Match> matches = new ArrayList<>();
        for (JsonNode m : root.path("matches")) {
            matches.add(new Match(
                    m.path("id").asText(),
                    m.path("homeTeam").path("name").asText(),
                    m.path("awayTeam").path("name").asText(),
                    scoreOrNull(m, "home"),
                    scoreOrNull(m, "away"),
                    m.path("status").asText(),
                    m.path("group").asText(null),
                    Instant.parse(m.path("utcDate").asText()),
                    SOURCE
            ));
        }
        log.info("Fetched {} matches for {}", matches.size(), date);
        return matches;
    }

    public List<GroupStanding> fetchStandings() throws Exception {
        String url = BASE_URL + "/competitions/WC/standings";
        JsonNode root = get(url);

        List<GroupStanding> standings = new ArrayList<>();
        for (JsonNode section : root.path("standings")) {
            String group = section.path("group").asText();
            for (JsonNode row : section.path("table")) {
                standings.add(new GroupStanding(
                        group,
                        row.path("team").path("name").asText(),
                        row.path("playedGames").asInt(),
                        row.path("won").asInt(),
                        row.path("draw").asInt(),
                        row.path("lost").asInt(),
                        row.path("goalDifference").asInt(),
                        row.path("points").asInt(),
                        row.path("position").asInt()
                ));
            }
        }
        log.info("Fetched {} standing rows", standings.size());
        return standings;
    }

    public List<Player> fetchScorers() throws Exception {
        String url = BASE_URL + "/competitions/WC/scorers";
        JsonNode root = get(url);

        List<Player> players = new ArrayList<>();
        for (JsonNode s : root.path("scorers")) {
            players.add(new Player(
                    s.path("player").path("name").asText(),
                    s.path("team").path("name").asText(),
                    s.path("goals").asInt(0),
                    s.path("assists").asInt(0),
                    s.path("playedMatches").asInt(0),
                    null, null, null, null
            ));
        }
        log.info("Fetched {} scorers", players.size());
        return players;
    }

    private JsonNode get(String url) throws Exception {
        rateLimiter.acquire();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Auth-Token", apiToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new RuntimeException("Rate limited by football-data.org (429)");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("football-data.org returned HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }

    private Integer scoreOrNull(JsonNode match, String side) {
        JsonNode val = match.path("score").path("fullTime").path(side);
        return val.isNull() || val.isMissingNode() ? null : val.asInt();
    }
}
