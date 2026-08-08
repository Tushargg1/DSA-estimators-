package com.dsatracker.github;

import com.dsatracker.security.AccessService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/github")
public class GitHubController {
    private final GitHubConnectionService service;
    private final GitHubCaptureService captureService;
    private final GitHubWorkflowSaveService workflowSaves;
    private final AccessService access;

    public GitHubController(GitHubConnectionService service,
                            GitHubCaptureService captureService,
                            GitHubWorkflowSaveService workflowSaves,
                            AccessService access) {
        this.service = service;
        this.captureService = captureService;
        this.workflowSaves = workflowSaves;
        this.access = access;
    }

    @GetMapping("/status")
    public GitHubDtos.StatusResponse status(Authentication authentication) {
        return service.status(access.userId(authentication));
    }

    @PostMapping("/connect")
    public GitHubDtos.ConnectResponse connect(Authentication authentication) {
        return service.connect(access.userId(authentication));
    }

    @PostMapping("/complete")
    public GitHubDtos.StatusResponse complete(@RequestBody GitHubDtos.CompleteRequest request,
                                              Authentication authentication) {
        return service.complete(access.userId(authentication), request);
    }

    @GetMapping("/repositories")
    public List<GitHubDtos.RepositoryResponse> repositories(Authentication authentication) {
        return service.repositories(access.userId(authentication));
    }

    @PutMapping("/repository")
    public GitHubDtos.StatusResponse selectRepository(
            @RequestBody GitHubDtos.SelectRepositoryRequest request,
            Authentication authentication) {
        return service.selectRepository(access.userId(authentication), request);
    }

    @PostMapping("/extension-token")
    public GitHubDtos.ExtensionTokenResponse extensionToken(Authentication authentication) {
        return service.issueExtensionToken(access.userId(authentication));
    }

    @GetMapping("/captures")
    public List<GitHubDtos.CaptureExportResponse> captures(Authentication authentication) {
        return captureService.exports(access.userId(authentication));
    }

    @GetMapping("/workflow-save")
    public GitHubDtos.WorkflowSaveStatusResponse workflowSaveStatus(
            Authentication authentication) {
        return workflowSaves.status(access.userId(authentication));
    }

    @PostMapping("/workflow-save")
    public GitHubDtos.WorkflowSaveStatusResponse requestWorkflowSave(
            Authentication authentication) {
        return workflowSaves.request(access.userId(authentication));
    }

    @PostMapping("/workflow-save/claim")
    public GitHubDtos.WorkflowSaveClaimResponse claimWorkflowSave(
            Authentication authentication) {
        return workflowSaves.claim(access.userId(authentication));
    }

    @PostMapping("/workflow-save/complete")
    public GitHubDtos.WorkflowSaveStatusResponse completeWorkflowSave(
            @RequestBody GitHubDtos.WorkflowSaveCompleteRequest request,
            Authentication authentication) {
        return workflowSaves.complete(access.userId(authentication), request);
    }

    @PostMapping("/workflow-save/fail")
    public GitHubDtos.WorkflowSaveStatusResponse failWorkflowSave(
            @RequestBody GitHubDtos.WorkflowSaveFailureRequest request,
            Authentication authentication) {
        return workflowSaves.fail(access.userId(authentication), request);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(Authentication authentication) {
        service.delete(access.userId(authentication));
        return ResponseEntity.noContent().build();
    }
}
