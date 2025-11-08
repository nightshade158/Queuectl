package com.queuectl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queuectl.Models.Job;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Worker {
    public static final File RUNTIME_DIR = new File("queuectl_runtime");
    public static final File STOP_FILE = new File(RUNTIME_DIR, "STOP");

    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    private File pidFile;

    public void run() {
        if (!RUNTIME_DIR.exists()) RUNTIME_DIR.mkdirs();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shouldStop.set(true)));
        pidFile = new File(RUNTIME_DIR, "worker-" + ProcessHandle.current().pid() + ".pid");
        try (FileWriter fw = new FileWriter(pidFile)) { fw.write(Long.toString(System.currentTimeMillis())); } catch (IOException ignored) {}

        ObjectNode cfg = Config.load();
        int backoffBase = cfg.get("backoff_base").asInt(2);
        int defaultTimeout = cfg.get("default_timeout_seconds").asInt(0);
        String logDirName = cfg.get("log_directory").asText("job_logs");
        File logDir = new File(logDirName);
        if (!logDir.exists()) logDir.mkdirs();

        try {
            while (!shouldStop.get()) {
                if (STOP_FILE.exists()) shouldStop.set(true);
                Job job = Storage.fetchAndLockNextPending();
                if (job == null) {
                    touchPid();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    continue;
                }
                int attemptNumber = job.attempts + 1;
                int timeoutSeconds = job.timeout_seconds > 0 ? job.timeout_seconds : defaultTimeout;
                ExecutionResult result = execute(job, attemptNumber, timeoutSeconds, logDir);
                if (result.exitCode == 0) {
                    Storage.markJobSuccess(job.id, attemptNumber, result.exitCode, result.durationMs, result.logPath);
                } else {
                    boolean willRetry = attemptNumber < job.max_retries;
                    Storage.markJobFailure(job.id, attemptNumber, result.exitCode, result.durationMs, result.logPath, willRetry);
                    if (willRetry) {
                        long delaySeconds = Math.max(1L, Math.round(Math.pow(backoffBase, attemptNumber)));
                        Instant nextRun = Instant.now().plus(delaySeconds, ChronoUnit.SECONDS);
                        Storage.scheduleRetry(job.id, Models.ISO.format(nextRun));
                    } else {
                        Storage.moveToDlq(job.id);
                    }
                }
                touchPid();
            }
        } finally {
            if (pidFile != null && pidFile.exists()) pidFile.delete();
        }
    }

    private void touchPid() {
        try (FileWriter fw = new FileWriter(pidFile)) { fw.write(Long.toString(System.currentTimeMillis())); } catch (IOException ignored) {}
    }

    private ExecutionResult execute(Job job, int attemptNumber, int timeoutSeconds, File logDir) {
        String safeId = job.id.replaceAll("[^a-zA-Z0-9_.-]", "_");
        String logFileName = safeId + "-attempt-" + attemptNumber + "-" + System.currentTimeMillis() + ".log";
        File logFile = new File(logDir, logFileName);
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", job.command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile));
        long start = System.currentTimeMillis();
        int exitCode = 127;
        boolean timedOut = false;
        try {
            Process process = pb.start();
            if (timeoutSeconds > 0) {
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    timedOut = true;
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    exitCode = 124;
                } else {
                    exitCode = process.exitValue();
                }
            } else {
                exitCode = process.waitFor();
            }
        } catch (Exception e) {
            exitCode = 127;
        }
        long duration = System.currentTimeMillis() - start;
        return new ExecutionResult(exitCode, duration, timedOut, logFile.getAbsolutePath());
    }

    private static class ExecutionResult {
        final int exitCode;
        final long durationMs;
        final String logPath;

        ExecutionResult(int exitCode, long durationMs, boolean timedOut, String logPath) {
            this.exitCode = exitCode;
            this.durationMs = durationMs;
            this.logPath = logPath;
        }
    }

    public static int activeWorkers() {
        if (!RUNTIME_DIR.exists()) return 0;
        File[] files = RUNTIME_DIR.listFiles((dir, name) -> name.startsWith("worker-") && name.endsWith(".pid"));
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            String base = f.getName().substring("worker-".length(), f.getName().length() - ".pid".length());
            try {
                long pid = Long.parseLong(base);
                if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent()) count++; else f.delete();
            } catch (NumberFormatException ignored) {}
        }
        return count;
    }
}


