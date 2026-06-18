package com.muji.worldcup.model;

public record Player(
        String name,
        String team,
        int goals,
        int assists,
        int appearances,
        String lastMatchSummary,
        String position,
        String nationality,
        String bio
) {}
