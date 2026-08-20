package com.dsatracker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled task that auto-scrapes all job sources daily at 11:00 AM IST.
 * Deduplication is handled inside JobSourceService.scrapeSource().
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
     * Runs daily at 11:00 AM IST (05:30 UTC).
     * Scrapes all registered sources sequentially.
     */
    @Scheduled(cron = "0 0 11 * * *", zone = "Asia/Kolkata")
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
}
