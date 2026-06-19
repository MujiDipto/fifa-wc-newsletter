package com.muji.worldcup.model;

public record Subscriber(String email, String followedTeam, String timezone) {

    public Subscriber(String email, String followedTeam) {
        this(email, followedTeam, "UTC");
    }
}
