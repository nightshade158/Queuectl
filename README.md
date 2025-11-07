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
├── src/
│   ├── main/
│   │   └── java/com/queuectl/
│   │       ├── Cli.java             # CLI entry point and command handlers
│   │       ├── Models.java          # Job data model
│   │       ├── Storage.java         # SQLite persistence layer
│   │       ├── Worker.java          # Worker process logic
│   │       ├── QueueManager.java    # Queue orchestration
│   │       ├── Dlq.java             # Dead Letter Queue operations
│   │       └── Config.java          # Configuration management
│   └── test/
│       └── java/com/queuectl/
│           └── FlowTest.java        # JUnit test cases
├── scripts/
│   ├── install.sh                   # Installation script
│   ├── test_flow.sh                 # Integration test script
│   └── clean_reset.sh               # Clean rebuild script
├── bin/
│   └── queuectl                     # Local wrapper script
└── README.md                        # This file
```

---

## Code Architecture

### **1. Models.java** - Data Model

**Purpose**: Defines the Job data structure and timestamp utilities.

**Structure**:
- `Models.Job` (static inner class): Represents a job with fields:
  - `id`, `command`, `state`, `attempts`, `max_retries`, `created_at`, `updated_at`
- `Models.nowIso()`: Returns current UTC time in ISO 8601 format

**Key Features**:
- Default state is "pending"
- Default max_retries is 3
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

#### `fetchAndLockNextPending()`
**Critical Method** - Implements atomic job locking:
1. Selects oldest pending job (ORDER BY created_at ASC)
2. Atomically updates state to "processing" (WHERE state='pending')
3. If update affects 1 row, job is locked; otherwise retry
4. Includes retry logic for SQLITE_BUSY errors (up to 20 attempts)
5. Returns locked Job or null if no jobs available

**Concurrency Handling**:
- Uses atomic UPDATE with WHERE condition to prevent race conditions
- Retries on database lock errors with exponential backoff (50-100ms)
- Multiple workers can safely poll simultaneously

#### `setCompleted(String id)`
- Updates job state to "completed"
- Updates timestamp

#### `setFailed(String id, int attempts, String state)`
- Updates job state and attempt count
- Used for retryable failures

#### `moveToDlq(String id)`
- Transactionally moves job from `jobs` to `dead_letter_jobs` table
- Sets state to "dead"
- Deletes from main jobs table

#### `listJobs(String state)`
- Returns all jobs, optionally filtered by state
- Ordered by creation time

#### `listDlq()`
- Returns all jobs in dead_letter_jobs table

#### `retryFromDlq(String id)`
- Moves job back from DLQ to main queue
- Resets attempts to 0, state to "pending"
- Returns true if successful, false if job not found

#### `counts()`
- Returns `Storage.Counts` object with job counts per state
- Used for status command

**Database Schema**:
```sql
jobs: id (PK), command, state, attempts, max_retries, created_at, updated_at
dead_letter_jobs: (same schema)
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
   - Exit code 0 → `Storage.setCompleted(job.id)`

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
- Defaults: `max_retries=3`, `backoff_base=2`
- Merges missing keys with defaults

#### `save(ObjectNode node)`
- Writes configuration to config.json (pretty-printed)

#### `set(String key, String value)`
- Updates configuration key
- Validates key name (only max_retries, backoff_base allowed)
- Converts value to integer if appropriate
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
   → Job inserted with state="pending"

2. WORKER POLLING
   Worker.run() loop:
   → Storage.fetchAndLockNextPending()
     → SELECT oldest pending job
     → UPDATE state='processing' (atomic)
     → Return Job object
   → Worker.execute(job.command)
     → ProcessBuilder("bash", "-lc", command)
     → Wait for exit code

3. SUCCESS PATH
   Exit code = 0
   → Storage.setCompleted(job.id)
   → State = "completed"

