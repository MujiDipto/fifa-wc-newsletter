package com.muji.worldcup;

import com.muji.worldcup.bundling.ContextBundleBuilder;
import com.muji.worldcup.composer.PythonComposerClient;
import com.muji.worldcup.config.EnvLoader;
import com.muji.worldcup.config.SubscriberConfigLoader;
import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.concurrency.FanOutQueue;
import com.muji.worldcup.concurrency.PrioritisedJobScheduler;
import com.muji.worldcup.ingestion.EspnClient;
import com.muji.worldcup.ingestion.FootballDataClient;
import com.muji.worldcup.ingestion.IngestionService;
import com.muji.worldcup.ingestion.TheSportsDbClient;
import com.muji.worldcup.model.ComposedEmail;
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
    private static final String COMPOSER_URL = "http://localhost:8000";

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
        IngestionService ingestion = new IngestionService(
                ingestionPool,
                new FootballDataClient(EnvLoader.getRequired("FOOTBALL_DATA_API_TOKEN")),
                new EspnClient(),
                new TheSportsDbClient(),
                repository);
        ingestion.ingest(LocalDate.now());
        ingestionPool.shutdown();
        ingestionPool.awaitTermination(10, TimeUnit.SECONDS);

        // --- Bundling ---
        Set<String> subscribedTeams = subscribers.stream()
                .map(Subscriber::followedTeam).filter(t -> t != null).collect(Collectors.toSet());
        Set<String> subscribedPlayers = subscribers.stream()
                .map(Subscriber::followedPlayer).filter(p -> p != null).collect(Collectors.toSet());

        PrioritisedJobScheduler scheduler = new PrioritisedJobScheduler(4);
        ContextBundleBuilder bundleBuilder = new ContextBundleBuilder(repository);
        BoundedWorkerPool bundlePool = new BoundedWorkerPool(4, 32);
        FanOutQueue<Subscriber, ContextBundle> bundleFanOut = new FanOutQueue<>(bundlePool);

        List<ContextBundle> bundles = bundleFanOut.process(subscribers, subscriber -> {
            PrioritisedJobScheduler.Priority priority =
                    subscribedTeams.contains(subscriber.followedTeam()) ||
                    subscribedPlayers.contains(subscriber.followedPlayer())
                    ? PrioritisedJobScheduler.Priority.HIGH
                    : PrioritisedJobScheduler.Priority.NORMAL;
            Future<ContextBundle> f = scheduler.submit(() -> bundleBuilder.build(subscriber), priority);
            try {
                return f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Bundle failed for " + subscriber.email(), e);
            }
        });

        scheduler.shutdown();
        scheduler.awaitTermination(10, TimeUnit.SECONDS);
        bundlePool.shutdown();
        bundlePool.awaitTermination(10, TimeUnit.SECONDS);

        // --- Composition ---
        PythonComposerClient composer = new PythonComposerClient(COMPOSER_URL);
        if (!composer.isHealthy()) {
            log.warn("Python composer is not running at {} — skipping composition. " +
                     "Start it with: cd python-composer && uvicorn main:app", COMPOSER_URL);
            printBundles(bundles);
        } else {
            BoundedWorkerPool composerPool = new BoundedWorkerPool(2, 16);
            FanOutQueue<ContextBundle, ComposedEmail> composeFanOut = new FanOutQueue<>(composerPool);

            List<ComposedEmail> emails = composeFanOut.process(bundles,
                    bundle -> {
                        try {
                            return composer.compose(bundle);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });

            composerPool.shutdown();
            composerPool.awaitTermination(30, TimeUnit.SECONDS);

            for (int i = 0; i < emails.size(); i++) {
                printEmail(bundles.get(i).subscriber().email(), emails.get(i));
            }

            if (!composeFanOut.deadLetters().isEmpty()) {
                log.error("Composition failed for {} subscriber(s)", composeFanOut.deadLetters().size());
            }
        }

        log.info("Pipeline run complete");
    }

    private static void printBundles(List<ContextBundle> bundles) {
        for (ContextBundle b : bundles) {
            System.out.println("\n=== Bundle: " + b.subscriber().email() + " ===");
            System.out.println("Team:   " + b.teamUpdate());
            System.out.println("Player: " + b.playerUpdate());
            System.out.println("Next:   " + b.nextMatchDayPreview());
        }
    }

    private static void printEmail(String to, ComposedEmail email) {
        System.out.println("\n========================================");
        System.out.println("To      : " + to);
        System.out.println("Subject : " + email.subject());
        System.out.println("----------------------------------------");
        System.out.println(email.body());
        System.out.println("========================================");
    }
}
