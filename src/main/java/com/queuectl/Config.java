package com.queuectl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class Config {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final File CONFIG_FILE = new File("config.json");

    public static ObjectNode load() {
        ObjectNode defaults = MAPPER.createObjectNode();
        defaults.put("max_retries", 3);
        defaults.put("backoff_base", 2);
        defaults.put("default_timeout_seconds", 0);
        defaults.put("dashboard_port", 8080);
        defaults.put("log_directory", "job_logs");
        if (!CONFIG_FILE.exists()) {
            save(defaults);
            return defaults;
        }
        try {
            ObjectNode current = (ObjectNode) MAPPER.readTree(CONFIG_FILE);
        if (!current.has("max_retries")) current.put("max_retries", 3);
        if (!current.has("backoff_base")) current.put("backoff_base", 2);
        if (!current.has("default_timeout_seconds")) current.put("default_timeout_seconds", 0);
        if (!current.has("dashboard_port")) current.put("dashboard_port", 8080);
        if (!current.has("log_directory")) current.put("log_directory", "job_logs");
            return current;
        } catch (IOException e) {
            return defaults;
        }
    }

    public static void save(ObjectNode node) {
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE, node);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static ObjectNode set(String key, String value) {
        ObjectNode cfg = load();
        if (!Objects.equals(key, "max_retries") &&
            !Objects.equals(key, "backoff_base") &&
            !Objects.equals(key, "default_timeout_seconds") &&
            !Objects.equals(key, "dashboard_port") &&
            !Objects.equals(key, "log_directory")) {
            throw new IllegalArgumentException("Unknown config key: " + key);
        }
        try {
            int intVal = Integer.parseInt(value);
            cfg.put(key, intVal);
        } catch (NumberFormatException e) {
            cfg.put(key, value);
        }
        save(cfg);
        return cfg;
    }
}


