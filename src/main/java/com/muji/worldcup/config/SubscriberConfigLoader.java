package com.muji.worldcup.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.muji.worldcup.model.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class SubscriberConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(SubscriberConfigLoader.class);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public List<Subscriber> load(Path configPath) throws IOException {
        SubscribersFile file = YAML.readValue(configPath.toFile(), SubscribersFile.class);
        List<Subscriber> subscribers = file.subscribers().stream()
                .map(e -> new Subscriber(e.email(), e.team(), e.player()))
                .toList();
        log.info("Loaded {} subscriber(s) from {}", subscribers.size(), configPath);
        return subscribers;
    }

    // --- internal YAML-binding types ---

    record SubscribersFile(@JsonProperty("subscribers") List<SubscriberEntry> subscribers) {}

    record SubscriberEntry(
            @JsonProperty("email") String email,
            @JsonProperty("team") String team,
            @JsonProperty("player") String player
    ) {}
}
