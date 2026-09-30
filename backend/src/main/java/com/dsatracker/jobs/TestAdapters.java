package com.dsatracker.jobs;

public class TestAdapters {
    public static void main(String[] args) throws Exception {

        System.out.println("Testing LGE...");
        LgeJobAdapter lge = new LgeJobAdapter();
        JobSource lgeSource = new JobSource();
        lgeSource.setUrl("https://globalcareers.lge.com/jobs");
        JobPortalAdapter.Chunk c = lge.fetchChunk(lgeSource, 0, 100);
        System.out.println("LGE jobs: " + c.jobs().size());
        if (!c.jobs().isEmpty()) {
            System.out.println("Sample LGE job: " + c.jobs().get(0).title() + " at " + c.jobs().get(0).url());
        }

        System.out.println("Testing TalentRecruit...");
        TalentRecruitJobAdapter tr = new TalentRecruitJobAdapter();
        JobSource trSource = new JobSource();
        trSource.setUrl("https://kpmgindia.talentrecruit.com/career-page");
        JobPortalAdapter.Chunk c2 = tr.fetchChunk(trSource, 0, 100);
        System.out.println("KPMG jobs: " + c2.jobs().size());
        if (!c2.jobs().isEmpty()) {
            System.out.println("Sample KPMG job: " + c2.jobs().get(0).title() + " at " + c2.jobs().get(0).url());
        }

        System.out.println("Testing CareersAtTech...");
        CareersAtTechJobAdapter cat = new CareersAtTechJobAdapter();
        JobSource catSource = new JobSource();
        catSource.setUrl("https://careersat.tech/jobs");
        JobPortalAdapter.Chunk c3 = cat.fetchChunk(catSource, 0, 100);
        System.out.println("CareersAtTech jobs: " + c3.jobs().size());
        if (!c3.jobs().isEmpty()) {
            System.out.println("Sample CareersAtTech job: " + c3.jobs().get(0).title() + " at " + c3.jobs().get(0).url());
        }
    }
}
