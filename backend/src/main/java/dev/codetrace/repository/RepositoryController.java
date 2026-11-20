package dev.codetrace.repository;

import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.repository.RepositoryService.Registered;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class RepositoryController {
    public record CreateRepositoryRequest(@NotNull SourceType sourceType,
                                          @NotBlank @Size(max = 2048) String location,
                                          @Size(max = 100) String displayName) {
    }

    public record IndexRequest(@Size(max = 64) String commitHash) {
    }

    /** Never exposes server filesystem paths: originUrl is only set for GIT_URL sources. */
    public record RepositoryView(UUID id, SourceType sourceType, String displayName, String originUrl,
                                 String syncStatus, String defaultBranch, String headCommit,
                                 OffsetDateTime createdAt) {
        static RepositoryView of(Repo r) {
            return new RepositoryView(r.id(), r.sourceType(), r.displayName(),
                    r.sourceType() == SourceType.GIT_URL ? r.location() : null, r.syncStatus(), r.defaultBranch(),
                    r.headCommit(), r.createdAt());
        }
    }

    public record RevisionView(String commitHash, String summary, OffsetDateTime authorTime) {
    }

    public record JobView(UUID id, UUID repositoryId, String operation, String status, String commitHash,
                          int attempts, String errorCategory, String errorMessage, OffsetDateTime createdAt,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt, Integer filesParsed,
                          Integer filesSkipped, Integer symbolsExtracted, String parserVersion) {
        public static JobView of(Job j) {
            return new JobView(j.id(), j.repositoryId(), j.operation(), j.status(), j.commitHash(), j.attempts(),
                    j.errorCategory(), j.errorMessage(), j.createdAt(), j.startedAt(), j.finishedAt(),
                    j.filesParsed(), j.filesSkipped(), j.symbolsExtracted(), j.parserVersion());
        }
    }

    public record RegisteredView(RepositoryView repository, JobView syncJob) {
    }

    private final RepositoryService service;

    public RepositoryController(RepositoryService service) {
        this.service = service;
    }

    @PostMapping("/repositories")
    public ResponseEntity<RegisteredView> register(@Valid @RequestBody CreateRepositoryRequest req) {
        Registered r = service.register(req.sourceType(), req.location(), req.displayName());
        RegisteredView body = new RegisteredView(RepositoryView.of(r.repo()), JobView.of(r.syncJob()));
        return ResponseEntity.status(r.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(body);
    }

    @GetMapping("/repositories")
    public List<RepositoryView> list() {
        return service.list().stream().map(RepositoryView::of).toList();
    }

    @GetMapping("/repositories/{id}")
    public RepositoryView get(@PathVariable UUID id) {
        return RepositoryView.of(service.get(id));
    }

    @GetMapping("/repositories/{id}/revisions")
    public List<RevisionView> revisions(@PathVariable UUID id) {
        return service.revisions(id).stream()
                .map(r -> new RevisionView(r.commitHash(), r.summary(), r.authorTime())).toList();
    }

    @PostMapping("/repositories/{id}/sync")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public JobView resync(@PathVariable UUID id) {
        return JobView.of(service.resync(id));
    }

    @PostMapping("/repositories/{id}/index-jobs")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public JobView requestIndex(@PathVariable UUID id, @Valid @RequestBody(required = false) IndexRequest req) {
        return JobView.of(service.requestIndex(id, req == null ? null : req.commitHash()));
    }

    @GetMapping("/repositories/{id}/index-jobs")
    public List<JobView> jobs(@PathVariable UUID id) {
        return service.jobsFor(id).stream().map(JobView::of).toList();
    }

    @GetMapping("/index-jobs/{jobId}")
    public JobView job(@PathVariable UUID jobId) {
        return JobView.of(service.job(jobId));
    }
}
