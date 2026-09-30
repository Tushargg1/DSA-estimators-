package com.dsatracker.jobs;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.autoconfigure.SpringBootApplication;

public class TestSpring {
    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(com.dsatracker.BackendApplication.class, args);
        JobSourceService service = ctx.getBean(JobSourceService.class);
        String url = "https://ncr.wd1.myworkdayjobs.com/ext_apac?Location_Country=c4f78be1a8f14da0ab49ce11";
        System.out.println("Checking support for: " + url);
        JobDtos.SupportCheck check = service.checkSupport(url);
        System.out.println("Result: " + check.level() + ", " + check.adapter() + ", " + check.message());
        
        System.exit(0);
    }
}