4. FAILURE PATH (Retryable)
   Exit code != 0
   → attempts++
   → If attempts <= max_retries:
     → Storage.setFailed(id, attempts, "failed")
     → Calculate delay = backoff_base ^ attempts
     → Thread.sleep(delay)
     → Storage.setFailed(id, attempts, "pending")  # Requeue
   → Worker continues polling

5. FAILURE PATH (Exhausted)
   Exit code != 0 AND attempts > max_retries
   → Storage.moveToDlq(job.id)
   → Job moved to dead_letter_jobs table
   → State = "dead"

6. DLQ RETRY
   User: queuectl dlq retry job1
   → Dlq.retry()
   → Storage.retryFromDlq()
   → Job moved back to jobs table
   → State = "pending", attempts = 0
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
long delay = (long) Math.pow(backoff_base, attempts) * 1000L;
```

**Example with backoff_base=2**:
- 1st retry: 2^1 = 2 seconds
- 2nd retry: 2^2 = 4 seconds
- 3rd retry: 2^3 = 8 seconds
- 4th retry: 2^4 = 16 seconds

**Rationale**: Prevents overwhelming systems with rapid retries while giving transient issues time to resolve.

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

4. **PID File Cleanup**: Deleted in `finally` block

---

## Setup & Installation

### **Prerequisites**

- **Java 17+**: Required for compilation and execution
  - Check: `java -version`
  - Install: `sudo apt install openjdk-17-jdk` (Ubuntu/Debian)

- **Maven 3.9+**: Build tool
  - Check: `mvn -version`
  - Install: `sudo apt install maven` (Ubuntu/Debian)

- **Bash**: Required for command execution (standard on Linux/macOS)

### **Build Process**

1. **Clone/Navigate to Project**:
   ```bash
   cd Project_java
   ```

2. **Build JAR**:
   ```bash
   mvn -q -DskipTests package
   ```
   - Compiles Java source files
   - Downloads dependencies (picocli, sqlite-jdbc, jackson, slf4j)
   - Creates `target/queuectl-0.1.0-jar-with-dependencies.jar`

3. **Install Command** (Optional):
   ```bash
   bash scripts/install.sh
   ```
   - Builds project
   - Installs `queuectl` to `/usr/local/bin` or `~/.local/bin`
   - Adds to PATH if needed

### **Dependencies (from pom.xml)**

- **picocli 4.7.5**: CLI framework
- **sqlite-jdbc 3.46.0.0**: SQLite database driver
- **jackson-databind 2.17.1**: JSON parsing
- **slf4j-simple 2.0.13**: Logging (suppresses warnings)
- **junit-jupiter 5.10.2**: Testing (test scope)

### **Auto-Generated Files**

- `config.json`: Created on first run with defaults
- `queue.db`: SQLite database created on first use
- `queuectl_runtime/`: Runtime directory created by workers

---

## CLI Usage Guide

### **Basic Operations**

#### **1. Enqueue a Job**
```bash
queuectl enqueue '{"id":"job1","command":"echo Hello World"}'
```
- **Required fields**: `id`, `command`
- **Optional fields**: `max_retries`, `attempts`, `state`
- **Default max_retries**: From config.json (default: 3)

**Example with all fields**:
```bash
queuectl enqueue '{"id":"task1","command":"sleep 5","max_retries":5,"state":"pending"}'
```

#### **2. Start Workers**
```bash
queuectl worker start --count 3
```
- Spawns 3 worker processes
- Each worker runs in separate JVM
- Workers poll continuously for jobs

#### **3. Check Status**
```bash
queuectl status
```
**Output**:
```json
{
  "pending": 2,
  "processing": 1,
  "completed": 10,
  "failed": 0,
  "dead": 1,
  "active_workers": 3
}
```

#### **4. List Jobs**
```bash
queuectl list                    # All jobs
queuectl list --state pending    # Only pending jobs
queuectl list --state completed  # Only completed jobs
```

**Output**:
```json
[
  {
    "id": "job1",
    "command": "echo hi",
    "state": "completed",
    "attempts": 0,
    "max_retries": 3,
    "created_at": "2025-11-06T10:00:00Z",
    "updated_at": "2025-11-06T10:00:05Z"
  }
]
```

#### **5. Dead Letter Queue**
```bash
queuectl dlq list              # List all DLQ jobs
queuectl dlq retry job1         # Retry a DLQ job
```

#### **6. Configuration**
```bash
queuectl config get             # Show current config
queuectl config set max_retries 5
queuectl config set backoff_base 3
```

#### **7. Stop Workers**
```bash
queuectl worker stop
```
- Creates STOP file
- Workers finish current job and exit
- May take a few seconds if jobs are running

### **Advanced Usage**

#### **Complex Commands**
```bash
# Multi-line command
queuectl enqueue '{"id":"build","command":"cd /tmp && make && ./test"}'

