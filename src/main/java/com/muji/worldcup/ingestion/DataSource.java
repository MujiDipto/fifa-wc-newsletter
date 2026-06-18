package com.muji.worldcup.ingestion;

/**
 * Marker interface for external data sources. Each implementation owns its
 * own HTTP client, rate limiter, and normalization logic.
 */
public interface DataSource {
    String name();
}
