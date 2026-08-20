package com.dsatracker.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the Accenture adapter.
 *
 * <p>Pure mapping/parsing checks always run. The tests that actually call the portal
 * are opt-in via {@code -DliveApiTests=true}, so a deploy build cannot fail because
 * the host blocks outbound traffic or the portal rate-limits us. Run them manually
 * when you need to confirm the portal's contract still holds:
 *
 * <pre>mvn test -Dtest=AccentureJobAdapterLiveTest -DliveApiTests=true</pre>
 */
class AccentureJobAdapterLiveTest {

    private final AccentureJobAdapter adapter = new AccentureJobAdapter();

    @Test
    void localeIsDerivedFromSourceUrl() {
        assertAll(
                () -> assertEquals("in-en",
                        AccentureJobAdapter.localeFrom("https://www.accenture.com/in-en/careers/jobsearch")),
                () -> assertEquals("us-en",
                        AccentureJobAdapter.localeFrom("https://www.accenture.com/us-en/careers")),
                // No locale segment in the URL falls back to the default.
                () -> assertEquals("us-en",
                        AccentureJobAdapter.localeFrom("https://www.accenture.com/careers")),
                () -> assertEquals("us-en", AccentureJobAdapter.localeFrom(null))
        );
    }

    @Test
    void supportsOnlyAccentureCareerUrls() {
        assertAll(
                () -> assertTrue(adapter.supports("https://www.accenture.com/in-en/careers/jobsearch")),
                () -> assertFalse(adapter.supports("https://www.google.com/careers")),
                () -> assertFalse(adapter.supports("https://www.accenture.com/in-en/about")),
                () -> assertFalse(adapter.supports(null))
        );
    }

    @Test
    void careerLevelMapsToApproximateExperience() {
        assertAll(
                () -> assertEquals(0, JobSourceService.experienceFromCareerLevel("Early Career")),
                () -> assertEquals(0, JobSourceService.experienceFromCareerLevel("Internship")),
                () -> assertEquals(2, JobSourceService.experienceFromCareerLevel("Mid-Level")),
                () -> assertEquals(5, JobSourceService.experienceFromCareerLevel("Senior Level")),
                () -> assertNull(JobSourceService.experienceFromCareerLevel(null)),
                () -> assertNull(JobSourceService.experienceFromCareerLevel("Unspecified"))
        );
    }

    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void fetchesRealPostingsWithStructuredFields() throws Exception {
        JobSource source = new JobSource();
        source.setUrl("https://www.accenture.com/in-en/careers/jobsearch");

        JobPortalAdapter.Chunk chunk = adapter.fetchChunk(source, 0, 5);

        assertFalse(chunk.jobs().isEmpty(), "expected the API to return postings");
        assertFalse(chunk.exhausted(), "a full 5-row chunk should not report exhausted");

        ScrapedJob job = chunk.jobs().get(0);
        System.out.println("Sample posting -> " + job);

        assertAll(
                () -> assertNotNull(job.externalId(), "requisitionId should map to externalId"),
                () -> assertNotNull(job.title(), "title is required"),
                () -> assertNotNull(job.url(), "apply url is required"),
                // The {0} placeholder must be substituted or the link 404s.
                () -> assertFalse(job.url().contains("{0}"), "locale placeholder must be replaced"),
                () -> assertTrue(job.url().contains("/in-en/"), "url should carry the derived locale"),
                () -> assertTrue(job.url().contains("jobdetails"), "url should be a per-job detail link")
        );
    }

    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void pagingDeepIntoResultsReturnsDifferentJobs() throws Exception {
        JobSource source = new JobSource();
        source.setUrl("https://www.accenture.com/in-en/careers/jobsearch");

        var first = adapter.fetchChunk(source, 0, 5);
        var deep = adapter.fetchChunk(source, 2000, 5);

        assertFalse(first.jobs().isEmpty());
        assertFalse(deep.jobs().isEmpty(), "deep offsets must still return rows for chunked sweeps to work");

        var firstIds = first.jobs().stream().map(ScrapedJob::externalId).toList();
        var deepIds = deep.jobs().stream().map(ScrapedJob::externalId).toList();
        assertFalse(firstIds.stream().anyMatch(deepIds::contains),
                "offset 0 and offset 2000 should not overlap");
    }
}