# Command with arguments
queuectl enqueue '{"id":"backup","command":"tar -czf backup.tar.gz /home/user"}'
```

#### **Monitoring Workflow**
```bash
# Start workers
queuectl worker start --count 2

# Enqueue jobs
queuectl enqueue '{"id":"job1","command":"sleep 2"}'
queuectl enqueue '{"id":"job2","command":"echo done"}'

# Monitor status
watch -n 1 'queuectl status'

# Check specific state
queuectl list --state processing
```

---

## Output Analysis

### **Status Command Output**

```json
{
  "pending": 5,        # Jobs waiting to be processed
  "processing": 2,      # Jobs currently being executed
  "completed": 100,    # Successfully completed jobs
  "failed": 1,         # Jobs in retry state (will retry)
  "dead": 3,          # Jobs moved to DLQ (exhausted retries)
  "active_workers": 2  # Number of running worker processes
}
```

**Interpretation**:
- **High pending**: Workers may be slow or insufficient
- **High processing**: Normal if jobs take time
- **High failed**: Check job commands for issues
- **High dead**: Review DLQ for problematic jobs
- **active_workers=0**: No workers running (start with `worker start`)

### **List Command Output**

**Job States**:
- `pending`: Waiting for worker
- `processing`: Currently executing
- `completed`: Successfully finished
- `failed`: Failed but retryable (attempts < max_retries)
- `dead`: Moved to DLQ (attempts >= max_retries)

**Key Fields**:
- `attempts`: Number of execution attempts
- `max_retries`: Maximum allowed retries
- `created_at`: When job was enqueued
- `updated_at`: Last state change time

### **DLQ List Output**

Shows jobs that have exhausted retries. Common reasons:
- Invalid command (command not found)
- Persistent failure (network issues, missing files)
- Permission errors

**Action**: Review commands, fix issues, then retry with `dlq retry <id>`.

### **Error Messages**

- **"Invalid JSON"**: Malformed JSON in enqueue command
- **"id and command required"**: Missing required fields
- **"Job not found in DLQ"**: Job ID doesn't exist in DLQ
- **"Unknown config key"**: Invalid config key name
- **SQLITE_BUSY**: Database locked (usually transient, retries automatically)

---

## Testing & Validation

### **Manual Testing**

1. **Basic Flow**:
   ```bash
   queuectl enqueue '{"id":"test1","command":"echo success"}'
   queuectl worker start --count 1
   sleep 2
   queuectl status  # Should show completed=1
   queuectl worker stop
   ```

2. **Retry Flow**:
   ```bash
   queuectl enqueue '{"id":"fail1","command":"bash -c \"exit 1\"","max_retries":2}'
   queuectl worker start --count 1
   sleep 10  # Wait for retries
   queuectl status  # Should show failed or dead
   queuectl dlq list  # Check if moved to DLQ
   ```

3. **Concurrent Workers**:
   ```bash
   queuectl enqueue '{"id":"job1","command":"sleep 2"}'
   queuectl enqueue '{"id":"job2","command":"sleep 2"}'
   queuectl enqueue '{"id":"job3","command":"sleep 2"}'
   queuectl worker start --count 3
   sleep 5
   queuectl status  # Should show 3 jobs completed
   ```

### **Automated Testing**

**Integration Test Script**:
```bash
bash scripts/test_flow.sh
```

**What it tests**:
1. Enqueue successful job
2. Start worker
3. Verify completion
4. Enqueue failing job
5. Verify DLQ movement
6. Retry from DLQ

**JUnit Tests**:
```bash
mvn test
```

Runs `FlowTest.java` which validates storage operations.

### **Clean Reset**

To reset everything for fresh testing:
```bash
bash scripts/clean_reset.sh
```

This script:
- Stops all workers
- Removes database
- Cleans Maven build
- Rebuilds project
- Runs tests

---

## Troubleshooting

### **Workers Not Processing Jobs**

**Symptoms**: `pending` count stays high, `active_workers=0`

**Solutions**:
1. Check if workers are running: `queuectl status`
2. Start workers: `queuectl worker start --count 2`
3. Check for STOP file: `ls queuectl_runtime/STOP` (delete if exists)
4. Verify database exists: `ls queue.db`

### **Jobs Stuck in Processing**

**Symptoms**: Jobs remain in "processing" state

**Causes**:
- Worker crashed mid-execution
- Long-running command

**Solutions**:
1. Check worker processes: `ps aux | grep queuectl`
2. Restart workers: `queuectl worker stop && queuectl worker start --count 2`
3. Manually update state (if needed): Direct SQLite access

### **Database Lock Errors**

**Symptoms**: `SQLITE_BUSY` errors in logs

**Solutions**:
- Usually transient - system retries automatically
- If persistent: Reduce number of workers
- Check for stale connections: Restart workers

### **Commands Not Executing**

**Symptoms**: Jobs fail immediately with exit code 127

**Causes**:
- Command not found
- Invalid shell syntax

**Solutions**:
1. Test command manually: `bash -lc "your-command"`
2. Use full paths: `/usr/bin/echo` instead of `echo`
3. Check permissions: Ensure command is executable

### **DLQ Growing Rapidly**

**Symptoms**: Many jobs in DLQ

**Solutions**:
1. Review DLQ jobs: `queuectl dlq list`
2. Check job commands for issues
3. Increase `max_retries` in config
4. Fix underlying issues (network, permissions, etc.)
5. Retry fixed jobs: `queuectl dlq retry <id>`

### **Performance Issues**

**Symptoms**: Slow job processing

**Optimizations**:
1. Increase worker count: `queuectl worker start --count 5`
2. Check database size: `ls -lh queue.db`
3. Clean old completed jobs (manual SQLite cleanup)
4. Monitor system resources: `top`, `htop`

---

## Latest Changes & Improvements

### **Concurrency Improvements**
- **Atomic Job Locking**: Implemented atomic UPDATE with WHERE condition to prevent duplicate processing
- **SQLITE_BUSY Handling**: Added retry logic with exponential backoff for database lock errors
- **WAL Mode**: Enabled Write-Ahead Logging for better concurrent read performance

### **Error Handling**
- **SLF4J Binding**: Added slf4j-simple to eliminate logging warnings
- **Graceful Degradation**: Workers handle missing jobs, invalid commands gracefully

### **Installation**
- **Auto-Install Script**: `scripts/install.sh` automatically installs queuectl command
- **Portable Scripts**: All scripts use relative paths for portability

### **Documentation**
- **Comprehensive README**: Complete technical documentation
- **Code Comments**: Inline documentation for complex logic

---

## License

MIT License - See LICENSE file for details.

---

## Contributing

This is an internship assignment project. For production use, consider:
- Job priority queues
- Scheduled/delayed jobs (run_at timestamps)
- Job output logging
- Metrics and monitoring
- Web dashboard
- Job timeout handling

---

**End of Documentation**
