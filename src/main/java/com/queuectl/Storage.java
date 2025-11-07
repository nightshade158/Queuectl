package com.queuectl;

import com.queuectl.Models.Job;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Storage {
    private static final String DB_URL = "jdbc:sqlite:queue.db?busy_timeout=5000";

    public static Connection getConn() throws SQLException {
        Connection c = DriverManager.getConnection(DB_URL);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA foreign_keys=ON;");
            s.execute("PRAGMA busy_timeout=5000;");
        }
        return c;
    }

    public static void init() {
        try (Connection c = getConn(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS jobs (" +
                    "id TEXT PRIMARY KEY, " +
                    "command TEXT, " +
                    "state TEXT, " +
                    "attempts INTEGER, " +
                    "max_retries INTEGER, " +
                    "created_at TEXT, " +
                    "updated_at TEXT)"
            );
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS dead_letter_jobs (" +
                    "id TEXT PRIMARY KEY, " +
                    "command TEXT, " +
                    "state TEXT, " +
                    "attempts INTEGER, " +
                    "max_retries INTEGER, " +
                    "created_at TEXT, " +
                    "updated_at TEXT)"
            );
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void upsert(Job j) {
        init();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO jobs (id, command, state, attempts, max_retries, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET command=excluded.command, state=excluded.state, attempts=excluded.attempts, max_retries=excluded.max_retries, created_at=excluded.created_at, updated_at=excluded.updated_at"
        )) {
            ps.setString(1, j.id);
            ps.setString(2, j.command);
            ps.setString(3, j.state);
            ps.setInt(4, j.attempts);
            ps.setInt(5, j.max_retries);
            ps.setString(6, j.created_at);
            ps.setString(7, j.updated_at);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Models.Job fetchAndLockNextPending() {
        init();
        int attempts = 0;
        while (attempts < 20) { // retry up to ~2s total
            attempts++;
            try (Connection c = getConn()) {
                // Atomic update: pick the oldest pending id in a subquery
                String id = null;
                try (PreparedStatement sel = c.prepareStatement("SELECT id FROM jobs WHERE state='pending' ORDER BY created_at ASC LIMIT 1"); ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) return null;
                    id = rs.getString(1);
                }
                int updated;
                try (PreparedStatement upd = c.prepareStatement("UPDATE jobs SET state='processing', updated_at=? WHERE id IN (SELECT id FROM jobs WHERE id=? AND state='pending')")) {
                    upd.setString(1, Models.nowIso());
                    upd.setString(2, id);
                    updated = upd.executeUpdate();
                }
                if (updated != 1) {
                    // another worker grabbed it; retry quickly
                    sleepQuiet(50L);
                    continue;
                }
                try (PreparedStatement get = c.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
                    get.setString(1, id);
                    try (ResultSet jr = get.executeQuery()) {
                        if (jr.next()) return map(jr);
                        else return null;
                    }
                }
            } catch (SQLException e) {
                if (isBusy(e)) { sleepQuiet(100L); continue; }
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private static boolean isBusy(SQLException e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("database is locked") || msg.contains("SQLITE_BUSY"));
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public static void setCompleted(String id) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement("UPDATE jobs SET state='completed', updated_at=? WHERE id=?")) {
            ps.setString(1, Models.nowIso());
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFailed(String id, int attempts, String state) {
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement("UPDATE jobs SET state=?, attempts=?, updated_at=? WHERE id=?")) {
            ps.setString(1, state);
            ps.setInt(2, attempts);
            ps.setString(3, Models.nowIso());
            ps.setString(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void moveToDlq(String id) {
        try (Connection c = getConn()) {
            c.setAutoCommit(false);
            Models.Job job = null;
            try (PreparedStatement get = c.prepareStatement("SELECT * FROM jobs WHERE id=?")) {
                get.setString(1, id);
                try (ResultSet rs = get.executeQuery()) {
                    if (rs.next()) job = map(rs);
                }
            }
            if (job != null) {
                job.state = "dead";
                job.updated_at = Models.nowIso();
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO dead_letter_jobs (id, command, state, attempts, max_retries, created_at, updated_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET command=excluded.command, state=excluded.state, attempts=excluded.attempts, max_retries=excluded.max_retries, created_at=excluded.created_at, updated_at=excluded.updated_at")) {
                    ins.setString(1, job.id);
                    ins.setString(2, job.command);
                    ins.setString(3, job.state);
                    ins.setInt(4, job.attempts);
                    ins.setInt(5, job.max_retries);
                    ins.setString(6, job.created_at);
                    ins.setString(7, job.updated_at);
                    ins.executeUpdate();
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM jobs WHERE id=?")) {
                    del.setString(1, id);
                    del.executeUpdate();
                }
            }
            c.commit();
            c.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Models.Job> listJobs(String state) {
        init();
        String sql = state == null ? "SELECT * FROM jobs ORDER BY created_at ASC" : "SELECT * FROM jobs WHERE state=? ORDER BY created_at ASC";
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (state != null) ps.setString(1, state);
            try (ResultSet rs = ps.executeQuery()) {
                List<Models.Job> out = new ArrayList<>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Models.Job> listDlq() {
        init();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement("SELECT * FROM dead_letter_jobs ORDER BY created_at ASC"); ResultSet rs = ps.executeQuery()) {
            List<Models.Job> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean retryFromDlq(String id) {
        init();
        try (Connection c = getConn()) {
            c.setAutoCommit(false);
            Models.Job job = null;
            try (PreparedStatement get = c.prepareStatement("SELECT * FROM dead_letter_jobs WHERE id=?")) {
                get.setString(1, id);
                try (ResultSet rs = get.executeQuery()) {
                    if (rs.next()) job = map(rs);
                }
            }
            if (job == null) { c.rollback(); return false; }
            job.state = "pending";
            job.attempts = 0;
            job.updated_at = Models.nowIso();
            try (PreparedStatement up = c.prepareStatement("INSERT INTO jobs (id, command, state, attempts, max_retries, created_at, updated_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET command=excluded.command, state=excluded.state, attempts=excluded.attempts, max_retries=excluded.max_retries, created_at=excluded.created_at, updated_at=excluded.updated_at")) {
                up.setString(1, job.id);
                up.setString(2, job.command);
                up.setString(3, job.state);
                up.setInt(4, job.attempts);
                up.setInt(5, job.max_retries);
                up.setString(6, job.created_at);
                up.setString(7, job.updated_at);
                up.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM dead_letter_jobs WHERE id=?")) {
                del.setString(1, id);
                del.executeUpdate();
            }
            c.commit();
            c.setAutoCommit(true);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Counts counts() {
        init();
        Counts cts = new Counts();
        String[] states = new String[]{"pending","processing","completed","failed","dead"};
        for (String s : states) cts.set(s, 0);
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement("SELECT state, COUNT(1) as c FROM jobs GROUP BY state"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                cts.set(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return cts;
    }

    private static Models.Job map(ResultSet r) throws SQLException {
        Models.Job j = new Models.Job();
        j.id = r.getString("id");
        j.command = r.getString("command");
        j.state = r.getString("state");
        j.attempts = r.getInt("attempts");
        j.max_retries = r.getInt("max_retries");
        j.created_at = r.getString("created_at");
        j.updated_at = r.getString("updated_at");
        return j;
    }

    public static class Counts {
        public int pending; public int processing; public int completed; public int failed; public int dead; public int active_workers;
        public void set(String state, int v) {
            switch (state) {
                case "pending" -> pending = v;
                case "processing" -> processing = v;
                case "completed" -> completed = v;
                case "failed" -> failed = v;
                case "dead" -> dead = v;
            }
        }
    }
}


