package com.queuectl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queuectl.Models.Job;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
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

        try {
            while (!shouldStop.get()) {
                if (STOP_FILE.exists()) shouldStop.set(true);
                Job job = Storage.fetchAndLockNextPending();
                if (job == null) {
                    touchPid();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    continue;
                }
                int exit = execute(job.command);
                if (exit == 0) {
                    Storage.setCompleted(job.id);
                } else {
                    int attempts = job.attempts + 1;
                    if (attempts <= job.max_retries) {
                        Storage.setFailed(job.id, attempts, "failed");
                        long delay = (long) Math.pow(backoffBase, attempts) * 1000L;
                        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
                        Storage.setFailed(job.id, attempts, "pending");
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

    private int execute(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
            pb.redirectOutput(Redirect.INHERIT).redirectError(Redirect.INHERIT);
            Process p = pb.start();
            return p.waitFor();
        } catch (Exception e) {
            return 127;
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


