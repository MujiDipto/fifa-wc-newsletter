package com.muji.worldcup;

import com.muji.worldcup.config.EnvLoader;
import com.muji.worldcup.config.SubscriberConfigLoader;
import com.muji.worldcup.model.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        EnvLoader.load(Path.of(".env"));

        List<Subscriber> subscribers = new SubscriberConfigLoader()
                .load(Path.of("config/subscribers.yaml"));

        log.info("Loaded {} subscriber(s)", subscribers.size());

        // Phases 1-6 will be wired in here.
    }
}
