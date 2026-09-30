package com.dsatracker.jobs;

public class TestWorkdayDirect {
    public static void main(String[] args) throws Exception {
        WorkdayJobAdapter adapter = new WorkdayJobAdapter();
        String[] urls = {
            "https://ncr.wd1.myworkdayjobs.com/ext_apac?Location_Country=c4f78be1a8f14da0ab49ce11",
            "https://ncratleos.wd1.myworkdayjobs.com/ext_apacatleos?Location_Country=c4f78be1a8f14d",
            "https://sec.wd3.myworkdayjobs.com/Samsung_Careers",
            "https://dentsuaegis.wd3.myworkdayjobs.com/en-US/DAN_GLOBAL/userHome"
        };
        
        for (String url : urls) {
            System.out.println("Testing: " + url);
            if (!adapter.supports(url)) {
                System.out.println("  NOT SUPPORTED");
                continue;
            }
            JobSource probe = new JobSource();
            probe.setUrl(url);
            try {
                JobPortalAdapter.Chunk chunk = adapter.fetchChunk(probe, 0, 5);
                System.out.println("  Fetched jobs: " + chunk.jobs().size());
                if (!chunk.jobs().isEmpty()) {
                    System.out.println("  First job: " + chunk.jobs().get(0).title());
                }
            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
            }
        }
    }
}
