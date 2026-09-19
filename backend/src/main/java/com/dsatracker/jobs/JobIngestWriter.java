package com.dsatracker.jobs;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Database writes for job ingestion, deliberately split out of {@link JobSourceService}.
 *
 * <p>Ingestion interleaves slow external HTTP calls with persistence. Wrapping that whole
 * loop in one transaction holds a write lock on the {@code job_sources} row for the entire
 * duration, which blocks unrelated operations on that source — deleting it, most visibly —
 * until the sweep finishes or the caller times out. Keeping the transactional boundary here,
 * around short bursts of writes only, means no transaction is ever open while waiting on a
 * third-party API.
 */
@Component
class JobIngestWriter {
    private final JobSourceRepository sources;
    private final JobListingRepository listings;
    private final JobProfileRepository profiles;
    private final GroqService groqService;

    JobIngestWriter(JobSourceRepository sources, JobListingRepository listings, JobProfileRepository profiles, GroqService groqService) {
        this.sources = sources;
        this.listings = listings;
        this.profiles = profiles;
        this.groqService = groqService;
    }

    /**
     * Insert the postings from one chunk that aren't already stored for this source.
     *
     * <p>Rows are saved in a single batch rather than one call each, since a few hundred
     * individual round-trips to a remote database is what pushed a sweep past the request
     * timeout in the first place.
     *
     * @return how many new listings were created
     */
    @Transactional
    public int persistChunk(Long sourceId, Long postedBy, String company, List<ScrapedJob> jobs, Long targetProfileId) {
        if (jobs.isEmpty()) return 0;

        List<JobProfile> targetProfiles;
        if (targetProfileId != null) {
            targetProfiles = profiles.findById(targetProfileId).map(List::of).orElse(List.of());
        } else {
            targetProfiles = profiles.findByUserIdOrderByCreatedAtDesc(postedBy);
        }

        Set<String> incomingIds = jobs.stream()
                .map(ScrapedJob::externalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // Mutable so it can also absorb ids added earlier within this same chunk.
        Set<String> known = incomingIds.isEmpty()
                ? new HashSet<>()
                : new HashSet<>(listings.findExistingExternalIds(sourceId, incomingIds));

        List<JobListing> batch = new ArrayList<>();
        for (ScrapedJob job : jobs) {
            if (job.externalId() != null) {
                if (!known.add(job.externalId())) continue;
            } else if (listings.existsByJobUrlIgnoreCase(job.url())) {
                // Generic scrapes have no portal id, so fall back to the URL.
                continue;
            }
            
            JobListing listing = toListing(job, sourceId, postedBy, company);
            
            // Apply strict filters based on user request:
            // 1. Must be 0 experience (null or 0)
            if (listing.getExperienceRequired() != null && listing.getExperienceRequired() > 0) {
                continue;
            }
            
            // 2. Must match one of the target roles using basic regex first, then Groq
            String detected = null;
            for (JobProfile profile : targetProfiles) {
                if (matchesProfileRegex(profile, listing.getTitle(), listing.getDescription())) {
                    if (groqService.confirmJobMatch(profile.getRoleTitle(), profile.getKeywords(), listing.getTitle(), listing.getDescription())) {
                        detected = profile.getRoleTitle();
                        break;
                    }
                }
            }

            if (detected == null) {
                continue;
            }
            listing.setDetectedRole(detected);
            
            batch.add(listing);
        }

        if (batch.isEmpty()) return 0;
        listings.saveAll(batch);
        return batch.size();
    }

    /** Extraction outcomes, surfaced to the UI so unsupported sites are visible. */
    static final String STATUS_FULL = "FULL";
    static final String STATUS_LIMITED = "LIMITED";
    static final String STATUS_NONE = "NONE";
    static final String STATUS_ERROR = "ERROR";

    /**
     * Record sweep progress and the outcome of the latest attempt.
     *
     * @param status one of the STATUS_* values, or null to leave the existing status alone
     *               (mid-sweep checkpoints shouldn't overwrite a verdict)
     */
    @Transactional
    public void markProgress(Long sourceId, String adapter, int cursor,
                             boolean sweepComplete, String error, String status,
                             Integer totalSeen, Integer totalMatched) {
        sources.findById(sourceId).ifPresent(source -> {
            if (adapter != null) source.setAdapter(adapter);
            if (status != null) source.setExtractionStatus(status);
            source.setSyncCursor(Math.max(0, cursor));
            source.setLastScrapedAt(Instant.now());
            source.setLastError(error);
            if (totalSeen != null) source.setLastScrapeTotalJobs(totalSeen);
            if (totalMatched != null) source.setLastScrapeMatchedJobs(totalMatched);
            if (sweepComplete) {
                source.setSyncCursor(0);
                source.setSweepCompletedAt(Instant.now());
            }
            sources.save(source);
        });
    }

    /** Persist a brand-new source in its own short transaction, before any HTTP happens. */
    @Transactional
    public JobSource createSource(Long userId, String url, String label) {
        JobSource source = new JobSource();
        source.setAddedBy(userId);
        source.setUrl(url);
        source.setLabel(label);
        source.setCreatedAt(Instant.now());
        return sources.save(source);
    }

    private JobListing toListing(ScrapedJob job, Long sourceId, Long postedBy, String company) {
        JobListing listing = new JobListing();
        listing.setTitle(job.title() != null ? job.title() : "Job opportunity");
        listing.setCompany(company);
        listing.setJobUrl(job.url());
        listing.setDescription(job.description());
        listing.setExternalId(job.externalId());
        listing.setLocation(truncateOrNull(job.location(), 300));
        listing.setEmploymentType(truncateOrNull(job.employmentType(), 100));
        listing.setCareerLevel(truncateOrNull(job.careerLevel(), 100));
        listing.setQualification(truncateOrNull(job.qualification(), 500));
        listing.setPostedText(truncateOrNull(job.postedText(), 120));
        listing.setPostedBy(postedBy);
        listing.setSourceId(sourceId);

        // Prefer what the portal states, then fall back to reading it out of the text.
        Integer experience = job.yearsExperience();
        if (experience == null) experience = JobSourceService.extractExperience(job.title());
        if (experience == null) experience = JobSourceService.extractExperience(job.description());
        if (experience == null) experience = JobSourceService.experienceFromCareerLevel(job.careerLevel());
        listing.setExperienceRequired(experience);

        listing.setCreatedAt(Instant.now());
        return listing;
    }

    private static String truncateOrNull(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
    
    private boolean matchesProfileRegex(JobProfile profile, String title, String description) {
        String combined = ((title != null ? title : "") + " " + (description != null ? description : "")).toLowerCase();
        String roleLower = profile.getRoleTitle().toLowerCase();
        
        // Basic naive check: if the title or keywords appear in the text
        if (combined.contains(roleLower)) return true;
        
        // If they provided keywords, check if any match
        if (profile.getKeywords() != null && !profile.getKeywords().isBlank()) {
            String[] words = profile.getKeywords().toLowerCase().split("[,\\s]+");
            for (String w : words) {
                if (!w.isBlank() && combined.contains(w)) return true;
            }
        }
        
        // If it's something like "Java Developer", check for "Java" and "Developer"
        String[] parts = roleLower.split("\\s+");
        boolean allMatch = true;
        for (String p : parts) {
            if (!combined.contains(p)) {
                allMatch = false;
                break;
            }
        }
        return allMatch;
    }
}
