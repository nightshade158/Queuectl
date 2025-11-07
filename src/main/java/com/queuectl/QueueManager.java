package com.queuectl;

import com.queuectl.Models.Job;

import java.util.List;

public class QueueManager {
    public void enqueue(Job job) {
        Storage.upsert(job);
    }

    public List<Job> list(String state) {
        if (state != null && state.equals("dead")) {
            return Storage.listDlq();
        }
        return Storage.listJobs(state);
    }

    public Storage.Counts status() { 
        Storage.Counts c = Storage.counts();
        c.active_workers = Worker.activeWorkers();
        return c;
    }
}


