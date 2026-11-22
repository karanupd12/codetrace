package dev.codetrace.graph.service;

import dev.codetrace.graph.engine.GraphBuilder;
import dev.codetrace.graph.engine.GraphTestSupport;
import dev.codetrace.graph.repository.GraphInputRepository;
import dev.codetrace.graph.repository.GraphIntegrityRepository;
import dev.codetrace.graph.repository.GraphSnapshotRepository;
import dev.codetrace.graph.repository.GraphWriteRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.codetrace.config.GraphProperties;
import dev.codetrace.graph.model.GraphModel.Input;
import dev.codetrace.graph.repository.GraphSnapshotRepository.Created;
import dev.codetrace.graph.model.Snapshot;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.jobs.service.JobService;
import dev.codetrace.jobs.model.IndexedRevision;
import dev.codetrace.jobs.model.Job;
import dev.codetrace.ingestion.parser.JavaSymbolExtractor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

/** The build lifecycle and, above all, what happens when a step fails. */
class GraphBuildHandlerTest {
    private static final String COMMIT = "a".repeat(40);

    private final UUID repoId = UUID.randomUUID();
    private final UUID indexJobId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();

    private JobService jobs;
    private GraphSnapshotRepository snapshots;
    private GraphInputRepository loader;
    private GraphWriteRepository writer;
    private GraphIntegrityRepository validator;
    private GraphBuildHandler handler;
    private final Job job = new Job(UUID.randomUUID(), repoId, "GRAPH_BUILD", "RUNNING", COMMIT, 1, null, null,
            OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);

