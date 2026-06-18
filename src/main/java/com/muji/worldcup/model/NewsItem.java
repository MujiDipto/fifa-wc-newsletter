package com.muji.worldcup.model;

import java.time.Instant;

public record NewsItem(
        String id,
        String headline,
        String description,
        Instant publishedAt,
        String relatedTeam
) {}
