package com.muji.worldcup.model;

import java.util.List;

public record ContextBundle(
        Subscriber subscriber,
        String matchDayRecapText,
        String teamUpdate,
        String playerUpdate,
        String nextMatchDayPreview,
        EliminationStatus eliminationStatus,
        List<GroupStanding> groupTable,
        Match nextMatch,
        Match lastResult   // most recent FINISHED match for the followed team (may be null)
) {
    public enum EliminationStatus {
        ACTIVE,
        JUST_ELIMINATED,
        ALREADY_HANDLED
    }
}
