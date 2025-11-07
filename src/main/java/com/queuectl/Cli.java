package com.queuectl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.queuectl.Models.Job;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.List;

@Command(name = "queuectl", mixinStandardHelpOptions = true, description = "CLI job queue controller", subcommands = {
        Cli.Enqueue.class, Cli.WorkerCmd.class, Cli.Status.class, Cli.ListCmd.class, Cli.DlqCmd.class, Cli.ConfigCmd.class
})
public class Cli implements Runnable {
    public void run() { new CommandLine(this).usage(System.out); }

    public static void main(String[] args) { System.exit(new CommandLine(new Cli()).execute(args)); }

    @Command(name = "enqueue", description = "Enqueue a new job with JSON payload")
    static class Enqueue implements Runnable {
        @Parameters(index = "0", paramLabel = "JOB_JSON", description = "Job JSON e.g. {\"id\":\"job1\",\"command\":\"echo hi\"}")
        String jobJson;
        public void run() {
            try {
                ObjectMapper m = new ObjectMapper();
                ObjectNode n = (ObjectNode) m.readTree(jobJson);
                if (!n.has("id") || !n.has("command")) throw new IllegalArgumentException("id and command required");
                Job j = new Job(n.get("id").asText(), n.get("command").asText());
                if (n.has("attempts")) j.attempts = n.get("attempts").asInt();
                if (n.has("state")) j.state = n.get("state").asText();
                // Default max_retries from config when not provided in payload
                if (n.has("max_retries")) {
                    j.max_retries = n.get("max_retries").asInt();
                } else {
                    j.max_retries = Config.load().get("max_retries").asInt(3);
                }
                QueueManager qm = new QueueManager();
                qm.enqueue(j);
                System.out.println("Enqueued job " + j.id);
            } catch (Exception e) { System.err.println("Invalid JSON: " + e.getMessage()); System.exit(1);}        }
    }

    @Command(name = "worker", description = "Manage workers", subcommands = {WorkerCmd.Start.class, WorkerCmd.Run.class, WorkerCmd.Stop.class})
    static class WorkerCmd implements Runnable {
        public void run() { CommandLine.usage(this, System.out); }

        @Command(name = "start", description = "Start N workers")
        static class Start implements Runnable {
            @Option(names = "--count", defaultValue = "1")
            int count;
            public void run() {
                // Remove STOP if exists
                if (!Worker.RUNTIME_DIR.exists()) Worker.RUNTIME_DIR.mkdirs();
                if (Worker.STOP_FILE.exists()) Worker.STOP_FILE.delete();
                String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                String cp = System.getProperty("java.class.path");
                for (int i = 0; i < count; i++) {
                    try {
                        new ProcessBuilder(java, "-cp", cp, Cli.class.getName(), "worker", "run").inheritIO().start();
                    } catch (Exception e) { System.err.println("Failed to start worker: " + e.getMessage()); }
                }
                System.out.println("Started " + count + " worker(s)");
            }
        }

        @Command(name = "run", description = "Run worker (internal)")
        static class Run implements Runnable {
            public void run() { new Worker().run(); }
        }

        @Command(name = "stop", description = "Stop workers gracefully")
        static class Stop implements Runnable {
            public void run() {
                if (!Worker.RUNTIME_DIR.exists()) Worker.RUNTIME_DIR.mkdirs();
                try { Worker.STOP_FILE.createNewFile(); } catch (Exception ignored) {}
                System.out.println("Signalled workers to stop. They will exit after current job.");
            }
        }
    }

    @Command(name = "status", description = "Show summary of job states & active workers")
    static class Status implements Runnable {
        public void run() {
            Storage.Counts s = new QueueManager().status();
            PrintStream o = System.out;
            o.println("{" +
                    "\"pending\":" + s.pending + "," +
                    "\"processing\":" + s.processing + "," +
                    "\"completed\":" + s.completed + "," +
                    "\"failed\":" + s.failed + "," +
                    "\"dead\":" + s.dead + "," +
                    "\"active_workers\":" + s.active_workers +
                    "}");
        }
    }

    @Command(name = "list", description = "List jobs")
    static class ListCmd implements Runnable {
        @Option(names = "--state", required = false)
        String state;
        public void run() {
            List<Job> jobs = new QueueManager().list(state);
            PrintStream o = System.out;
            o.println("[");
            for (int i=0;i<jobs.size();i++) {
                Job j = jobs.get(i);
                o.print("  {\"id\":\""+j.id+"\",\"command\":\""+j.command.replace("\"","\\\"")+"\",\"state\":\""+j.state+"\",\"attempts\":"+j.attempts+",\"max_retries\":"+j.max_retries+",\"created_at\":\""+j.created_at+"\",\"updated_at\":\""+j.updated_at+"\"}");
                if (i<jobs.size()-1) o.println(","); else o.println();
            }
            o.println("]");
        }
    }

    @Command(name = "dlq", description = "Dead Letter Queue operations", subcommands = {DlqCmd.ListDlq.class, DlqCmd.Retry.class})
    static class DlqCmd implements Runnable {
        public void run() { CommandLine.usage(this, System.out); }

        @Command(name = "list", description = "List DLQ jobs")
        static class ListDlq implements Runnable {
            public void run() {
                List<Job> jobs = Dlq.list();
                PrintStream o = System.out;
                o.println("[");
                for (int i=0;i<jobs.size();i++) {
                    Job j = jobs.get(i);
                    o.print("  {\"id\":\""+j.id+"\",\"command\":\""+j.command.replace("\"","\\\"")+"\",\"state\":\""+j.state+"\",\"attempts\":"+j.attempts+",\"max_retries\":"+j.max_retries+",\"created_at\":\""+j.created_at+"\",\"updated_at\":\""+j.updated_at+"\"}");
                    if (i<jobs.size()-1) o.println(","); else o.println();
                }
                o.println("]");
            }
        }

        @Command(name = "retry", description = "Retry DLQ job by id")
        static class Retry implements Runnable {
            @Parameters(index = "0") String jobId;
            public void run() {
                boolean ok = Dlq.retry(jobId);
                if (ok) System.out.println("Retried DLQ job " + jobId); else { System.err.println("Job not found in DLQ"); System.exit(1);}            }
        }
    }

    @Command(name = "config", description = "Manage configuration", subcommands = {ConfigCmd.Get.class, ConfigCmd.Set.class})
    static class ConfigCmd implements Runnable {
        public void run() { CommandLine.usage(this, System.out); }

        @Command(name = "get", description = "Show current config")
        static class Get implements Runnable {
            public void run() { System.out.println(Config.load().toPrettyString()); }
        }

        @Command(name = "set", description = "Set a config key")
        static class Set implements Runnable {
            @Parameters(index = "0") String key; @Parameters(index = "1") String value;
            public void run() {
                try { System.out.println(Config.set(key, value).toPrettyString()); } catch (IllegalArgumentException e) { System.err.println(e.getMessage()); System.exit(1);}            }
        }
    }
}


