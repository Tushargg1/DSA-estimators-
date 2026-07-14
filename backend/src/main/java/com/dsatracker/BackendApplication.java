package com.dsatracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the DSA Progress Tracker backend.
 *
 * <p>{@link EnableScheduling} activates Spring's scheduling support (part of
 * spring-context), which the 5-minute submission polling job relies on
 * (see design.md "Polling Job Design").
 */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