    @BeforeEach
    void setUp() {
        jobs = mock(JobService.class);
        snapshots = mock(GraphSnapshotRepository.class);
        loader = mock(GraphInputRepository.class);
        writer = mock(GraphWriteRepository.class);
        validator = mock(GraphIntegrityRepository.class);
        handler = new GraphBuildHandler(jobs, snapshots, loader, writer, validator,
                new GraphProperties(1000, 1000, 100, 6, 10, 20_000));

        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.of(
                new IndexedRevision(indexJobId, JavaSymbolExtractor.PARSER_VERSION, JavaSymbolExtractor.EXTRACTION_VERSION)));
        when(snapshots.createOrGet(eq(repoId), eq(COMMIT), anyString(), eq(GraphBuilder.VERSION), eq(indexJobId)))
                .thenReturn(new Created(snapshot("REQUESTED"), false));
        when(snapshots.begin(snapshotId)).thenReturn(true);
        when(snapshots.publish(snapshotId)).thenReturn(true);
        when(loader.load(indexJobId)).thenReturn(GraphTestSupport.facts(Map.of("src/p/A.java", "package p; class A { void m() {} }")));
        when(validator.validate(eq(snapshotId), anyInt(), anyInt())).thenReturn(List.of());
    }

    private Snapshot snapshot(String status) {
        return new Snapshot(snapshotId, repoId, COMMIT, JavaSymbolExtractor.PARSER_VERSION, GraphBuilder.VERSION,
                indexJobId, status, null, null, null, null, null, null, OffsetDateTime.now(),
                status.equals("PUBLISHED") ? OffsetDateTime.now() : null);
    }

    @Test
    void happyPathBuildsWritesValidatesThenPublishesInThatOrder() {
        handler.handle(job);

        InOrder order = inOrder(snapshots, loader, writer, validator);
        order.verify(snapshots).begin(snapshotId);
        order.verify(loader).load(indexJobId);
        order.verify(writer).write(any(), any());
        order.verify(writer).refreshStatistics();
        order.verify(validator).validate(eq(snapshotId), anyInt(), anyInt());
        order.verify(snapshots).publish(snapshotId);
        verify(snapshots, never()).markFailed(any(), any());
    }

    @Test
    void anAlreadyPublishedSnapshotIsLeftAlone() {
        when(snapshots.createOrGet(any(), any(), any(), any(), any())).thenReturn(new Created(snapshot("PUBLISHED"), false));

        handler.handle(job);

        verify(snapshots, never()).begin(any());
        verifyNoInteractions(loader, writer, validator);
    }

    @Test
    void aSnapshotPublishedByAnotherWorkerBetweenCheckAndBeginIsNotRebuilt() {
        when(snapshots.begin(snapshotId)).thenReturn(false);

        handler.handle(job);

        verifyNoInteractions(loader, writer, validator);
        verify(snapshots, never()).markFailed(any(), any());
    }

    @Test
    void anUnindexedRevisionFailsBeforeAnythingIsTouched() {
        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOfSatisfying(JobFailureException.class,
                e -> assertThat(e.category()).isEqualTo(Category.INVALID_SOURCE));
        verifyNoInteractions(snapshots, loader, writer);
    }

    @Test
    void factsFromAnOlderExtractorAreRejected() {
        when(jobs.findSucceededIndex(repoId, COMMIT)).thenReturn(Optional.of(
                new IndexedRevision(indexJobId, JavaSymbolExtractor.PARSER_VERSION, 1)));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(JobFailureException.class)
                .hasMessageContaining("re-index");
        verifyNoInteractions(snapshots, loader);
    }

    @Test
    void aSnapshotThatFailsValidationIsMarkedFailedAndNeverPublished() {
        when(validator.validate(eq(snapshotId), anyInt(), anyInt())).thenReturn(List.of("declarations without exactly one container (2)"));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOfSatisfying(JobFailureException.class, e -> {
            assertThat(e.category()).isEqualTo(Category.GRAPH_INVALID);
            assertThat(e.category().retryable()).isFalse();
            assertThat(e.getMessage()).contains("container");
        });
        verify(snapshots).markFailed(eq(snapshotId), org.mockito.ArgumentMatchers.contains("container"));
        verify(snapshots, never()).publish(any());
    }

    @Test
    void anOversizedGraphFailsTheSnapshotWithTheLimitReason() {
        handler = new GraphBuildHandler(jobs, snapshots, loader, writer, validator,
                new GraphProperties(1, 1000, 100, 6, 10, 20_000));

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOfSatisfying(JobFailureException.class,
                e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED));
        verify(snapshots).markFailed(eq(snapshotId), org.mockito.ArgumentMatchers.contains("nodes"));
        verifyNoInteractions(writer);
    }

    @Test
    void anUnexpectedErrorFailsTheSnapshotWithoutLeakingTheMessage() {
        doThrow(new IllegalStateException("/secret/path leaked")).when(writer).write(any(), any());

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(IllegalStateException.class);
        verify(snapshots).markFailed(eq(snapshotId), org.mockito.ArgumentMatchers.argThat(
                m -> !m.contains("/secret") && m.contains("IllegalStateException")));
        verify(snapshots, never()).publish(any());
    }

    @Test
    void databaseOutagesPropagateWithoutFailingTheSnapshot() {
        doThrow(new QueryTimeoutException("db down")).when(writer).write(any(), any());

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(QueryTimeoutException.class);
        verify(snapshots, never()).markFailed(any(), any()); // the retried job clears the partial state in begin()
        verify(snapshots, never()).publish(any());
    }

    @Test
    void anUnexpectedPublishRefusalIsReportedNotIgnored() {
        when(snapshots.publish(snapshotId)).thenReturn(false);

        assertThatThrownBy(() -> handler.handle(job)).isInstanceOf(JobFailureException.class);
        verify(snapshots).markFailed(eq(snapshotId), anyString());
    }

    @Test
    void emptyInputStillProducesAValidEmptySnapshot() {
        when(loader.load(indexJobId)).thenReturn(new Input(List.of(), List.of(), List.of(), List.of()));

        handler.handle(job);

        verify(snapshots).publish(snapshotId);
    }
}
