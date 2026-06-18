package com.muji.worldcup.model;

import java.time.Instant;

public record Match(
        String id,
        String homeTeam,
        String awayTeam,
        Integer homeScore,
        Integer awayScore,
        String status,
        String group,
        Instant kickoffTime,
        String source
) {}
