package com.bhagwat.scm.paymentService.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the nightly transaction purge batch job.
 *
 * All settings can be overridden via application.properties or env vars.
 *
 *   purge.batch.enabled            = true / false (kill switch)
 *   purge.batch.cron               = Spring cron expression (default: 2 AM daily)
 *   purge.batch.chunk-size         = IDs fetched + deleted per DB transaction (default: 500)
 *   purge.batch.thread-pool-size   = parallel worker threads (default: 4)
 *   purge.batch.max-parallel-chunks= max concurrent chunks in-flight (default: 8)
 *   purge.batch.retention-years    = data older than this is purged (default: 7)
 *   purge.batch.dry-run            = log counts only, do NOT delete (default: false)
 */
@Data
@Component
@ConfigurationProperties(prefix = "purge.batch")
public class PurgeJobProperties {

    /** Master kill switch. Set to false to disable the job without redeploying. */
    private boolean enabled = true;

    /** Spring cron expression. Default: 02:00 AM every day. */
    private String cron = "0 0 2 * * *";

    /** Number of WalletTransfer IDs processed in a single DB transaction. */
    private int chunkSize = 500;

    /** Thread pool size for the purge executor service. */
    private int threadPoolSize = 4;

    /**
     * Maximum number of chunk futures kept in-flight simultaneously.
     * Once this limit is hit, the job drains these futures before fetching more chunks.
     * Prevents unbounded memory growth when data volume is very large.
     */
    private int maxParallelChunks = 8;

    /** Data older than this many years is eligible for purge. Hard floor = 7. */
    private int retentionYears = 7;

    /**
     * When true, the job scans and logs what would be deleted but performs no deletions.
     * Safe to run in production to preview the expected purge volume.
     */
    private boolean dryRun = false;
}
