package com.muji.worldcup.model;

import java.util.List;

public record ContextBundle(
        Subscriber subscriber,
        String matchDayRecapText,
        String teamUpdate,
        String nextMatchDayPreview,
        EliminationStatus eliminationStatus,
        List<GroupStanding> groupTable,
        Match nextMatch,
        Match lastResult,
        String tournamentStage
) {
    public enum EliminationStatus {
        ACTIVE,
        JUST_ELIMINATED,
        ALREADY_HANDLED
    }

    public boolean isKnockoutStage() {
        return tournamentStage != null && !tournamentStage.equals("GROUP_STAGE");
    }

    public String stageLabel() {
        if (tournamentStage == null) return "Group Stage";
        return switch (tournamentStage) {
            case "LAST_32"        -> "Round of 32";
            case "LAST_16"        -> "Round of 16";
            case "QUARTER_FINALS" -> "Quarter-Finals";
            case "SEMI_FINALS"    -> "Semi-Finals";
            case "THIRD_PLACE"    -> "Third Place Play-off";
            case "FINAL"          -> "Final";
            default               -> "Group Stage";
        };
    }
}
