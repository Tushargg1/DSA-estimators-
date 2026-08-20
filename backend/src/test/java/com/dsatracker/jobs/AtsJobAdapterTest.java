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
 * Slug parsing and URL claiming for the ATS adapters, plus opt-in live checks.
 *
 * <p>Slug tests matter most: an adapter that mis-claims a URL would shadow the correct
 * one, and a wrong slug silently yields an empty board. Live calls are gated behind
 * {@code -DliveApiTests=true} so deploy builds don't depend on third-party uptime.
 */
class AtsJobAdapterTest {

    private final GreenhouseJobAdapter greenhouse = new GreenhouseJobAdapter();
    private final LeverJobAdapter lever = new LeverJobAdapter();
    private final AshbyJobAdapter ashby = new AshbyJobAdapter();

    @Test
    void greenhouseParsesSlugFromBoardAndApiUrls() {
        assertAll(
                () -> assertTrue(greenhouse.supports("https://boards.greenhouse.io/figma")),
                () -> assertTrue(greenhouse.supports("https://boards.greenhouse.io/figma/jobs/5364702004")),
                () -> assertTrue(greenhouse.supports("https://job-boards.greenhouse.io/acme")),
                () -> assertTrue(greenhouse.supports(
                        "https://boards.greenhouse.io/embed/job_board?for=stripe")),
                () -> assertFalse(greenhouse.supports("https://jobs.lever.co/nium")),
                () -> assertFalse(greenhouse.supports("https://example.com/careers")),
                () -> assertFalse(greenhouse.supports(null))
        );
    }

    @Test
    void leverParsesSlugAndIgnoresOtherHosts() {
        assertAll(
                () -> assertTrue(lever.supports("https://jobs.lever.co/nium")),
                () -> assertTrue(lever.supports("https://jobs.lever.co/nium/beca0f56-105d")),
                () -> assertTrue(lever.supports("https://api.lever.co/v0/postings/nium")),
                () -> assertFalse(lever.supports("https://boards.greenhouse.io/figma")),
                () -> assertFalse(lever.supports(null))
        );
    }

    @Test
    void ashbyParsesSlugAndIgnoresOtherHosts() {
        assertAll(
                () -> assertTrue(ashby.supports("https://jobs.ashbyhq.com/linear")),
                () -> assertTrue(ashby.supports("https://jobs.ashbyhq.com/linear/d3bc1ced")),
                () -> assertTrue(ashby.supports("https://api.ashbyhq.com/posting-api/job-board/linear")),
                () -> assertFalse(ashby.supports("https://jobs.lever.co/nium")),
                () -> assertFalse(ashby.supports(null))
        );
    }

    /** Each adapter must claim only its own host, or lookup order would decide behaviour. */
    @Test
    void adaptersDoNotClaimEachOthersUrls() {
        String[] urls = {
                "https://boards.greenhouse.io/figma",
                "https://jobs.lever.co/nium",
                "https://jobs.ashbyhq.com/linear",
                "https://www.accenture.com/in-en/careers/jobsearch"
        };
        JobPortalAdapter[] all = { greenhouse, lever, ashby, new AccentureJobAdapter() };
        for (String url : urls) {
            long claims = java.util.Arrays.stream(all).filter(a -> a.supports(url)).count();
            assertEquals(1, claims, "exactly one adapter should claim " + url);
        }
    }

    @Test
    void entityEscapedHtmlIsDecodedBeforeTagStripping() {
        // Greenhouse returns HTML that is itself entity-escaped; stripping before
        // decoding would leave the markup visible as literal text.
        String raw = "&lt;p&gt;Build with &lt;b&gt;Java&lt;/b&gt; &amp;amp; Spring&lt;/p&gt;";
        String plain = AbstractJsonJobAdapter.toPlainText(raw);
        assertAll(
                () -> assertNotNull(plain),
                () -> assertFalse(plain.contains("<"), "tags should be gone: " + plain),
                () -> assertFalse(plain.contains("&lt;"), "entities should be decoded: " + plain),
                () -> assertTrue(plain.contains("Java"), plain),
                () -> assertTrue(plain.contains("Spring"), plain)
        );
    }

