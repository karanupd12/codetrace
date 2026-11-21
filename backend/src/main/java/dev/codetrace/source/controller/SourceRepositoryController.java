package dev.codetrace.source.controller;

import dev.codetrace.jobs.controller.dto.JobResponse;
import dev.codetrace.source.controller.dto.IndexRequest;
import dev.codetrace.source.controller.dto.RegisterRepositoryRequest;
import dev.codetrace.source.controller.dto.RegisteredResponse;
import dev.codetrace.source.controller.dto.RevisionResponse;
import dev.codetrace.source.controller.dto.SourceRepositoryResponse;
import dev.codetrace.source.service.SourceRepositoryService;
import dev.codetrace.source.service.SourceRepositoryService.Registered;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registering repositories, their revisions, and requesting sync and indexing. */
@RestController
@Profile("api")
@RequestMapping("/api/v1/repositories")
public class SourceRepositoryController {
    private final SourceRepositoryService service;

    public SourceRepositoryController(SourceRepositoryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RegisteredResponse> register(@Valid @RequestBody RegisterRepositoryRequest req) {
        Registered r = service.register(req.sourceType(), req.location(), req.displayName());
        RegisteredResponse body = new RegisteredResponse(SourceRepositoryResponse.from(r.repo()), JobResponse.from(r.syncJob()));
        return ResponseEntity.status(r.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(body);
    }

    @GetMapping
    public List<SourceRepositoryResponse> list() {
        return service.list().stream().map(SourceRepositoryResponse::from).toList();
    }

    @GetMapping("/{id}")
    public SourceRepositoryResponse get(@PathVariable UUID id) {
        return SourceRepositoryResponse.from(service.get(id));
    }

    @GetMapping("/{id}/revisions")
    public List<RevisionResponse> revisions(@PathVariable UUID id) {
        return service.revisions(id).stream().map(RevisionResponse::from).toList();
    }

    @PostMapping("/{id}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse resync(@PathVariable UUID id) {
        return JobResponse.from(service.resync(id));
    }

    @PostMapping("/{id}/index-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobResponse requestIndex(@PathVariable UUID id, @Valid @RequestBody(required = false) IndexRequest req) {
        return JobResponse.from(service.requestIndex(id, req == null ? null : req.commitHash()));
    }

    @GetMapping("/{id}/index-jobs")
    public List<JobResponse> jobs(@PathVariable UUID id) {
        return service.jobsFor(id).stream().map(JobResponse::from).toList();
    }
}
