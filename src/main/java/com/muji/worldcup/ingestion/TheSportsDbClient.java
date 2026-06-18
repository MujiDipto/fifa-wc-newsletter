package com.muji.worldcup.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Client for TheSportsDB free tier (API key "3").
 * No signup required. Provides player biographical data (position, nationality,
 * description) and team info not available from the other two sources.
 *
 * Base: https://www.thesportsdb.com/api/v1/json/3/
 */
public class TheSportsDbClient implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbClient.class);
    private static final String BASE = "https://www.thesportsdb.com/api/v1/json/3";
    private static final String SOURCE = "thesportsdb";

    private final HttpClient http;
    private final ObjectMapper mapper;

    public TheSportsDbClient() {
        this.http = HttpClient.newHttpClient();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    public record PlayerBio(
            String name,
            String team,
            String position,
            String nationality,
            String description
    ) {}

    public record TeamInfo(
            String name,
            String country,
            String description,
            String stadiumName
    ) {}

    /**
     * Looks up a player by name and returns biographical data.
     * Returns empty if not found or if the API has no entry for this player.
     */
    public Optional<PlayerBio> fetchPlayerBio(String playerName) throws Exception {
        String url = BASE + "/searchplayers.php?p=" + encode(playerName);
        JsonNode root = get(url);

        JsonNode players = root.path("player");
        if (players.isNull() || players.isEmpty()) {
            log.debug("No TheSportsDB entry for player: {}", playerName);
            return Optional.empty();
        }

        // Take the first result — name searches can return multiple variants
        JsonNode p = players.path(0);
        return Optional.of(new PlayerBio(
                p.path("strPlayer").asText(playerName),
                p.path("strTeam").asText(null),
                p.path("strPosition").asText(null),
                p.path("strNationality").asText(null),
                p.path("strDescriptionEN").asText(null)
        ));
    }

    /**
     * Looks up a team by name and returns descriptive info.
     * Returns empty if not found.
     */
    public Optional<TeamInfo> fetchTeamInfo(String teamName) throws Exception {
        String url = BASE + "/searchteams.php?t=" + encode(teamName);
        JsonNode root = get(url);

        JsonNode teams = root.path("teams");
        if (teams.isNull() || teams.isEmpty()) {
            log.debug("No TheSportsDB entry for team: {}", teamName);
            return Optional.empty();
        }

        JsonNode t = teams.path(0);
        return Optional.of(new TeamInfo(
                t.path("strTeam").asText(teamName),
                t.path("strCountry").asText(null),
                t.path("strDescriptionEN").asText(null),
                t.path("strStadium").asText(null)
        ));
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("TheSportsDB returned HTTP " + response.statusCode() + " for " + url);
        }
        return mapper.readTree(response.body());
    }

    private String encode(String value) {
        return value.replace(" ", "%20");
    }
}
