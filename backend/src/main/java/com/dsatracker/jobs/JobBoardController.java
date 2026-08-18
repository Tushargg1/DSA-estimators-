package com.dsatracker.jobs;

import com.dsatracker.security.AccessService;
import com.dsatracker.web.ErrorResponse;
import com.dsatracker.web.PageResponse;
import com.dsatracker.web.ValidationException;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/jobs")
public class JobBoardController {
    private final JobBoardService jobs;
    private final AccessService access;

    public JobBoardController(JobBoardService jobs, AccessService access) {
        this.jobs = jobs;
        this.access = access;
    }

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

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ValidationException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse(exception.getErrors()));
    }
}
