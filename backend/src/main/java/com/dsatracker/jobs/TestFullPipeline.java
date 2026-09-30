package com.dsatracker.jobs;

import java.util.List;
import java.util.ArrayList;

public class TestFullPipeline {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting TestFullPipeline...");
        
        JobSourceRepository sources = new JobSourceRepository() {
            @Override public void flush() {}
            @Override public <S extends JobSource> S saveAndFlush(S entity) { return entity; }
            @Override public <S extends JobSource> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { return null; }
            @Override public void deleteAllInBatch(Iterable<JobSource> entities) {}
            @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {}
            @Override public void deleteAllInBatch() {}
            @Override public JobSource getOne(Long id) { return null; }
            @Override public JobSource getById(Long id) { return null; }
            @Override public JobSource getReferenceById(Long id) { return null; }
            @Override public <S extends JobSource> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { return null; }
            @Override public <S extends JobSource> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return null; }
            @Override public <S extends JobSource> java.util.List<S> saveAll(Iterable<S> entities) { return null; }
            @Override public java.util.List<JobSource> findAll() { return null; }
            @Override public java.util.List<JobSource> findAllById(Iterable<Long> ids) { return null; }
            @Override public <S extends JobSource> S save(S entity) { return entity; }
            @Override public java.util.Optional<JobSource> findById(Long id) { return java.util.Optional.of(new JobSource()); }
            @Override public boolean existsById(Long id) { return false; }
            @Override public long count() { return 0; }
            @Override public void deleteById(Long id) {}
            @Override public void delete(JobSource entity) {}
            @Override public void deleteAllById(Iterable<? extends Long> ids) {}
            @Override public void deleteAll(Iterable<? extends JobSource> entities) {}
            @Override public void deleteAll() {}
            @Override public java.util.List<JobSource> findAll(org.springframework.data.domain.Sort sort) { return null; }
            @Override public org.springframework.data.domain.Page<JobSource> findAll(org.springframework.data.domain.Pageable pageable) { return null; }
            @Override public <S extends JobSource> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return java.util.Optional.empty(); }
            @Override public <S extends JobSource> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return null; }
            @Override public <S extends JobSource> long count(org.springframework.data.domain.Example<S> example) { return 0; }
            @Override public <S extends JobSource> boolean exists(org.springframework.data.domain.Example<S> example) { return false; }
            @Override public <S extends JobSource, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
            // Missing method from previous error
            @Override public java.util.List<JobSource> findAllByOrderByCreatedAtDesc() { return null; }
        };
        
        JobIngestWriter writer = new JobIngestWriter(sources, null, null, null) {
            @Override
            public int persistChunk(Long sourceId, Long postedBy, String company, List<ScrapedJob> jobs, Long targetProfileId) {
                System.out.println("  [Writer] persistChunk called with " + jobs.size() + " jobs.");
                int saved = 0;
                for (ScrapedJob job : jobs) {
                    // System.out.println("    -> SAVING: " + job.title() + " | URL: " + job.url());
                    saved++;
                }
                return saved;
            }
            
            @Override
            public void markProgress(Long sourceId, String adapter, int cursor,
                                     boolean sweepComplete, String error, String status,
                                     Integer totalSeen, Integer totalMatched) {
                System.out.println("  [Writer] Progress marked: status=" + status + ", totalSeen=" + totalSeen + ", saved=" + totalMatched);
            }
        };
        
        List<JobPortalAdapter> adapters = new ArrayList<>();
        adapters.add(new WorkdayJobAdapter());
        adapters.add(new DarwinboxJobAdapter());
        adapters.add(new CareersAtTechJobAdapter());
        adapters.add(new LgeJobAdapter());
        adapters.add(new TalentRecruitJobAdapter());
        adapters.add(new GreenhouseJobAdapter());
        adapters.add(new LeverJobAdapter());
        adapters.add(new IcimsJobAdapter());
        adapters.add(new AccentureJobAdapter());
        adapters.add(new JioJobAdapter());
        adapters.add(new SmartRecruitersJobAdapter());
        adapters.add(new AshbyJobAdapter());
        
        JobSourceService service = new JobSourceService(sources, null, null, null, adapters, writer, null);
        
        java.lang.reflect.Method ingestMethod = JobSourceService.class.getDeclaredMethod("ingest", JobSource.class, int.class, Long.class);
        ingestMethod.setAccessible(true);
        
        String[] urlsToTest = {
            "https://ncr.wd1.myworkdayjobs.com/ext_apac?Location_Country=c4f78be1a8f14da0ab49ce11",
            "https://ncratleos.wd1.myworkdayjobs.com/ext_apacatleos?Location_Country=c4f78be1a8f14d",
            "https://sec.wd3.myworkdayjobs.com/Samsung_Careers",
            "https://airtel.darwinbox.in/ms/candidatev2/main/careers/allJobs",
            "https://careersat.tech/jobs"
        };
        
        long id = 1;
        for (String url : urlsToTest) {
            JobSource s = new JobSource();
            s.setId(id++);
            s.setUrl(url);
            s.setLabel("TestSite");
            System.out.println("\n=============================================");
            System.out.println("Scraping Site: " + url);
            JobDtos.ScrapeResult result = (JobDtos.ScrapeResult) ingestMethod.invoke(service, s, 1, null);
            System.out.println("Result -> New Listings: " + result.newListings() + " | Total Jobs Seen: " + result.totalJobsSeen());
            System.out.println("=============================================\n");
        }
        
        System.exit(0);
    }
}
