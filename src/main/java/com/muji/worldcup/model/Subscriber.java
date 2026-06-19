package com.muji.worldcup.model;

public record Subscriber(String email, String followedTeam, String followedPlayer, String timezone) {

    public Subscriber(String email, String followedTeam, String followedPlayer) {
        this(email, followedTeam, followedPlayer, "UTC");
    }
}
