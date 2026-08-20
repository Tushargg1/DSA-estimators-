package com.dsatracker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps job sources ingested without anyone pressing a button.
 *
 * <p>Two schedules cooperate. A daily pass starts a fresh sweep of every source, and a
 * frequent pass carries any half-finished sweep forward until the whole board is stored.
 * The second one exists because a board can hold thousands of postings: a single run only
 * fetches a bounded number of chunks so it cannot outlive a request or hosting timeout,
 * and the persisted cursor is what lets the next run resume instead of restarting.
 *
 * <p>Deduplication lives in {@link JobIngestWriter}, so overlapping runs are harmless.
 */
@Component
public class JobScrapeScheduler {
    private static final Logger log = LoggerFactory.getLogger(JobScrapeScheduler.class);

    private final JobSourceRepository sources;
    private final JobSourceService sourceService;

    public JobScrapeScheduler(JobSourceRepository sources, JobSourceService sourceService) {
        this.sources = sources;
        this.sourceService = sourceService;
    }

    /**
     * Runs daily at 9:00 AM IST (03:30 UTC).
     * Scrapes all registered sources sequentially.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void dailyScrapeAll() {
        List<JobSource> allSources = sources.findAllByOrderByCreatedAtDesc();
        if (allSources.isEmpty()) {
            log.info("[DailyScrape] No sources registered, skipping.");
            return;
        }

        log.info("[DailyScrape] Starting daily scrape of {} sources", allSources.size());
        int totalNew = 0;
        int errors = 0;

        for (JobSource source : allSources) {
            try {
                JobDtos.ScrapeResult result = sourceService.scrapeSourceInternal(source);
                totalNew += result.newListings();
                if (result.error() != null) errors++;
                log.debug("[DailyScrape] Source {} ({}): {} new, error={}",
                        source.getId(), source.getUrl(), result.newListings(), result.error());
            } catch (Exception e) {
                errors++;
                log.warn("[DailyScrape] Unexpected error scraping source {}: {}",
                        source.getId(), e.getMessage());
            }
        }

        log.info("[DailyScrape] Completed: {} sources, {} new listings, {} errors",
                allSources.size(), totalNew, errors);
    }

    /**
     * Carries partially-ingested sources forward so a board finishes on its own.
     *
     * <p>A non-zero sync cursor means a sweep stopped mid-board; it is reset to zero once
     * the portal reports no further results. Only those sources are touched, so completed
     * ones are left alone until the next daily pass.
     *
     * <p>{@code fixedDelay} rather than {@code fixedRate}: the gap is measured after a run
     * finishes, which stops slow runs from overlapping each other.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void continueIncompleteSweeps() {
        List<JobSource> pending = sources.findAllByOrderByCreatedAtDesc().stream()
                .filter(source -> source.getSyncCursor() > 0)
                .toList();
        if (pending.isEmpty()) return;

        log.info("[ContinueSweep] Resuming {} partially ingested source(s)", pending.size());
        for (JobSource source : pending) {
            try {
                JobDtos.ScrapeResult result = sourceService.scrapeSourceInternal(source);
                log.info("[ContinueSweep] Source {} added {} listing(s){}",
                        source.getId(), result.newListings(),
                        result.error() != null ? " (error: " + result.error() + ")" : "");
            } catch (Exception e) {
                log.warn("[ContinueSweep] Unexpected error on source {}: {}",
                        source.getId(), e.getMessage());
            }
        }
    }
}
