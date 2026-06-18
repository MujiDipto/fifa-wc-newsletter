package com.muji.worldcup.config;

import com.muji.worldcup.model.Subscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubscriberConfigLoaderTest {

    private final SubscriberConfigLoader loader = new SubscriberConfigLoader();

    @Test
    void loadsFullSubscriber(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("subscribers.yaml");
        Files.writeString(yaml, """
                subscribers:
                  - email: alice@example.com
                    team: Argentina
                    player: Messi
                """);
        List<Subscriber> subs = loader.load(yaml);
        assertEquals(1, subs.size());
        assertEquals("alice@example.com", subs.get(0).email());
        assertEquals("Argentina", subs.get(0).followedTeam());
        assertEquals("Messi", subs.get(0).followedPlayer());
    }

    @Test
    void loadsSubscriberWithNullPlayer(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("subscribers.yaml");
        Files.writeString(yaml, """
                subscribers:
                  - email: bob@example.com
                    team: Spain
                    player: null
                """);
        List<Subscriber> subs = loader.load(yaml);
        assertEquals(1, subs.size());
        assertNull(subs.get(0).followedPlayer());
    }

    @Test
    void loadsMultipleSubscribers(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("subscribers.yaml");
        Files.writeString(yaml, """
                subscribers:
                  - email: a@example.com
                    team: France
                    player: Mbappé
                  - email: b@example.com
                    team: Brazil
                    player: null
                """);
        List<Subscriber> subs = loader.load(yaml);
        assertEquals(2, subs.size());
    }

    @Test
    void emptySubscriberList(@TempDir Path dir) throws IOException {
        Path yaml = dir.resolve("subscribers.yaml");
        Files.writeString(yaml, "subscribers: []\n");
        List<Subscriber> subs = loader.load(yaml);
        assertTrue(subs.isEmpty());
    }
}
