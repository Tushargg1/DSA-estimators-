
package com.dsatracker;
import com.dsatracker.jobs.*;
import java.util.List;

public class TestWorkdayAdapter {
    public static void main(String[] args) throws Exception {
        WorkdayJobAdapter adapter = new WorkdayJobAdapter();
        
        JobSource source1 = new JobSource();
        source1.setUrl("https://ncratleos.wd1.myworkdayjobs.com/ext_apacatleos?Location_Country=c4f78be1a8f14da0ab49ce11...");
        System.out.println("Supports NCR? " + adapter.supports(source1.getUrl()));
        
        try {
            JobPortalAdapter.Chunk chunk = adapter.fetchChunk(source1, 0, 10);
            System.out.println("NCR Jobs found: " + chunk.jobs().size());
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
}

