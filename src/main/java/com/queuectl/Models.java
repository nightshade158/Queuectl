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
        public int priority = 0;
        public String run_at = nowIso();
        public int timeout_seconds = 0; // 0 => no timeout / use default
        public Integer last_exit_code = null;
        public Long last_duration_ms = null;
        public String last_output_path = null;
        public Integer run_count = 0;
        public Integer success_count = 0;
        public Integer failure_count = 0;
        public Long total_runtime_ms = 0L;
        public String last_finished_at = null;
        public String created_at = nowIso();
        public String updated_at = created_at;

        public Job() {}

        public Job(String id, String command) {
            this.id = id;
            this.command = command;
            this.run_at = nowIso();
        }
    }
}


