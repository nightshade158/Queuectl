package com.queuectl;

import com.queuectl.Models.Job;

import java.util.List;

public class Dlq {
    public static List<Job> list() { return Storage.listDlq(); }
    public static boolean retry(String id) { return Storage.retryFromDlq(id); }
}


