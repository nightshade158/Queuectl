# queuectl - Technical Documentation

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Key Terminology](#key-terminology)
3. [Project Structure](#project-structure)
4. [Code Architecture](#code-architecture)
5. [Implementation Details](#implementation-details)
6. [Setup & Installation](#setup--installation)
7. [CLI Usage Guide](#cli-usage-guide)
8. [Output Analysis](#output-analysis)
9. [Testing & Validation](#testing--validation)
10. [Troubleshooting](#troubleshooting)

---

## Problem Statement

**queuectl** is a production-grade CLI-based background job queue system that enables:

- **Asynchronous Job Execution**: Execute shell commands in the background without blocking the terminal
- **Concurrent Processing**: Multiple worker processes can process jobs in parallel
- **Automatic Retry Mechanism**: Failed jobs automatically retry with exponential backoff
- **Dead Letter Queue (DLQ)**: Permanently failed jobs are moved to a DLQ for manual inspection and retry
- **Persistent Storage**: All job data persists across system restarts using SQLite
- **Graceful Shutdown**: Workers finish current jobs before exiting

This system is designed for scenarios where you need reliable, persistent job processing with retry capabilities, such as batch processing, scheduled tasks, or asynchronous command execution.

---

## Key Terminology

### **Job**
A unit of work defined by:
- **id**: Unique identifier (e.g., "job1", "task-123")
- **command**: Shell command to execute (e.g., "echo hello", "sleep 5")
- **state**: Current status (pending, processing, completed, failed, dead)
- **attempts**: Number of times execution has been attempted
- **max_retries**: Maximum retry attempts before moving to DLQ
- **priority**: Higher values are processed first (default 0)
- **run_at**: ISO timestamp when the job becomes eligible for execution (supports scheduling)
- **timeout_seconds**: Hard execution timeout per attempt (0 = no limit / use config default)
- **last_output_path**: File path capturing stdout/stderr for the most recent attempt
- **run_count / success_count / failure_count**: Execution metrics maintained per job
- **created_at/updated_at**: ISO 8601 timestamps

### **Worker**
A background process that:
- Continuously polls for pending jobs
- Atomically locks and processes one job at a time
- Executes the job's command via bash
- Handles retries with exponential backoff
- Moves exhausted jobs to DLQ
- Supports graceful shutdown

### **Dead Letter Queue (DLQ)**
A separate storage area for jobs that have:
- Exceeded their maximum retry attempts
- Been marked as permanently failed (state="dead")
- Can be manually inspected and retried via CLI commands

### **Exponential Backoff**
A retry strategy where the delay between retries increases exponentially:
- Formula: `delay = backoff_base ^ attempts` seconds
- Example with `backoff_base=2`: 1st retry waits 2s, 2nd waits 4s, 3rd waits 8s
- Prevents overwhelming systems with rapid retry attempts

### **Job Priority**
An integer that influences job scheduling order. Higher values are dequeued before lower-priority jobs when multiple jobs are ready at the same time.

### **Scheduled Job (run_at)**
Each job can specify a future `run_at` timestamp (or `delay_seconds` during enqueue). Workers ignore jobs whose `run_at` is in the future, enabling delayed execution and retry deferral without busy-wait sleeps.

### **Job Timeout**
Jobs can define `timeout_seconds` or fall back to `default_timeout_seconds` in `config.json`. If a command exceeds the timeout, it is forcefully terminated, marked as failure with exit code 124, and retried or moved to DLQ.

### **Job Output Logging**
Every attempt writes combined stdout/stderr to a log file under `job_logs/`. The latest path is tracked per job, retrievable via `queuectl logs <job_id>` and exposed through the dashboard API.

### **Execution Metrics**
Per-job and global metrics (run counts, success/failure totals, cumulative runtimes, average duration, last finished timestamp) are maintained in SQLite and exposed via `queuectl status` / `metrics`.

### **Dashboard**
A lightweight HTTP server (`queuectl dashboard start`) serving live JSON and HTML views of queue status, jobs, DLQ, and logs.

### **Atomic Locking**
A database mechanism ensuring:
- Only one worker can claim a specific pending job
- Prevents duplicate job execution
- Uses SQLite's transactional UPDATE with WHERE conditions

### **WAL Mode (Write-Ahead Logging)**
SQLite journaling mode that:
- Improves concurrent read performance
- Allows multiple readers while one writer
- Reduces database lock contention

---

## Project Structure

```
Project_java/
├── pom.xml                          # Maven build configuration
├── config.json                      # Runtime configuration (auto-generated)
├── queue.db                         # SQLite database (auto-generated)
├── queuectl_runtime/                # Runtime directory (auto-generated)
│   ├── STOP                         # Graceful shutdown flag
│   └── worker-<pid>.pid             # Worker PID files
├── job_logs/                        # Captured stdout/stderr per job attempt (auto-generated)
├── src/
│   ├── main/
│   │   └── java/com/queuectl/
│   │       ├── Cli.java             # CLI entry point and command handlers
│   │       ├── Models.java          # Job data model
│   │       ├── Storage.java         # SQLite persistence layer
│   │       ├── Worker.java          # Worker process logic
│   │       ├── QueueManager.java    # Queue orchestration
│   │       ├── Dlq.java             # Dead Letter Queue operations
│   │       ├── Config.java          # Configuration management
│   │       └── DashboardServer.java # Minimal monitoring web server
│   └── test/
│       └── java/com/queuectl/
│           └── FlowTest.java        # JUnit test cases
├── scripts/
│   ├── install.sh                   # Installation script
│   ├── test_flow.sh                 # Integration test script
│   ├── clean_reset.sh               # Clean rebuild script
│   └── demo_all_features.sh         # End-to-end demonstration script
└── README.md                        # This file
```

---

## Code Architecture

### **1. Models.java** - Data Model

**Purpose**: Defines the Job data structure and timestamp utilities.

**Structure**:
- `Models.Job` (static inner class): Represents a job with fields:
  - `id`, `command`, `state`, `attempts`, `max_retries`, `priority`, `run_at`, `timeout_seconds`, `last_exit_code`, `last_duration_ms`, `last_output_path`, `run_count`, `success_count`, `failure_count`, `total_runtime_ms`, `last_finished_at`, `created_at`, `updated_at`
- `Models.nowIso()`: Returns current UTC time in ISO 8601 format

**Key Features**:
- Default state is "pending"
- Default max_retries is 3
- Default priority is 0, `run_at` seeded to now, and metrics counters initialise to 0
- Optional timeout inherits from config if not specified
- Timestamps auto-initialized on creation

---

### **2. Storage.java** - Persistence Layer

**Purpose**: Manages all database operations for jobs and DLQ.

**Key Methods**:

#### `init()`
- Creates `jobs` and `dead_letter_jobs` tables if they don't exist
- Sets up SQLite PRAGMAs: WAL mode, foreign keys, busy_timeout

#### `getConn()`
- Establishes SQLite connection with:
  - `busy_timeout=5000` (5 second timeout for locked database)
  - WAL journal mode for better concurrency
- Returns a Connection object

#### `upsert(Job j)`
- Inserts new job or updates existing one (ON CONFLICT)
- Used by enqueue operation

#### `markJobSuccess(String id, int attempts, int exitCode, long durationMs, String outputPath)`
- Marks job as completed, increments run/success counters, stores duration and exit code
- Persists latest log path and last_finished_at timestamps

#### `markJobFailure(String id, int attempts, int exitCode, long durationMs, String outputPath, boolean willRetry)`
- Records failed attempt metrics (run_count, failure_count, total_runtime)
- Sets state to `failed` (if retry pending) or `dead` (terminal failure)

#### `scheduleRetry(String id, String nextRunAt)`
- Resets state to `pending`, updates `run_at` to future timestamp for exponential backoff scheduling
- Allows workers to continue processing other jobs without sleeping

#### `moveToDlq(String id)`
- Transactionally copies job + metrics/log path into `dead_letter_jobs`
- Deletes original row from `jobs`, preserving audit information for DLQ inspection

#### `listJobs(String state)`
- Returns jobs ordered by priority DESC, run_at ASC, created_at ASC
- When filtering by `dead`, QueueManager delegates to DLQ list for convenience

#### `listDlq()`
- Returns all jobs in `dead_letter_jobs` ordered by creation time

#### `retryFromDlq(String id)`
- Moves job back from DLQ to main queue, resets attempts=0, state=`pending`, run_at=now
- Preserves historical metrics for observability

#### `counts()`
- Aggregates state counts plus run/success/failure totals, total/average runtime, last_finished_at
- Includes DLQ rows so long-lived failures are reflected in metrics
- Used by status/metrics commands and dashboard APIs

**Database Schema**:
```sql
jobs: id (PK), command, state, attempts, max_retries, priority, run_at, timeout_seconds,
      last_exit_code, last_duration_ms, last_output_path, run_count, success_count,
      failure_count, total_runtime_ms, last_finished_at, created_at, updated_at

dead_letter_jobs: (same columns as jobs)
```

---

### **3. Worker.java** - Worker Process Logic

**Purpose**: Implements the worker loop that processes jobs.

**Key Components**:

#### `run()` - Main Worker Loop
1. **Initialization**:
   - Creates runtime directory (`queuectl_runtime/`)
   - Registers shutdown hook for graceful termination
   - Creates PID file (`worker-<pid>.pid`)

2. **Main Loop**:
   - Checks for STOP file or shutdown signal
   - Calls `Storage.fetchAndLockNextPending()` to get next job
   - If no job: sleep 500ms and continue
   - If job found: execute command

3. **Command Execution**:
   - Uses `ProcessBuilder` with `bash -lc` to execute command
   - Captures exit code

4. **Success Handling**:
   - Exit code 0 → `Storage.markJobSuccess(job.id, job.attempts, exitCode, durationMs, logPath)`

5. **Failure Handling**:
   - Exit code != 0 → increment attempts
   - If `attempts <= max_retries`:
     - Set state to "failed"
     - Calculate exponential backoff: `backoff_base ^ attempts` seconds
     - Sleep for delay
     - Set state back to "pending" (requeue)
   - If `attempts > max_retries`:
     - Move to DLQ via `Storage.moveToDlq(job.id)`

6. **Graceful Shutdown**:
   - Checks `STOP_FILE.exists()` or `shouldStop` flag
   - Finishes current job before exiting
   - Deletes PID file on exit

#### `execute(String command)`
- Executes command via `bash -lc`
- Returns exit code (0 = success, non-zero = failure, 127 = command not found)

#### `activeWorkers()`
- Scans `queuectl_runtime/` for PID files
- Verifies each PID is still alive
- Deletes stale PID files
- Returns count of active workers

**Concurrency**: Multiple worker processes can run simultaneously, each polling independently. Atomic locking in Storage ensures no duplicate processing.

---

### **4. Cli.java** - Command-Line Interface

**Purpose**: Provides CLI commands using picocli framework.

**Commands**:

#### `enqueue <JOB_JSON>`
- Parses JSON payload
- Validates required fields (id, command)
- Creates Job object
- Defaults `max_retries` from config if not provided
- Calls `QueueManager.enqueue()`

**Example**:
```json
{"id":"job1","command":"echo hello","max_retries":3}
```

#### `worker start --count N`
- Removes STOP file if exists
- Spawns N worker processes as separate JVM processes
- Each worker runs `Cli worker run` command
- Workers run in background

#### `worker run` (internal)
- Entry point for worker process
- Calls `new Worker().run()`

#### `worker stop`
- Creates STOP file in `queuectl_runtime/`
- Workers check this file and exit gracefully after current job

#### `status`
- Calls `QueueManager.status()`
- Returns JSON with counts: pending, processing, completed, failed, dead, active_workers

#### `list [--state <state>]`
- Lists all jobs or filtered by state
- Returns JSON array of job objects

#### `dlq list`
- Lists all jobs in Dead Letter Queue
- Returns JSON array

#### `dlq retry <job_id>`
- Moves job from DLQ back to main queue
- Resets attempts to 0
- Returns error if job not found

#### `config get`
- Displays current configuration (JSON)

#### `config set <key> <value>`
- Updates configuration key (max_retries or backoff_base)
- Saves to config.json
- Validates key name

**CLI Framework**: Uses picocli for command parsing, help generation, and subcommand handling.

---

### **5. QueueManager.java** - Queue Orchestration

**Purpose**: Provides high-level queue operations.

**Methods**:
- `enqueue(Job job)`: Delegates to `Storage.upsert()`
- `list(String state)`: Delegates to `Storage.listJobs()`
- `status()`: Combines `Storage.counts()` and `Worker.activeWorkers()`

**Design Pattern**: Facade pattern - simplifies interface to Storage layer.

---

### **6. Dlq.java** - Dead Letter Queue Operations

**Purpose**: Provides DLQ-specific operations.

**Methods**:
- `list()`: Returns all DLQ jobs
- `retry(String id)`: Retries a DLQ job

**Design Pattern**: Facade pattern - delegates to Storage layer.

---

### **7. Config.java** - Configuration Management

**Purpose**: Manages runtime configuration stored in `config.json`.

**Key Methods**:

#### `load()`
- Loads config.json or creates with defaults
- Defaults: `max_retries=3`, `backoff_base=2`, `default_timeout_seconds=0`, `dashboard_port=8080`, `log_directory="job_logs"`
- Merges missing keys with defaults

#### `save(ObjectNode node)`
- Writes configuration to config.json (pretty-printed)

#### `set(String key, String value)`
- Updates configuration key
- Validates key name (`max_retries`, `backoff_base`, `default_timeout_seconds`, `dashboard_port`, `log_directory`)
- Converts numeric values when appropriate
- Saves updated config

**Configuration File**: `config.json` (auto-created in project root)

---

## Implementation Details

### **Job Lifecycle Flow**

```
1. ENQUEUE
   User: queuectl enqueue '{"id":"job1","command":"echo hi"}'
   → Cli.Enqueue.run()
   → QueueManager.enqueue()
   → Storage.upsert()
   → Job inserted with state="pending", default priority=0, run_at=now

2. WORKER POLLING
   Worker.run() loop:
   → Storage.fetchAndLockNextPending()
     → SELECT job where state='pending' AND run_at <= now ORDER BY priority DESC, run_at ASC, created_at ASC
     → UPDATE state='processing' (atomic)
     → Return Job object with metrics/log paths
   → Worker.execute(job)
     → ProcessBuilder("bash", "-lc", command)
     → Redirect combined stdout/stderr to job_logs/<id>-attempt-<n>.log
     → Wait for exit code, respecting per-job/default timeout

3. SUCCESS PATH
   Exit code = 0
   → Storage.markJobSuccess(id, attempts, exitCode, durationMs, logPath)
   → Job state="completed", metrics (run_count, success_count, total_runtime_ms, last_finished_at) updated

4. FAILURE PATH (Retryable)
   Exit code != 0
   → attempts++
   → Storage.markJobFailure(id, attempts, exitCode, durationMs, logPath, willRetry=true)
   → Compute delaySeconds = max(1, backoff_base ^ attempts)
   → Storage.scheduleRetry(id, run_at = now + delaySeconds)  // worker immediately continues polling other jobs

5. FAILURE PATH (Exhausted)
   Exit code != 0 AND attempts >= max_retries
   → Storage.markJobFailure(id, attempts, exitCode, durationMs, logPath, willRetry=false)
   → Storage.moveToDlq(id)  // copies metrics/log reference into dead_letter_jobs and deletes main record

6. DLQ RETRY
   User: queuectl dlq retry job1
   → Dlq.retry()
   → Storage.retryFromDlq()
   → Job moved back to jobs table with attempts reset, run_at=now, state="pending"
```

### **Concurrency & Locking**

**Problem**: Multiple workers must not process the same job.

**Solution**: Atomic UPDATE with WHERE condition:
```sql
UPDATE jobs SET state='processing' 
WHERE id IN (SELECT id FROM jobs WHERE id=? AND state='pending')
```

**How it works**:
1. Worker A selects job with id="job1" (state="pending")
2. Worker A updates: WHERE id="job1" AND state="pending"
3. If Worker B tries same update, WHERE condition fails (state is now "processing")
4. UPDATE returns rowcount=0, Worker B retries with next job

**Database Lock Handling**:
- SQLITE_BUSY errors handled with retry loop (up to 20 attempts)
- Exponential backoff: 50ms, 100ms, ...
- WAL mode allows concurrent reads
- busy_timeout=5000ms gives other transactions time to complete

### **Exponential Backoff Calculation**

```java
long delaySeconds = Math.max(1L, Math.round(Math.pow(backoffBase, attempts)));
```

**Rationale**: Workers reschedule the job's `run_at` instead of sleeping, giving other jobs a chance to run while transient issues recover.

### **Graceful Shutdown Mechanism**

1. **STOP File**: `queuectl_runtime/STOP`
   - Created by `queuectl worker stop`
   - Workers check this file in main loop
   - If exists, set `shouldStop = true`

2. **Shutdown Hook**: Registered via `Runtime.addShutdownHook()`
   - Catches SIGTERM/SIGINT signals
   - Sets `shouldStop = true`

3. **Current Job Completion**: Worker finishes current job before exiting
   - Loop checks `shouldStop` after each job
   - Exits only after completing current job

4. **PID File Cleanup**: Deleted in `


[🎬 Watch Demo Video using this Drive link](https://drive.google.com/file/d/1KUjCwLHTHqX7EZackrOp-ukIo5mKy3cP/view?usp=drive_link)
