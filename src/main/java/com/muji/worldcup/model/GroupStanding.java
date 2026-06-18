package com.muji.worldcup.model;

public record GroupStanding(
        String group,
        String teamName,
        int played,
        int won,
        int drawn,
        int lost,
        int goalDifference,
        int points,
        int position
) {}
