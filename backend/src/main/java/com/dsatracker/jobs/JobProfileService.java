package com.dsatracker.jobs;

import com.dsatracker.web.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class JobProfileService {
    private static final int MAX_PROFILES = 10;
    private final JobProfileRepository profiles;
    private final JobListingRepository listings;
    private final JobApplicationRepository applications;
    private final JobSourceRepository sources;
    private final com.dsatracker.repository.UserRepository users;

    public JobProfileService(JobProfileRepository profiles,
                             JobListingRepository listings,
                             JobApplicationRepository applications,
                             JobSourceRepository sources,
                             com.dsatracker.repository.UserRepository users) {
        this.profiles = profiles;
        this.listings = listings;
        this.applications = applications;
        this.sources = sources;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<JobDtos.ProfileResponse> listProfiles(Long userId) {
        return listProfiles(userId, null);
    }

    @Transactional(readOnly = true)
    public List<JobDtos.ProfileResponse> listProfiles(Long userId, Integer maxExperience) {
        List<JobProfile> userProfiles = profiles.findByUserIdOrderByCreatedAtDesc(userId);
        List<JobListing> allJobs = listings.findAllByOrderByCreatedAtDescIdDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
        List<JobSource> allSources = sources.findAllByOrderByCreatedAtDesc();
        Map<Long, Instant> appliedMap = loadAppliedMap(userId, allJobs);
        Map<Long, String> posterNames = loadPosterNames(allJobs);

        return userProfiles.stream().map(profile -> {
            Set<String> keys = parseKeywords(profile.getKeywords());
            List<JobDtos.JobResponse> matched = allJobs.stream()
                    .filter(job -> matchesKeywords(job, keys))
                    .filter(job -> filterByExperience(job, maxExperience))
                    .map(job -> toResponse(job, appliedMap, posterNames))
                    .toList();

            // Group matched jobs by company
            Map<String, List<JobDtos.JobResponse>> byCompany = matched.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            JobDtos.JobResponse::company,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.toList()));

            // Build company groups: start with all registered sources, then add any
            // other companies that have matched jobs but aren't a source
            Map<String, JobDtos.CompanyGroup> groupMap = new LinkedHashMap<>();

            // Add all sources as companies (even with 0 matched jobs)
            for (JobSource source : allSources) {
                String companyName = source.getLabel() != null ? source.getLabel() : hostFromUrl(source.getUrl());
                List<JobDtos.JobResponse> jobs = byCompany.getOrDefault(companyName, List.of());
                groupMap.put(companyName, new JobDtos.CompanyGroup(companyName, source.getId(), jobs.size(), jobs));
            }

            // Add companies from matched jobs that aren't already a source
            for (Map.Entry<String, List<JobDtos.JobResponse>> entry : byCompany.entrySet()) {
                if (!groupMap.containsKey(entry.getKey())) {
                    Long sourceId = allJobs.stream()
                            .filter(j -> j.getCompany().equals(entry.getKey()) && j.getSourceId() != null)
                            .map(JobListing::getSourceId)
                            .findFirst().orElse(null);
                    groupMap.put(entry.getKey(), new JobDtos.CompanyGroup(
                            entry.getKey(), sourceId, entry.getValue().size(), entry.getValue()));
                }
            }

            // Sort: companies with jobs first (desc by count), then empty ones
            List<JobDtos.CompanyGroup> companyGroups = groupMap.values().stream()
                    .sorted((a, b) -> Integer.compare(b.totalJobs(), a.totalJobs()))
                    .toList();

            return new JobDtos.ProfileResponse(
                    profile.getId(), profile.getRoleTitle(), profile.getKeywords(),
                    profile.getResumeText(), profile.getResumeFileName(),
                    profile.getCreatedAt(), matched, companyGroups);
        }).toList();
    }

    @Transactional
    public JobDtos.ProfileResponse createProfile(Long userId, JobDtos.CreateProfileRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String roleTitle = validated(request == null ? null : request.roleTitle(),
                "roleTitle", "Role title", 200, errors);
        String keywords = validated(request == null ? null : request.keywords(),
                "keywords", "Keywords", 2000, errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);

        if (profiles.countByUserId(userId) >= MAX_PROFILES) {
            errors.put("roleTitle", "You can have at most " + MAX_PROFILES + " role profiles.");
            throw new ValidationException(errors);
        }

        // If resume text is provided, extract additional keywords from it
        String resumeText = request != null ? request.resumeText() : null;
        String resumeFileName = request != null ? request.resumeFileName() : null;
        if (resumeText != null && resumeText.length() > 50000) {
            resumeText = resumeText.substring(0, 50000);
        }
        if (resumeFileName != null && resumeFileName.length() > 500) {
            resumeFileName = resumeFileName.substring(0, 500);
        }
        String finalKeywords = keywords;
        if (resumeText != null && !resumeText.isBlank()) {
            Set<String> extracted = extractKeywordsFromResume(resumeText);
            if (!extracted.isEmpty()) {
                Set<String> existing = parseKeywords(keywords);
                extracted.removeAll(existing);
                if (!extracted.isEmpty()) {
                    finalKeywords = keywords + "," + String.join(",", extracted);
                }
            }
        }

        JobProfile profile = new JobProfile();
        profile.setUserId(userId);
        profile.setRoleTitle(roleTitle);
        profile.setKeywords(finalKeywords);
        profile.setResumeText(resumeText);
        profile.setResumeFileName(resumeFileName);
        profile.setCreatedAt(Instant.now());
        profiles.save(profile);

        return new JobDtos.ProfileResponse(profile.getId(), profile.getRoleTitle(),
                profile.getKeywords(), profile.getResumeText(), profile.getResumeFileName(),
                profile.getCreatedAt(), List.of());
    }

    @Transactional
    public void deleteProfile(Long userId, Long profileId) {
        profiles.findById(profileId).ifPresent(profile -> {
            if (profile.getUserId().equals(userId)) {
                profiles.delete(profile);
            }
        });
    }

    // --- Helpers ---

    private Map<Long, Instant> loadAppliedMap(Long userId, List<JobListing> jobs) {
        if (jobs.isEmpty()) return Map.of();
        List<Long> ids = jobs.stream().map(JobListing::getId).toList();
        return applications.findForUserAndListings(userId, ids).stream()
                .collect(Collectors.toMap(a -> a.getId().getListingId(), JobApplication::getAppliedAt));
    }

    private Map<Long, String> loadPosterNames(List<JobListing> jobs) {
        if (jobs.isEmpty()) return Map.of();
        Set<Long> posterIds = jobs.stream().map(JobListing::getPostedBy).collect(Collectors.toSet());
        return users.findAllById(posterIds).stream()
                .collect(Collectors.toMap(com.dsatracker.model.User::getId, com.dsatracker.model.User::getName));
    }

    private JobDtos.JobResponse toResponse(JobListing job, Map<Long, Instant> appliedMap,
                                           Map<Long, String> posterNames) {
        Instant applied = appliedMap.get(job.getId());
        return new JobDtos.JobResponse(job.getId(), job.getTitle(), job.getCompany(), job.getJobUrl(),
                posterNames.getOrDefault(job.getPostedBy(), "Community member"),
                job.getCreatedAt(), job.getExperienceRequired(), applied != null, applied);
    }

    static Set<String> parseKeywords(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split("[,;|\\n]"))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean matchesKeywords(JobListing job, Set<String> keywords) {
        if (keywords.isEmpty()) return false;
        String haystack = (job.getTitle() + " " + job.getCompany()).toLowerCase();
        return keywords.stream().anyMatch(haystack::contains);
    }

    /**
     * Filter by experience level.
     * If maxExperience is null, no filtering is applied.
     * If a job has no explicit experience_required (null), it's visible to everyone.
     * If a job has experience_required set, only show it if user's experience >= that value.
     */
    private boolean filterByExperience(JobListing job, Integer maxExperience) {
        if (maxExperience == null) return true; // No filter applied
        Integer required = job.getExperienceRequired();
        if (required == null) return true; // No requirement = show to all
        return maxExperience >= required;
    }

    /** Extracts tech-related keywords from resume text. */
    static Set<String> extractKeywordsFromResume(String text) {
        // Simple heuristic: split on whitespace/punctuation, keep words 2-30 chars
        // that look like tech terms (contain letters, may contain digits, common in roles)
        String[] tokens = text.toLowerCase().split("[\\s,;.!?()\\[\\]{}<>\"'|/\\\\]+");
        Set<String> techTerms = Set.of(
                "java", "python", "javascript", "typescript", "react", "angular", "vue",
                "node", "spring", "django", "flask", "aws", "azure", "gcp", "docker",
                "kubernetes", "k8s", "terraform", "ci/cd", "ml", "ai", "deep learning",
                "machine learning", "data science", "sql", "nosql", "mongodb", "postgresql",
                "mysql", "redis", "kafka", "rabbitmq", "graphql", "rest", "api",
                "microservices", "devops", "frontend", "backend", "fullstack", "full-stack",
                "ios", "android", "swift", "kotlin", "flutter", "dart", "rust", "go",
                "golang", "c++", "c#", ".net", "scala", "haskell", "ruby", "rails",
                "php", "laravel", "nextjs", "nuxtjs", "svelte", "tailwind", "css",
                "html", "webpack", "vite", "git", "linux", "agile", "scrum",
                "tensorflow", "pytorch", "pandas", "numpy", "spark", "hadoop",
                "elasticsearch", "solr", "jenkins", "github actions", "gitlab"
        );
        return Arrays.stream(tokens)
                .filter(t -> t.length() >= 2 && t.length() <= 30)
                .filter(techTerms::contains)
                .collect(Collectors.toSet());
    }

    private static String validated(String value, String field, String label, int max,
                                    Map<String, String> errors) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            errors.put(field, label + " is required.");
            return null;
        }
        if (normalized.length() > max) {
            errors.put(field, label + " must be " + max + " characters or fewer.");
            return null;
        }
        return normalized;
    }

    private static String hostFromUrl(String url) {
        try { return new java.net.URI(url).getHost().replaceFirst("^www\\.", ""); }
        catch (Exception e) { return "External"; }
    }
}
