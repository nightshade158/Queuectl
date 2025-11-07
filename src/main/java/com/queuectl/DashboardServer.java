package com.queuectl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class DashboardServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final File PID_FILE = new File(Worker.RUNTIME_DIR, "dashboard.pid");

    private DashboardServer() {}

    public static File pidFile() {
        return PID_FILE;
    }

    public static void run(int port) {
        try {
            if (!Worker.RUNTIME_DIR.exists()) Worker.RUNTIME_DIR.mkdirs();
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> respond(exchange, 200, "text/html; charset=utf-8", DASHBOARD_HTML.getBytes(StandardCharsets.UTF_8)));
            server.createContext("/api/status", exchange -> json(exchange, new QueueManager().status()));
            server.createContext("/api/jobs", exchange -> json(exchange, new QueueManager().list(null)));
            server.createContext("/api/dlq", exchange -> json(exchange, Dlq.list()));
            server.createContext("/api/logs", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String jobId = Query.queryParam(query, "id");
                if (jobId == null || jobId.isBlank()) {
                    respond(exchange, 400, "text/plain", "Missing id parameter".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String log = Storage.loadJobLog(jobId);
                if (log == null) log = "No log available for job " + jobId;
                respond(exchange, 200, "text/plain; charset=utf-8", log.getBytes(StandardCharsets.UTF_8));
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            writePid();
            System.out.println("Dashboard running at http://localhost:" + port);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop(0);
                PID_FILE.delete();
            }));
            try {
                while (true) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            System.err.println("Dashboard failed: " + e.getMessage());
        }
    }

    private static void json(HttpExchange exchange, Object payload) throws IOException {
        byte[] body = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        respond(exchange, 200, "application/json; charset=utf-8", body);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void writePid() {
        try (FileWriter fw = new FileWriter(PID_FILE)) {
            fw.write(Long.toString(ProcessHandle.current().pid()));
        } catch (IOException ignored) {}
    }

    private static final String DASHBOARD_HTML = """
            <!doctype html>
            <html lang=\"en\">
            <head>
              <meta charset=\"utf-8\"/>
              <title>queuectl Dashboard</title>
              <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                pre { background: #f5f5f5; padding: 12px; border-radius: 4px; overflow-x: auto; }
                section { margin-bottom: 24px; }
                h1 { margin-bottom: 8px; }
              </style>
            </head>
            <body>
              <h1>queuectl Dashboard</h1>
              <section>
                <h2>Status</h2>
                <pre id=\"status\">Loading...</pre>
              </section>
              <section>
                <h2>Active Jobs</h2>
                <pre id=\"jobs\">Loading...</pre>
              </section>
              <section>
                <h2>Dead Letter Queue</h2>
                <pre id=\"dlq\">Loading...</pre>
              </section>
              <script>
                async function refresh() {
                  const [status, jobs, dlq] = await Promise.all([
                    fetch('/api/status').then(r => r.json()),
                    fetch('/api/jobs').then(r => r.json()),
                    fetch('/api/dlq').then(r => r.json())
                  ]);
                  document.getElementById('status').textContent = JSON.stringify(status, null, 2);
                  document.getElementById('jobs').textContent = JSON.stringify(jobs, null, 2);
                  document.getElementById('dlq').textContent = JSON.stringify(dlq, null, 2);
                }
                refresh();
                setInterval(refresh, 3000);
              </script>
            </body>
            </html>
            """;

    private static final class Query {
        static String queryParam(String query, String key) {
            if (query == null || query.isBlank()) return null;
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return decode(kv[1]);
                }
            }
            return null;
        }

        private static String decode(String value) {
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return value;
            }
        }
    }
}

