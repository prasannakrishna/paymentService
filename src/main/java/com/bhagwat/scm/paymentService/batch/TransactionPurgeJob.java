package com.bhagwat.scm.paymentService.batch;

import com.bhagwat.scm.paymentService.batch.PurgeChunkService.ChunkResult;
import com.bhagwat.scm.paymentService.config.PurgeJobProperties;
import com.bhagwat.scm.paymentService.repository.PaymentTransactionRepository;
import com.bhagwat.scm.paymentService.repository.WalletTransferRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nightly batch job that purges transaction data older than the configured
 * retention window (default 7 years).
 *
 * <h3>Tables purged</h3>
 * <ol>
 *   <li>{@code wallet_audit_logs}   — child rows linked to expired transfers</li>
 *   <li>{@code wallet_transactions} — ledger entries linked to expired transfers</li>
 *   <li>{@code wallet_transfers}    — the master transfer records</li>
 *   <li>{@code payment_transactions}— standalone Cashfree PG payment records</li>
 * </ol>
 *
 * <h3>Performance approach</h3>
 * <ul>
 *   <li>IDs are fetched in small chunks ({@code purge.batch.chunk-size}) to
 *       avoid large IN-clauses and long-running table scans.</li>
 *   <li>Each chunk is submitted as a {@link CompletableFuture} to a dedicated
 *       {@link ThreadPoolExecutor}, so multiple chunks can be deleted in parallel
 *       against separate DB connections.</li>
 *   <li>At most {@code purge.batch.max-parallel-chunks} futures are in-flight at
 *       once; the job drains them before fetching more chunks, bounding memory.</li>
 *   <li>Each chunk runs in its own {@code REQUIRES_NEW} transaction via
 *       {@link PurgeChunkService} — one failed chunk rolls back only itself.</li>
 * </ul>
 *
 * <h3>Dry-run mode</h3>
 * Set {@code purge.batch.dry-run=true} to log counts only without deleting.
 * Safe to run against production to preview purge volume.
 *
 * <h3>Kill switch</h3>
 * Set {@code purge.batch.enabled=false} to disable without redeploying.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionPurgeJob {

    private final PurgeJobProperties          props;
    private final WalletTransferRepository    walletTransferRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PurgeChunkService           purgeChunkService;

    /** Dedicated executor — sized independently from the general async pool. */
    private ExecutorService executor;

    @PostConstruct
    void init() {
        int threads = props.getThreadPoolSize();
        AtomicInteger threadNumber = new AtomicInteger(1);
        executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(props.getMaxParallelChunks() * 2),
                r -> {
                    Thread t = new Thread(r, "purge-worker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                // Caller-runs policy: if queue is full, the scheduler thread executes
                // the chunk itself, providing natural back-pressure.
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("Purge executor initialised: threads={} maxParallelChunks={}",
                threads, props.getMaxParallelChunks());
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down purge executor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================
    // Scheduled entry point
    // =========================================================================

    /**
     * Runs on the schedule defined by {@code purge.batch.cron}.
     * Default: 02:00 AM every day (server local time).
     */
    @Scheduled(cron = "${purge.batch.cron:0 0 2 * * *}")
    public void run() {
        if (!props.isEnabled()) {
            log.info("Purge job is disabled (purge.batch.enabled=false). Skipping.");
            return;
        }

        Instant start = Instant.now();
        LocalDateTime cutoff = LocalDateTime.now().minusYears(
                Math.min(props.getRetentionYears(), 7));  // hard cap at 7 years

        log.info("=== TransactionPurgeJob START === cutoff={} dryRun={} chunkSize={} threads={}",
                cutoff, props.isDryRun(), props.getChunkSize(), props.getThreadPoolSize());

        try {
            ChunkResult walletResult = purgeWalletTransfers(cutoff);
            int pgDeleted            = purgePaymentTransactions(cutoff);

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("=== TransactionPurgeJob DONE === elapsed={}s  " +
                     "transfers={} walletTxns={} auditLogs={} pgTransactions={}",
                    elapsed.toSeconds(),
                    walletResult.transfersDeleted(),
                    walletResult.walletTxnsDeleted(),
                    walletResult.auditLogsDeleted(),
                    pgDeleted);
        } catch (Exception e) {
            log.error("TransactionPurgeJob failed after {}: {}",
                    Duration.between(start, Instant.now()), e.getMessage(), e);
        }
    }

    // =========================================================================
    // WalletTransfer purge (chunked + parallel)
    // =========================================================================

    /**
     * Loops until no more expired transfer IDs remain.
     * Submits each chunk as a CompletableFuture to the executor.
     * Drains futures every {@code maxParallelChunks} submissions.
     */
    private ChunkResult purgeWalletTransfers(LocalDateTime cutoff) throws InterruptedException {

        if (props.isDryRun()) {
            long count = walletTransferRepository.countExpiredTransfers(cutoff);
            log.info("[DRY-RUN] WalletTransfers eligible for purge: {}", count);
            return ChunkResult.empty();
        }

        ChunkResult totalResult = ChunkResult.empty();
        List<CompletableFuture<ChunkResult>> inFlight = new ArrayList<>();
        int chunkNumber = 0;

        while (true) {
            // Always fetch from offset 0 — previous batch was already deleted
            List<String> ids = walletTransferRepository.findExpiredTransferIds(
                    cutoff, props.getChunkSize());

            if (ids.isEmpty()) {
                break;
            }

            chunkNumber++;
            final int chunkNum = chunkNumber;
            final List<String> chunkIds = ids;

            log.debug("Submitting purge chunk #{}: {} transfer IDs", chunkNum, chunkIds.size());

            CompletableFuture<ChunkResult> future = CompletableFuture
                    .supplyAsync(() -> {
                        log.debug("Processing purge chunk #{} on thread {}",
                                chunkNum, Thread.currentThread().getName());
                        return purgeChunkService.purgeChunk(chunkIds);
                    }, executor)
                    .exceptionally(ex -> {
                        log.error("Purge chunk #{} failed: {}", chunkNum, ex.getMessage(), ex);
                        return ChunkResult.empty();   // don't let one bad chunk stop the job
                    });

            inFlight.add(future);

            // Drain when we reach maxParallelChunks to bound memory usage
            if (inFlight.size() >= props.getMaxParallelChunks()) {
                totalResult = totalResult.add(drainFutures(inFlight));
                inFlight.clear();
            }
        }

        // Drain any remaining futures
        if (!inFlight.isEmpty()) {
            totalResult = totalResult.add(drainFutures(inFlight));
        }

        log.info("WalletTransfer purge complete: {} chunks processed, {} transfers, {} walletTxns, {} auditLogs",
                chunkNumber,
                totalResult.transfersDeleted(),
                totalResult.walletTxnsDeleted(),
                totalResult.auditLogsDeleted());

        return totalResult;
    }

    /** Waits for all futures, aggregates their results. */
    private ChunkResult drainFutures(List<CompletableFuture<ChunkResult>> futures)
            throws InterruptedException {

        ChunkResult aggregated = ChunkResult.empty();
        try {
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            all.get(10, TimeUnit.MINUTES);  // generous timeout per drain cycle
        } catch (ExecutionException e) {
            log.error("Unexpected execution error draining purge futures: {}", e.getMessage(), e);
        } catch (TimeoutException e) {
            log.error("Drain cycle timed out after 10 minutes — some chunks may not have finished");
        }

        for (CompletableFuture<ChunkResult> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                aggregated = aggregated.add(f.getNow(ChunkResult.empty()));
            }
        }
        return aggregated;
    }

    // =========================================================================
    // Standalone PaymentTransaction purge (single bulk DELETE)
    // =========================================================================

    /**
     * PaymentTransactions have no child tables, so a single bulk DELETE is sufficient.
     * No chunking needed — the DB executes this as one indexed range delete.
     */
    @Transactional
    int purgePaymentTransactions(LocalDateTime cutoff) {
        if (props.isDryRun()) {
            long count = paymentTransactionRepository.countByCreatedAtBefore(cutoff);
            log.info("[DRY-RUN] PaymentTransactions eligible for purge: {}", count);
            return 0;
        }

        int deleted = paymentTransactionRepository.deleteByCreatedAtBefore(cutoff);
        log.info("PaymentTransaction purge: {} rows deleted", deleted);
        return deleted;
    }
}
