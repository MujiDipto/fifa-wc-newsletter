package com.muji.worldcup;

import com.muji.worldcup.bundling.ContextBundleBuilder;
import com.muji.worldcup.composer.PythonComposerClient;
import com.muji.worldcup.config.EnvLoader;
import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.concurrency.FanOutQueue;
import com.muji.worldcup.concurrency.PrioritisedJobScheduler;
import com.muji.worldcup.delivery.EmailSender;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final String COMPOSER_URL = "http://localhost:8000";

    public static void main(String[] args) throws Exception {
        boolean testMode = List.of(args).contains("--test");

        EnvLoader.load(Path.of(".env"));

        Database db = new Database("worldcup.db");
        db.initSchema();
        SqliteRepository repository = new SqliteRepository(db);

        List<Subscriber> subscribers;
        if (testMode) {
            subscribers = repository.getTestSubscribers();
            log.info("[TEST MODE] Loaded {} test subscriber(s) — timezone filter bypassed", subscribers.size());
            if (subscribers.isEmpty()) {
                log.warn("[TEST MODE] No test subscribers found. " +
                         "Mark one with: UPDATE subscribers SET is_test=1 WHERE email='you@example.com';");
                return;
            }
        } else {
            List<Subscriber> allSubscribers = repository.getActiveSubscribers();
            log.info("Loaded {} subscriber(s) from database", allSubscribers.size());

            // Only send to subscribers whose local time is currently 9am
            subscribers = allSubscribers.stream()
                    .filter(s -> {
                        try {
                            int localHour = LocalTime.now(ZoneId.of(s.timezone())).getHour();
                            return localHour == 9;
                        } catch (Exception e) {
                            log.warn("Invalid timezone '{}' for {}, skipping", s.timezone(), s.email());
                            return false;
                        }
                    })
                    .toList();
            log.info("{} subscriber(s) due for delivery at their local 9am", subscribers.size());
        }

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

        PrioritisedJobScheduler scheduler = new PrioritisedJobScheduler(4);
        ContextBundleBuilder bundleBuilder = new ContextBundleBuilder(repository);
        BoundedWorkerPool bundlePool = new BoundedWorkerPool(4, 32);
        FanOutQueue<Subscriber, ContextBundle> bundleFanOut = new FanOutQueue<>(bundlePool);

        List<ContextBundle> bundles = bundleFanOut.process(subscribers, subscriber -> {
            PrioritisedJobScheduler.Priority priority =
                    subscribedTeams.contains(subscriber.followedTeam())
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
            return;
        }

        // Single worker — serialises Gemini calls to avoid free-tier 429s
        BoundedWorkerPool composerPool = new BoundedWorkerPool(1, 16);
        FanOutQueue<ContextBundle, ComposedEmail> composeFanOut = new FanOutQueue<>(composerPool);

        // Map bundle → composed email, keyed by subscriber email for delivery
        List<ComposedEmail> emails = composeFanOut.process(bundles, bundle -> {
            try {
                return composer.compose(bundle);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        composerPool.shutdown();
        composerPool.awaitTermination(30, TimeUnit.SECONDS);

        if (!composeFanOut.deadLetters().isEmpty()) {
            log.error("Composition failed for {} subscriber(s): {}",
                    composeFanOut.deadLetters().size(), composeFanOut.deadLetters());
        }

        // --- Delivery ---
        String smtpHost = EnvLoader.getRequired("SMTP_HOST");
        int smtpPort    = Integer.parseInt(EnvLoader.getRequired("SMTP_PORT"));
        String smtpUser = EnvLoader.getRequired("SMTP_USERNAME");
        String smtpPass = EnvLoader.getRequired("SMTP_PASSWORD");
        EmailSender sender = new EmailSender(smtpHost, smtpPort, smtpUser, smtpPass);

        // Build a lookup of subscriber email → composed email.
        // FanOutQueue preserves input order for successes, so we pair by iterating
        // both lists; the composed email count may be less than bundles if some failed.
        Set<String> deadLetterEmails = composeFanOut.deadLetters().stream()
                .map(b -> b.subscriber().email())
                .collect(Collectors.toSet());
        List<ContextBundle> succeededBundles = bundles.stream()
                .filter(b -> !deadLetterEmails.contains(b.subscriber().email()))
                .toList();
        Map<String, ComposedEmail> emailBySubscriber = new java.util.LinkedHashMap<>();
        for (int i = 0; i < succeededBundles.size() && i < emails.size(); i++) {
            emailBySubscriber.put(succeededBundles.get(i).subscriber().email(), emails.get(i));
        }

        BoundedWorkerPool deliveryPool = new BoundedWorkerPool(2, 16);
        FanOutQueue<Map.Entry<String, ComposedEmail>, Void> deliveryFanOut = new FanOutQueue<>(deliveryPool);

        deliveryFanOut.process(new java.util.ArrayList<>(emailBySubscriber.entrySet()), entry -> {
            try {
                sender.send(entry.getKey(), entry.getValue());
                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to send to " + entry.getKey(), e);
            }
        });

        deliveryPool.shutdown();
        deliveryPool.awaitTermination(30, TimeUnit.SECONDS);

        if (!deliveryFanOut.deadLetters().isEmpty()) {
            log.error("Delivery failed for {} subscriber(s): {}",
                    deliveryFanOut.deadLetters().size(), deliveryFanOut.deadLetters());
        }

        log.info("Pipeline run complete — {} email(s) sent", emailBySubscriber.size());
    }
}
