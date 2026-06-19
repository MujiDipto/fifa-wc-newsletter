package com.muji.worldcup.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final String jdbcUrl;

    public Database(String dbPath) {
        // Allow passing a full JDBC URL directly (e.g. for named in-memory test DBs)
        this.jdbcUrl = dbPath.startsWith("jdbc:") ? dbPath : "jdbc:sqlite:" + dbPath;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    public void initSchema() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS matches (
                    id          TEXT PRIMARY KEY,
                    home_team   TEXT NOT NULL,
                    away_team   TEXT NOT NULL,
                    home_score  INTEGER,
                    away_score  INTEGER,
                    status      TEXT,
                    group_name  TEXT,
                    kickoff_time TEXT,
                    source      TEXT,
                    fetched_at  TEXT NOT NULL
                )
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS group_standings (
                    group_name       TEXT NOT NULL,
                    team_name        TEXT NOT NULL,
                    played           INTEGER,
                    won              INTEGER,
                    drawn            INTEGER,
                    lost             INTEGER,
                    goal_difference  INTEGER,
                    points           INTEGER,
                    position         INTEGER,
                    fetched_at       TEXT NOT NULL,
                    PRIMARY KEY (group_name, team_name)
                )
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS players (
                    name                TEXT NOT NULL,
                    team                TEXT NOT NULL,
                    goals               INTEGER,
                    assists             INTEGER,
                    appearances         INTEGER,
                    last_match_summary  TEXT,
                    position            TEXT,
                    nationality         TEXT,
                    bio                 TEXT,
                    source              TEXT,
                    fetched_at          TEXT NOT NULL,
                    PRIMARY KEY (name, team)
                )
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS news (
                    id           TEXT PRIMARY KEY,
                    headline     TEXT NOT NULL,
                    description  TEXT,
                    published_at TEXT,
                    related_team TEXT,
                    fetched_at   TEXT NOT NULL
                )
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS subscribers (
                    email           TEXT PRIMARY KEY,
                    followed_team   TEXT,
                    followed_player TEXT,
                    timezone        TEXT NOT NULL DEFAULT 'UTC',
                    created_at      TEXT NOT NULL,
                    active          INTEGER NOT NULL DEFAULT 1
                )
                """);

            // Add bio columns to existing installs that predate this schema version
            tryAddColumn(stmt, "players", "position", "TEXT");
            tryAddColumn(stmt, "players", "nationality", "TEXT");
            tryAddColumn(stmt, "players", "bio", "TEXT");

            log.info("SQLite schema initialised at {}", jdbcUrl);
        }
    }

    // ALTER TABLE ADD COLUMN is idempotent via try/catch — SQLite has no IF NOT EXISTS for columns
    private void tryAddColumn(Statement stmt, String table, String column, String type) {
        try {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
            // Column already exists — safe to ignore
        }
    }
}
