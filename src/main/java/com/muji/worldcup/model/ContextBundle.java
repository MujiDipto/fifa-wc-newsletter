package com.muji.worldcup.model;

public record ContextBundle(
        Subscriber subscriber,
        String matchDayRecapText,
        String teamUpdate,
        String playerUpdate,
        String nextMatchDayPreview,
        EliminationStatus eliminationStatus
) {
    public enum EliminationStatus {
        ACTIVE,
        JUST_ELIMINATED,
        ALREADY_HANDLED
    }
}
