package com.dsatracker.jobs;

import com.dsatracker.security.AccessService;
import com.dsatracker.web.ErrorResponse;
import com.dsatracker.web.PageResponse;
import com.dsatracker.web.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobBoardController {
    private final JobBoardService jobs;
    private final JobProfileService profileService;
    private final JobSourceService sourceService;
    private final ResumeParserService resumeParser;
    private final AccessService access;

    public JobBoardController(JobBoardService jobs,
                              JobProfileService profileService,
                              JobSourceService sourceService,
                              ResumeParserService resumeParser,
                              AccessService access) {
        this.jobs = jobs;
        this.profileService = profileService;
        this.sourceService = sourceService;
        this.resumeParser = resumeParser;
        this.access = access;
    }

    // --- Listings ---

    @GetMapping
    public PageResponse<JobDtos.JobResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return jobs.list(access.userId(authentication), page, size);
    }

    @PostMapping
    public ResponseEntity<JobDtos.JobResponse> create(
            @RequestBody JobDtos.CreateRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(jobs.create(access.userId(authentication), request));
    }

    @PutMapping("/{id}/applied")
    public JobDtos.JobResponse markApplied(@PathVariable Long id,
                                           Authentication authentication) {
        return jobs.markApplied(access.userId(authentication), id);
    }

    @DeleteMapping("/{id}/applied")
    public JobDtos.JobResponse unmarkApplied(@PathVariable Long id,
                                             Authentication authentication) {
        return jobs.unmarkApplied(access.userId(authentication), id);
    }

    // --- Profiles ---

    @GetMapping("/profiles")
    public List<JobDtos.ProfileResponse> listProfiles(
            @RequestParam(required = false) Integer experience,
            Authentication authentication) {
        return profileService.listProfiles(access.userId(authentication), experience);
    }

    @PostMapping("/profiles")
    public ResponseEntity<JobDtos.ProfileResponse> createProfile(
            @RequestBody JobDtos.CreateProfileRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(profileService.createProfile(access.userId(authentication), request));
    }

    @PostMapping(value = "/profiles/upload-resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobDtos.ResumeParseResponse> uploadResume(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        access.userId(authentication); // Ensure authenticated
        try {
            ResumeParserService.ParseResult result = resumeParser.parseResume(file);
            return ResponseEntity.ok(new JobDtos.ResumeParseResponse(
                    result.suggestedRole(),
                    result.detectedKeywords(),
                    result.extractedText(),
                    result.categoryScores(),
                    file.getOriginalFilename()
            ));
        } catch (IOException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(new JobDtos.ResumeParseResponse(null, null, null, Map.of(), null));
        }
    }

    @DeleteMapping("/profiles/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable Long id,
                                              Authentication authentication) {
        profileService.deleteProfile(access.userId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // --- Sources ---

    @GetMapping("/sources")
    public List<JobDtos.SourceResponse> listSources() {
        return sourceService.listSources();
    }

    @PostMapping("/sources")
    public ResponseEntity<JobDtos.SourceResponse> addSource(
            @RequestBody JobDtos.CreateSourceRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sourceService.addSource(access.userId(authentication), request));
    }

    @PostMapping("/sources/{id}/scrape")
    public JobDtos.ScrapeResult scrapeSource(@PathVariable Long id,
                                             Authentication authentication) {
        return sourceService.scrapeSource(access.userId(authentication), id);
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> deleteSource(@PathVariable Long id,
                                             Authentication authentication) {
        sourceService.deleteSource(access.userId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ValidationException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse(exception.getErrors()));
    }
}
