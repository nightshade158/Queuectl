package com.queuectl;

import com.queuectl.Models.Job;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                    "priority INTEGER DEFAULT 0, " +
                    "run_at TEXT, " +
                    "timeout_seconds INTEGER DEFAULT 0, " +
                    "last_exit_code INTEGER, " +
                    "last_duration_ms INTEGER, " +
                    "last_output_path TEXT, " +
                    "run_count INTEGER DEFAULT 0, " +
                    "success_count INTEGER DEFAULT 0, " +
                    "failure_count INTEGER DEFAULT 0, " +
                    "total_runtime_ms INTEGER DEFAULT 0, " +
                    "last_finished_at TEXT, " +
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
                    "priority INTEGER DEFAULT 0, " +
                    "run_at TEXT, " +
                    "timeout_seconds INTEGER DEFAULT 0, " +
                    "last_exit_code INTEGER, " +
                    "last_duration_ms INTEGER, " +
                    "last_output_path TEXT, " +
                    "run_count INTEGER DEFAULT 0, " +
                    "success_count INTEGER DEFAULT 0, " +
                    "failure_count INTEGER DEFAULT 0, " +
                    "total_runtime_ms INTEGER DEFAULT 0, " +
                    "last_finished_at TEXT, " +
                    "created_at TEXT, " +
                    "updated_at TEXT)"
            );
            ensureColumn(c, "jobs", "priority", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "run_at", "TEXT");
            ensureColumn(c, "jobs", "timeout_seconds", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "last_exit_code", "INTEGER");
            ensureColumn(c, "jobs", "last_duration_ms", "INTEGER");
            ensureColumn(c, "jobs", "last_output_path", "TEXT");
            ensureColumn(c, "jobs", "run_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "success_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "failure_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "total_runtime_ms", "INTEGER DEFAULT 0");
            ensureColumn(c, "jobs", "last_finished_at", "TEXT");
            ensureColumn(c, "dead_letter_jobs", "priority", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "run_at", "TEXT");
            ensureColumn(c, "dead_letter_jobs", "timeout_seconds", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "last_exit_code", "INTEGER");
            ensureColumn(c, "dead_letter_jobs", "last_duration_ms", "INTEGER");
            ensureColumn(c, "dead_letter_jobs", "last_output_path", "TEXT");
            ensureColumn(c, "dead_letter_jobs", "run_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "success_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "failure_count", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "total_runtime_ms", "INTEGER DEFAULT 0");
            ensureColumn(c, "dead_letter_jobs", "last_finished_at", "TEXT");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void ensureColumn(Connection c, String table, String column, String definition) throws SQLException {
        boolean exists = false;
        try (Statement check = c.createStatement(); ResultSet rs = check.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement alter = c.createStatement()) {
                alter.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    public static void upsert(Job j) {
        init();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO jobs (id, command, state, attempts, max_retries, priority, run_at, timeout_seconds, last_exit_code, last_duration_ms, last_output_path, run_count, success_count, failure_count, total_runtime_ms, last_finished_at, created_at, updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "command=excluded.command, " +
                "state=excluded.state, " +
                "attempts=excluded.attempts, " +
                "max_retries=excluded.max_retries, " +
                "priority=excluded.priority, " +
                "run_at=excluded.run_at, " +
                "timeout_seconds=excluded.timeout_seconds, " +
                "created_at=excluded.created_at, " +
                "updated_at=excluded.updated_at"
        )) {
            ps.setString(1, j.id);
            ps.setString(2, j.command);
            ps.setString(3, j.state);
            ps.setInt(4, j.attempts);
            ps.setInt(5, j.max_retries);
            ps.setInt(6, j.priority);
            String runAt = j.run_at != null ? j.run_at : Models.nowIso();
            ps.setString(7, runAt);
            ps.setInt(8, j.timeout_seconds);
            if (j.last_exit_code != null) ps.setInt(9, j.last_exit_code); else ps.setNull(9, Types.INTEGER);
            if (j.last_duration_ms != null) ps.setLong(10, j.last_duration_ms); else ps.setNull(10, Types.BIGINT);
            if (j.last_output_path != null) ps.setString(11, j.last_output_path); else ps.setNull(11, Types.VARCHAR);
            ps.setInt(12, j.run_count != null ? j.run_count : 0);
            ps.setInt(13, j.success_count != null ? j.success_count : 0);
            ps.setInt(14, j.failure_count != null ? j.failure_count : 0);
            ps.setLong(15, j.total_runtime_ms != null ? j.total_runtime_ms : 0L);
            if (j.last_finished_at != null) ps.setString(16, j.last_finished_at); else ps.setNull(16, Types.VARCHAR);
            ps.setString(17, j.created_at);
            ps.setString(18, j.updated_at);
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
                String now = Models.nowIso();
                String id = null;
                try (PreparedStatement sel = c.prepareStatement("SELECT id FROM jobs WHERE state='pending' AND (run_at IS NULL OR run_at <= ?) ORDER BY priority DESC, run_at ASC, created_at ASC LIMIT 1")) {
                    sel.setString(1, now);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) return null;
                        id = rs.getString(1);
                    }
                }
                int updated;
                try (PreparedStatement upd = c.prepareStatement("UPDATE jobs SET state='processing', updated_at=? WHERE id IN (SELECT id FROM jobs WHERE id=? AND state='pending')")) {
                    upd.setString(1, now);
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

    public static void markJobSuccess(String id, int attempts, int exitCode, long durationMs, String outputPath) {
        init();
        String now = Models.nowIso();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
            "UPDATE jobs SET state='completed', attempts=?, last_exit_code=?, last_duration_ms=?, last_output_path=?, " +
                "run_count=COALESCE(run_count,0)+1, success_count=COALESCE(success_count,0)+1, total_runtime_ms=COALESCE(total_runtime_ms,0)+?, " +
                "last_finished_at=?, updated_at=? WHERE id=?"
        )) {
            ps.setInt(1, attempts);
            ps.setInt(2, exitCode);
            ps.setLong(3, durationMs);
            if (outputPath != null) ps.setString(4, outputPath); else ps.setNull(4, Types.VARCHAR);
            ps.setLong(5, durationMs);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.setString(8, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void markJobFailure(String id, int attempts, int exitCode, long durationMs, String outputPath, boolean willRetry) {
        init();
        String now = Models.nowIso();
        String newState = willRetry ? "failed" : "dead";
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement(
            "UPDATE jobs SET state=?, attempts=?, last_exit_code=?, last_duration_ms=?, last_output_path=?, " +
                "run_count=COALESCE(run_count,0)+1, failure_count=COALESCE(failure_count,0)+1, total_runtime_ms=COALESCE(total_runtime_ms,0)+?, " +
                "last_finished_at=?, updated_at=? WHERE id=?"
        )) {
            ps.setString(1, newState);
            ps.setInt(2, attempts);
            ps.setInt(3, exitCode);
            ps.setLong(4, durationMs);
            if (outputPath != null) ps.setString(5, outputPath); else ps.setNull(5, Types.VARCHAR);
            ps.setLong(6, durationMs);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setString(9, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void scheduleRetry(String id, String nextRunAt) {
        init();
        String now = Models.nowIso();
        try (Connection c = getConn(); PreparedStatement ps = c.prepareStatement("UPDATE jobs SET state='pending', run_at=?, updated_at=? WHERE id=?")) {
            ps.setString(1, nextRunAt);
            ps.setString(2, now);
            ps.setString(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void moveToDlq(String id) {
        init();
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
                try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO dead_letter_jobs (id, command, state, attempts, max_retries, priority, run_at, timeout_seconds, last_exit_code, last_duration_ms, last_output_path, run_count, success_count, failure_count, total_runtime_ms, last_finished_at, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET command=excluded.command, state=excluded.state, attempts=excluded.attempts, max_retries=excluded.max_retries, priority=excluded.priority, run_at=excluded.run_at, timeout_seconds=excluded.timeout_seconds, last_exit_code=excluded.last_exit_code, last_duration_ms=excluded.last_duration_ms, last_output_path=excluded.last_output_path, run_count=excluded.run_count, success_count=excluded.success_count, failure_count=excluded.failure_count, total_runtime_ms=excluded.total_runtime_ms, last_finished_at=excluded.last_finished_at, created_at=excluded.created_at, updated_at=excluded.updated_at"
                )) {
                    bindJobParams(ins, job);
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
        String sql = state == null ? "SELECT * FROM jobs ORDER BY priority DESC, run_at ASC, created_at ASC" : "SELECT * FROM jobs WHERE state=? ORDER BY priority DESC, run_at ASC, created_at ASC";
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
            job.run_at = Models.nowIso();
            try (PreparedStatement up = c.prepareStatement(
                "INSERT INTO jobs (id, command, state, attempts, max_retries, priority, run_at, timeout_seconds, last_exit_code, last_duration_ms, last_output_path, run_count, success_count, failure_count, total_runtime_ms, last_finished_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET command=excluded.command, state=excluded.state, attempts=excluded.attempts, max_retries=excluded.max_retries, priority=excluded.priority, run_at=excluded.run_at, timeout_seconds=excluded.timeout_seconds, created_at=excluded.created_at, updated_at=excluded.updated_at"
            )) {
                bindJobParams(up, job);
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

    private static void bindJobParams(PreparedStatement ps, Models.Job job) throws SQLException {
        ps.setString(1, job.id);
        ps.setString(2, job.command);
        ps.setString(3, job.state);
        ps.setInt(4, job.attempts);
        ps.setInt(5, job.max_retries);
        ps.setInt(6, job.priority);
        ps.setString(7, job.run_at);
        ps.setInt(8, job.timeout_seconds);
        if (job.last_exit_code != null) ps.setInt(9, job.last_exit_code); else ps.setNull(9, Types.INTEGER);
        if (job.last_duration_ms != null) ps.setLong(10, job.last_duration_ms); else ps.setNull(10, Types.BIGINT);
        if (job.last_output_path != null) ps.setString(11, job.last_output_path); else ps.setNull(11, Types.VARCHAR);
        ps.setInt(12, job.run_count != null ? job.run_count : 0);
        ps.setInt(13, job.success_count != null ? job.success_count : 0);
        ps.setInt(14, job.failure_count != null ? job.failure_count : 0);
        ps.setLong(15, job.total_runtime_ms != null ? job.total_runtime_ms : 0L);
        if (job.last_finished_at != null) ps.setString(16, job.last_finished_at); else ps.setNull(16, Types.VARCHAR);
        ps.setString(17, job.created_at);
        ps.setString(18, job.updated_at != null ? job.updated_at : Models.nowIso());
    }

    public static Counts counts() {
        init();
        Counts cts = new Counts();
        String[] states = new String[]{"pending","processing","completed","failed","dead"};
        for (String s : states) cts.set(s, 0);
        try (Connection c = getConn()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT state, COUNT(1) as c FROM jobs GROUP BY state"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cts.set(rs.getString(1), rs.getInt(2));
                }
            }
            try (PreparedStatement dlqCount = c.prepareStatement("SELECT COUNT(1) FROM dead_letter_jobs"); ResultSet rs = dlqCount.executeQuery()) {
                if (rs.next()) {
                    cts.dead += rs.getInt(1);
                }
            }
            accumulateMetrics(cts, c, "jobs");
            accumulateMetrics(cts, c, "dead_letter_jobs");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        if (cts.success_count > 0) {
            cts.average_duration_ms = (double) cts.total_runtime_ms / (double) cts.success_count;
        }
        return cts;
    }

    private static void accumulateMetrics(Counts counts, Connection c, String table) throws SQLException {
        String sql = "SELECT SUM(run_count), SUM(success_count), SUM(failure_count), SUM(total_runtime_ms), MAX(last_finished_at) FROM " + table;
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                counts.run_count += safeLong(rs, 1);
                counts.success_count += safeLong(rs, 2);
                counts.failure_count += safeLong(rs, 3);
                counts.total_runtime_ms += safeLong(rs, 4);
                String last = rs.getString(5);
                if (last != null) {
                    if (counts.last_finished_at == null || last.compareTo(counts.last_finished_at) > 0) {
                        counts.last_finished_at = last;
                    }
                }
            }
        }
    }

    private static long safeLong(ResultSet rs, int idx) throws SQLException {
        long value = rs.getLong(idx);
        return rs.wasNull() ? 0L : value;
    }

    public static String loadJobLog(String jobId) {
        init();
        try (Connection c = getConn()) {
            String path = findOutputPath(c, "jobs", jobId);
            if (path == null) {
                path = findOutputPath(c, "dead_letter_jobs", jobId);
            }
            if (path == null || path.isBlank()) return null;
            Path p = Paths.get(path);
            if (!p.isAbsolute()) {
                p = new File(path).getAbsoluteFile().toPath();
            }
            if (!Files.exists(p)) return "Log file missing at: " + p;
            return Files.readString(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String findOutputPath(Connection c, String table, String jobId) throws SQLException {
        String sql = "SELECT last_output_path FROM " + table + " WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    public static Models.Job getJob(String jobId) {
        init();
        try (Connection c = getConn()) {
            Models.Job job = fetchJobFromTable(c, "jobs", jobId);
            if (job != null) return job;
            return fetchJobFromTable(c, "dead_letter_jobs", jobId);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Models.Job fetchJobFromTable(Connection c, String table, String jobId) throws SQLException {
        String sql = "SELECT * FROM " + table + " WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return map(rs);
            }
        }
        return null;
    }

    private static Models.Job map(ResultSet r) throws SQLException {
        Models.Job j = new Models.Job();
        j.id = r.getString("id");
        j.command = r.getString("command");
        j.state = r.getString("state");
        j.attempts = r.getInt("attempts");
        j.max_retries = r.getInt("max_retries");
        j.priority = r.getInt("priority");
        j.run_at = r.getString("run_at") != null ? r.getString("run_at") : j.run_at;
        j.timeout_seconds = r.getInt("timeout_seconds");
        if (r.wasNull()) j.timeout_seconds = 0;
        Object exit = r.getObject("last_exit_code");
        if (exit != null) j.last_exit_code = ((Number) exit).intValue();
        long duration = r.getLong("last_duration_ms");
        if (!r.wasNull()) j.last_duration_ms = duration;
        String logPath = r.getString("last_output_path");
        if (logPath != null) j.last_output_path = logPath;
        j.run_count = r.getInt("run_count");
        if (r.wasNull()) j.run_count = 0;
        j.success_count = r.getInt("success_count");
        if (r.wasNull()) j.success_count = 0;
        j.failure_count = r.getInt("failure_count");
        if (r.wasNull()) j.failure_count = 0;
        long totalRuntime = r.getLong("total_runtime_ms");
        if (!r.wasNull()) j.total_runtime_ms = totalRuntime;
        String lastFinished = r.getString("last_finished_at");
        if (lastFinished != null) j.last_finished_at = lastFinished;
        j.created_at = r.getString("created_at");
        j.updated_at = r.getString("updated_at");
        return j;
    }

    public static class Counts {
        public int pending; public int processing; public int completed; public int failed; public int dead; public int active_workers;
        public long run_count; public long success_count; public long failure_count; public long total_runtime_ms;
        public Double average_duration_ms;
        public String last_finished_at;

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


