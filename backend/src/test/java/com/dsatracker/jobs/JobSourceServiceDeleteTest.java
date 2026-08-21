package com.dsatracker.jobs;

import com.dsatracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Removing a source must either succeed or say why, and must also remove its postings.
 *
 * <p>The original implementation silently did nothing when the caller wasn't the owner,
 * which from the UI is indistinguishable from a dead button.
 */
class JobSourceServiceDeleteTest {

    private JobSourceRepository sources;
    private JobListingRepository listings;
    private JobSourceService service;

    @BeforeEach
    void setUp() {
        sources = mock(JobSourceRepository.class);
        listings = mock(JobListingRepository.class);
        service = new JobSourceService(
                sources,
                listings,
                mock(JobApplicationRepository.class),
                mock(UserRepository.class),
                List.of(),
                mock(JobIngestWriter.class),
                // Self-reference for the async background sweep; unused by these tests.
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    private JobSource source(long id, long owner) {
        JobSource source = new JobSource();
        source.setId(id);
        source.setAddedBy(owner);
        source.setUrl("https://www.accenture.com/in-en/careers/jobsearch");
        source.setCreatedAt(Instant.now());
        return source;
    }

    @Test
    void ownerCanDeleteAndItsListingsAreDeletedToo() {
        JobSource existing = source(7L, 42L);
        when(sources.findById(7L)).thenReturn(Optional.of(existing));

        JobListing attached = new JobListing();
        attached.setId(1L);
        attached.setSourceId(7L);
        List<JobListing> attachedList = new ArrayList<>(List.of(attached));
        when(listings.findBySourceIdOrderByCreatedAtDesc(7L)).thenReturn(attachedList);

        service.deleteSource(42L, 7L);

        assertAll(
                () -> verify(sources).delete(existing),
                // Removing a company removes its postings, not just the source row.
                () -> verify(listings).deleteAll(attachedList),
                () -> verify(listings, never()).saveAll(any())
        );
    }

    @Test
    void deletingSomeoneElsesSourceIsRejectedNotIgnored() {
        when(sources.findById(7L)).thenReturn(Optional.of(source(7L, 42L)));

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service.deleteSource(99L, 7L));

        assertAll(
                () -> assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode()),
                () -> verify(sources, never()).delete(any())
        );
    }

    @Test
    void deletingMissingSourceReportsNotFound() {
        when(sources.findById(anyLong())).thenReturn(Optional.empty());

        ResponseStatusException thrown = assertThrows(ResponseStatusException.class,
                () -> service.deleteSource(42L, 404L));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void deleteWorksWhenSourceHasNoListings() {
        JobSource existing = source(8L, 42L);
        when(sources.findById(8L)).thenReturn(Optional.of(existing));
        when(listings.findBySourceIdOrderByCreatedAtDesc(8L)).thenReturn(List.of());

        service.deleteSource(42L, 8L);

        assertAll(
                () -> verify(sources).delete(existing),
                () -> verify(listings, never()).saveAll(any())
        );
    }

    @Test
    void addingTheSameCareerPageTwiceIsRejected() {
        JobSource existing = source(1L, 42L);
        when(sources.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(existing));

        var request = new JobDtos.CreateSourceRequest(
                // Same page, different casing — still a duplicate.
                "https://WWW.Accenture.com/in-en/careers/jobsearch", "Accenture");

        var thrown = assertThrows(com.dsatracker.web.ValidationException.class,
                () -> service.addSource(42L, request));

        assertTrue(thrown.getErrors().containsKey("url"),
                "expected a url validation error, got: " + thrown.getErrors());
    }
}
