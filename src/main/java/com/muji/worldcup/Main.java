package com.muji.worldcup;

import com.muji.worldcup.config.EnvLoader;
import com.muji.worldcup.config.SubscriberConfigLoader;
import com.muji.worldcup.concurrency.BoundedWorkerPool;
import com.muji.worldcup.ingestion.EspnClient;
import com.muji.worldcup.ingestion.FootballDataClient;
import com.muji.worldcup.ingestion.IngestionService;
import com.muji.worldcup.ingestion.TheSportsDbClient;
import com.muji.worldcup.model.Subscriber;
import com.muji.worldcup.persistence.Database;
import com.muji.worldcup.persistence.SqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

        BoundedWorkerPool pool = new BoundedWorkerPool(4, 16);

        FootballDataClient footballDataClient = new FootballDataClient(
                EnvLoader.getRequired("FOOTBALL_DATA_API_TOKEN"));

        EspnClient espnClient = new EspnClient();
        TheSportsDbClient theSportsDbClient = new TheSportsDbClient();

        IngestionService ingestion = new IngestionService(
                pool, footballDataClient, espnClient, theSportsDbClient, repository);

        ingestion.ingest(LocalDate.now());

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        log.info("Pipeline run complete");
    }
}
