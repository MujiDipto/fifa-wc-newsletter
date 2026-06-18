package com.muji.worldcup;

import com.muji.worldcup.bundling.ContextBundleBuilder;
import com.muji.worldcup.config.EnvLoader;
import com.muji.worldcup.config.SubscriberConfigLoader;
import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.concurrency.FanOutQueue;
import com.muji.worldcup.concurrency.PrioritisedJobScheduler;
import com.muji.worldcup.ingestion.EspnClient;
import com.muji.worldcup.ingestion.FootballDataClient;
import com.muji.worldcup.ingestion.IngestionService;
import com.muji.worldcup.ingestion.TheSportsDbClient;
import com.muji.worldcup.model.ContextBundle;
import com.muji.worldcup.model.Subscriber;
import com.muji.worldcup.persistence.Database;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        EnvLoader.load(Path.of(".env"));

        List<Subscriber> subscribers = new SubscriberConfigLoader()
                .load(Path.of("config/subscribers.yaml"));
        log.info("Loaded {} subscriber(s)", subscribers.size());

        Database db = new Database("worldcup.db");
        db.initSchema();
        SqliteRepository repository = new SqliteRepository(db);

        // --- Ingestion ---
        BoundedWorkerPool ingestionPool = new BoundedWorkerPool(4, 16);
        FootballDataClient footballDataClient = new FootballDataClient(
                EnvLoader.getRequired("FOOTBALL_DATA_API_TOKEN"));
        EspnClient espnClient = new EspnClient();
        TheSportsDbClient theSportsDbClient = new TheSportsDbClient();

        IngestionService ingestion = new IngestionService(
                ingestionPool, footballDataClient, espnClient, theSportsDbClient, repository);
        ingestion.ingest(LocalDate.now());

        ingestionPool.shutdown();
        ingestionPool.awaitTermination(10, TimeUnit.SECONDS);

        // --- Bundling ---
        // Subscribed teams and players get HIGH priority in the scheduler so they
        // are processed before the rest of the field.
        Set<String> subscribedTeams = subscribers.stream()
                .map(Subscriber::followedTeam)
                .filter(t -> t != null)
                .collect(Collectors.toSet());
        Set<String> subscribedPlayers = subscribers.stream()
                .map(Subscriber::followedPlayer)
                .filter(p -> p != null)
                .collect(Collectors.toSet());

        PrioritisedJobScheduler scheduler = new PrioritisedJobScheduler(4);
        ContextBundleBuilder bundleBuilder = new ContextBundleBuilder(repository);
        BoundedWorkerPool deliveryPool = new BoundedWorkerPool(4, 32);
        FanOutQueue<Subscriber, ContextBundle> fanOut = new FanOutQueue<>(deliveryPool);

        List<ContextBundle> bundles = fanOut.process(subscribers, subscriber -> {
            PrioritisedJobScheduler.Priority priority =
                    subscribedTeams.contains(subscriber.followedTeam()) ||
                    subscribedPlayers.contains(subscriber.followedPlayer())
                    ? PrioritisedJobScheduler.Priority.HIGH
                    : PrioritisedJobScheduler.Priority.NORMAL;

            Future<ContextBundle> future = scheduler.submit(
                    () -> bundleBuilder.build(subscriber), priority);
            try {
                return future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Bundle build failed for " + subscriber.email(), e);
            }
        });

        scheduler.shutdown();
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
        deliveryPool.shutdown();
        deliveryPool.awaitTermination(10, TimeUnit.SECONDS);

        // Print bundles — Phase 3 will replace this with LLM composition
        for (ContextBundle bundle : bundles) {
            printBundle(bundle);
        }

        if (!fanOut.deadLetters().isEmpty()) {
            log.error("Dead letters (subscribers that failed bundling): {}", fanOut.deadLetters());
        }

        log.info("Pipeline run complete — {} bundle(s) assembled", bundles.size());
    }

    private static void printBundle(ContextBundle b) {
        System.out.println("\n========================================");
        System.out.println("Subscriber : " + b.subscriber().email());
        System.out.println("Team       : " + b.subscriber().followedTeam());
        System.out.println("Player     : " + b.subscriber().followedPlayer());
        System.out.println("Status     : " + b.eliminationStatus());
        System.out.println("--- Match Recap ---");
        System.out.println(b.matchDayRecapText());
        System.out.println("--- Team Update ---");
        System.out.println(b.teamUpdate());
        System.out.println("--- Player Update ---");
        System.out.println(b.playerUpdate());
        System.out.println("--- Next Match ---");
        System.out.println(b.nextMatchDayPreview());
        System.out.println("========================================");
    }
}
