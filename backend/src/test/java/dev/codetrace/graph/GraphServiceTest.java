package dev.codetrace.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.codetrace.graph.GraphSnapshotDao.Snapshot;
import dev.codetrace.jobs.JobDao;
import dev.codetrace.jobs.JobDao.IndexedRevision;
import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.jobs.JobPublisher;
import dev.codetrace.parser.JavaSymbolExtractor;
import dev.codetrace.repository.RepositoryDao;
import dev.codetrace.repository.RepositoryDao.Repo;
import dev.codetrace.repository.SourceType;
import dev.codetrace.system.ApiException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GraphServiceTest {
    private static final String COMMIT = "a".repeat(40);

    private final UUID repoId = UUID.randomUUID();
    private final UUID indexJobId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID buildJobId = UUID.randomUUID();

    private RepositoryDao repos;
    private JobDao jobs;
    private GraphSnapshotDao snapshots;
    private JobPublisher publisher;
    private GraphService service;

    @BeforeEach
    void setUp() {
        repos = mock(RepositoryDao.class);
        jobs = mock(JobDao.class);
        snapshots = mock(GraphSnapshotDao.class);
        publisher = mock(JobPublisher.class);
        service = new GraphService(repos, jobs, snapshots, publisher);

        when(repos.find(repoId)).thenReturn(Optional.of(new Repo(repoId, SourceType.GIT_URL, "https://github.com/o/r", "o/r",
                "SYNCED", "main", COMMIT, OffsetDateTime.now())));
        when(repos.revisionExists(repoId, COMMIT)).thenReturn(true);
        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.of(
                new IndexedRevision(indexJobId, JavaSymbolExtractor.PARSER_VERSION, JavaSymbolExtractor.EXTRACTION_VERSION)));
        when(snapshots.find(snapshotId)).thenAnswer(i -> Optional.of(snapshot("REQUESTED")));
    }

    private Snapshot snapshot(String status) {
        return new Snapshot(snapshotId, repoId, COMMIT, JavaSymbolExtractor.PARSER_VERSION, GraphBuilder.VERSION,
                indexJobId, status, null, null, null, null, null, null, OffsetDateTime.now(),
                status.equals("PUBLISHED") ? OffsetDateTime.now() : null);
    }

    private Job buildJob(String status) {
        return new Job(buildJobId, repoId, "GRAPH_BUILD", status, COMMIT, 0, null, null, OffsetDateTime.now(), null, null,
                null, null, null, null);
    }

    private void givenSnapshot(String snapStatus, String jobStatus, boolean jobCreated) {
        when(snapshots.createOrGet(eq(repoId), eq(COMMIT), anyString(), eq(GraphBuilder.VERSION), eq(indexJobId)))
                .thenReturn(new GraphSnapshotDao.Created(snapshot(snapStatus), jobCreated));
        when(snapshots.find(snapshotId)).thenReturn(Optional.of(snapshot(snapStatus)));
        when(jobs.createOrGet(eq(repoId), eq("GRAPH_BUILD"), eq(COMMIT), anyString()))
                .thenReturn(new JobDao.Created(buildJob(jobStatus), jobCreated));
        when(jobs.find(buildJobId)).thenReturn(Optional.of(buildJob(jobStatus)));
    }

    private static void assertStatus(Runnable r, HttpStatus status) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(status));
    }

    // ---- preconditions ---------------------------------------------------------------------------------------

    @Test
    void unknownRepositoriesAndRevisionsAre404() {
        assertStatus(() -> service.requestBuild(UUID.randomUUID(), null), HttpStatus.NOT_FOUND);
        when(repos.revisionExists(repoId, COMMIT)).thenReturn(false);
        assertStatus(() -> service.requestBuild(repoId, COMMIT), HttpStatus.NOT_FOUND);
    }

    @Test
    void malformedCommitsAre400AndUnsyncedRepositoriesAre409() {
        assertStatus(() -> service.requestBuild(repoId, "main"), HttpStatus.BAD_REQUEST);
        when(repos.find(repoId)).thenReturn(Optional.of(new Repo(repoId, SourceType.GIT_URL, "https://github.com/o/r", "o/r",
                "PENDING", null, null, OffsetDateTime.now())));
        assertStatus(() -> service.requestBuild(repoId, null), HttpStatus.CONFLICT);
    }

    @Test
    void anUnindexedRevisionIs409AndAnOldExtractionAsksForReindexing() {
        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.empty());
        assertStatus(() -> service.requestBuild(repoId, COMMIT), HttpStatus.CONFLICT);

        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.of(
                new IndexedRevision(indexJobId, JavaSymbolExtractor.PARSER_VERSION, 1)));
        assertThatThrownBy(() -> service.requestBuild(repoId, COMMIT)).hasMessageContaining("Re-index");
        verify(publisher, never()).publish(any());
    }

    // ---- idempotency ------------------------------------------------------------------------------------------

    @Test
    void aNewRequestQueuesExactlyOneBuildJob() {
        givenSnapshot("REQUESTED", "QUEUED", true);

        GraphService.Requested r = service.requestBuild(repoId, COMMIT);

        assertThat(r.snapshot().id()).isEqualTo(snapshotId);
        verify(publisher).publish(buildJobId);
    }

    @Test
    void aPublishedSnapshotIsReturnedWithoutAnyWork() {
        givenSnapshot("PUBLISHED", "SUCCEEDED", false);

        GraphService.Requested r = service.requestBuild(repoId, COMMIT);

        assertThat(r.snapshot().published()).isTrue();
        verify(publisher, never()).publish(any());
        verify(jobs, never()).resetForRerun(any());
    }

    @Test
    void aRunningOrQueuedBuildIsNotDuplicated() {
        for (String status : new String[] {"QUEUED", "RUNNING"}) {
            givenSnapshot("BUILDING", status, false);
            service.requestBuild(repoId, COMMIT);
        }
        verify(publisher, never()).publish(any());
        verify(jobs, never()).resetForRerun(any());
    }

    @Test
    void aFailedBuildIsQueuedAgain() {
        givenSnapshot("FAILED", "FAILED", false);
        when(jobs.resetForRerun(buildJobId)).thenReturn(true);

        service.requestBuild(repoId, COMMIT);

        verify(snapshots).requeue(snapshotId);
        verify(publisher).publish(buildJobId);
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    @Test
    void onlyPublishedSnapshotsAreReadable() {
        for (String status : new String[] {"REQUESTED", "BUILDING", "VALIDATING", "FAILED"}) {
            when(snapshots.find(snapshotId)).thenReturn(Optional.of(snapshot(status)));
            assertStatus(() -> service.getPublished(snapshotId), HttpStatus.CONFLICT);
        }
        when(snapshots.find(snapshotId)).thenReturn(Optional.of(snapshot("PUBLISHED")));
        assertThat(service.getPublished(snapshotId).published()).isTrue();
        assertStatus(() -> service.getPublished(UUID.randomUUID()), HttpStatus.NOT_FOUND);
    }
}