    @Test
    void plainTextHandlesNullAndBlank() {
        assertAll(
                () -> assertNull(AbstractJsonJobAdapter.toPlainText(null)),
                () -> assertNull(AbstractJsonJobAdapter.toPlainText("   ")),
                () -> assertNull(AbstractJsonJobAdapter.toPlainText("<p></p>"))
        );
    }

    // --- live checks ---

    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void greenhouseFetchesRealJobs() throws Exception {
        JobSource source = new JobSource();
        source.setUrl("https://boards.greenhouse.io/figma");

        JobPortalAdapter.Chunk chunk = greenhouse.fetchChunk(source, 0, 100);
        assertFalse(chunk.jobs().isEmpty(), "figma board should return postings");
        assertTrue(chunk.exhausted(), "Greenhouse has no paging, so one chunk is the whole board");

        ScrapedJob job = chunk.jobs().get(0);
        System.out.println("Greenhouse sample -> " + job.title() + " | " + job.location() + " | " + job.url());
        assertAll(
                () -> assertNotNull(job.externalId()),
                () -> assertNotNull(job.title()),
                () -> assertTrue(job.url().contains("greenhouse.io"), job.url()),
                () -> assertNotNull(job.description(), "content=true should yield a description"),
                () -> assertFalse(job.description().contains("&lt;"), "description must be decoded")
        );
    }

    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void leverFetchesRealJobsAndPages() throws Exception {
        JobSource source = new JobSource();
        source.setUrl("https://jobs.lever.co/nium");

        JobPortalAdapter.Chunk first = lever.fetchChunk(source, 0, 5);
        assertFalse(first.jobs().isEmpty(), "nium board should return postings");
        assertFalse(first.exhausted(), "a full page should not report exhausted");

        ScrapedJob job = first.jobs().get(0);
        System.out.println("Lever sample -> " + job.title() + " | " + job.location()
                + " | " + job.employmentType() + " | " + job.url());
        assertAll(
                () -> assertNotNull(job.externalId()),
                () -> assertNotNull(job.title()),
                () -> assertTrue(job.url().contains("lever.co"), job.url())
        );

        // skip/limit must actually move the window, otherwise the sweep would loop.
        JobPortalAdapter.Chunk second = lever.fetchChunk(source, 5, 5);
        var firstIds = first.jobs().stream().map(ScrapedJob::externalId).toList();
        var secondIds = second.jobs().stream().map(ScrapedJob::externalId).toList();
        assertFalse(secondIds.stream().anyMatch(firstIds::contains),
                "paged windows should not overlap");
    }

    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void ashbyFetchesRealJobs() throws Exception {
        JobSource source = new JobSource();
        source.setUrl("https://jobs.ashbyhq.com/linear");

        JobPortalAdapter.Chunk chunk = ashby.fetchChunk(source, 0, 100);
        assertFalse(chunk.jobs().isEmpty(), "linear board should return postings");

        ScrapedJob job = chunk.jobs().get(0);
        System.out.println("Ashby sample -> " + job.title() + " | " + job.location()
                + " | " + job.employmentType() + " | " + job.url());
        assertAll(
                () -> assertNotNull(job.externalId()),
                () -> assertNotNull(job.title()),
                () -> assertTrue(job.url().contains("ashbyhq.com"), job.url())
        );
    }

    /** An unknown Lever slug returns an error envelope, not an empty array. */
    @Test
    @EnabledIfSystemProperty(named = "liveApiTests", matches = "true")
    void leverReportsUnknownBoardClearly() {
        JobSource source = new JobSource();
        source.setUrl("https://jobs.lever.co/definitely-not-a-real-company-xyz");

        Exception thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> lever.fetchChunk(source, 0, 5));
        assertTrue(thrown.getMessage().toLowerCase().contains("not found"),
                "expected a not-found style message, got: " + thrown.getMessage());
    }
}
