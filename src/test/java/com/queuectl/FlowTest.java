package com.queuectl;

import com.queuectl.Models.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FlowTest {
    @Test
    public void testStorageInitAndEnqueue() {
        Storage.init();
        Job j = new Job("test-1", "echo hi");
        Storage.upsert(j);
        assertNotNull(Storage.listJobs(null));
    }
}


