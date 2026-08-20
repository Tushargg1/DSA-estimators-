package com.dsatracker.jobs;

import com.dsatracker.model.User;
import com.dsatracker.repository.UserRepository;
import com.dsatracker.web.PageResponse;
import com.dsatracker.web.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobBoardService {
    private static final int MAX_PAGE_SIZE = 50;
    private final JobListingRepository listings;
    private final JobApplicationRepository applications;
    private final UserRepository users;

    public JobBoardService(JobListingRepository listings,
                           JobApplicationRepository applications,
                           UserRepository users) {
        this.listings = listings;
        this.applications = applications;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobDtos.JobResponse> list(Long actorId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<JobListing> result = listings.findAllByOrderByCreatedAtDescIdDesc(
                PageRequest.of(safePage, safeSize));
        MappingContext context = context(actorId, result.getContent());
        return PageResponse.from(result, context::response);
    }

    @Transactional
    public JobDtos.JobResponse create(Long actorId, JobDtos.CreateRequest request) {
        ValidatedJob value = validate(request);
        JobListing listing = new JobListing();
        listing.setTitle(value.title());
        listing.setCompany(value.company());
        listing.setJobUrl(value.jobUrl());
        listing.setPostedBy(actorId);
        listing.setCreatedAt(Instant.now());
        return response(actorId, listings.save(listing));
    }

    @Transactional
    public JobDtos.JobResponse markApplied(Long actorId, Long listingId) {
        JobListing listing = requireListing(listingId);
        applications.markApplied(listingId, actorId);
        return response(actorId, listing);
    }

    @Transactional
    public JobDtos.JobResponse unmarkApplied(Long actorId, Long listingId) {
        JobListing listing = requireListing(listingId);
        applications.unmarkApplied(listingId, actorId);
        return response(actorId, listing);
    }

    private JobListing requireListing(Long listingId) {
        return listings.findById(listingId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Job listing not found"));
    }

    private JobDtos.JobResponse response(Long actorId, JobListing listing) {
        return context(actorId, List.of(listing)).response(listing);
    }

    private MappingContext context(Long actorId, List<JobListing> values) {
        if (values.isEmpty()) return new MappingContext(Map.of(), Map.of());
        List<Long> listingIds = values.stream().map(JobListing::getId).toList();
        Map<Long, Instant> appliedAt = applications
                .findForUserAndListings(actorId, listingIds).stream()
                .collect(Collectors.toMap(
                        item -> item.getId().getListingId(),
                        JobApplication::getAppliedAt));
        Collection<Long> posterIds = values.stream().map(JobListing::getPostedBy)
                .collect(Collectors.toSet());
        Map<Long, String> posterNames = users.findAllById(posterIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName));
        return new MappingContext(posterNames, appliedAt);
    }

    private static ValidatedJob validate(JobDtos.CreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        String title = text(request == null ? null : request.title(),
                "title", "Job title", 200, errors);
        String company = text(request == null ? null : request.company(),
                "company", "Company", 200, errors);
        String jobUrl = text(request == null ? null : request.jobUrl(),
                "jobUrl", "Job link", 2048, errors);
        if (jobUrl != null && !isSafeUrl(jobUrl)) {
            errors.put("jobUrl", "Enter a valid HTTP or HTTPS job link without credentials.");
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return new ValidatedJob(title, company, jobUrl);
    }

    private static String text(String value, String field, String label, int max,
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

    private static boolean isSafeUrl(String value) {
        if (value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private record ValidatedJob(String title, String company, String jobUrl) { }

    private record MappingContext(Map<Long, String> posterNames,
                                  Map<Long, Instant> appliedAt) {
        JobDtos.JobResponse response(JobListing listing) {
            Instant applied = appliedAt.get(listing.getId());
            return new JobDtos.JobResponse(
                    listing.getId(), listing.getTitle(), listing.getCompany(),
                    listing.getJobUrl(),
                    posterNames.getOrDefault(listing.getPostedBy(), "Community member"),
                    listing.getCreatedAt(), listing.getExperienceRequired(), listing.getDescription(),
                    applied != null, applied);
        }
    }
}
