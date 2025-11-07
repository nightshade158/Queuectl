package com.queuectl;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

public class Models {
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    public static String nowIso() {
        return ISO.format(Instant.now());
    }

    public static class Job {
        public String id;
        public String command;
        public String state = "pending"; // pending, processing, completed, failed, dead
        public int attempts = 0;
        public int max_retries = 3;
        public String created_at = nowIso();
        public String updated_at = created_at;

        public Job() {}

        public Job(String id, String command) {
            this.id = id;
            this.command = command;
        }
    }
}


