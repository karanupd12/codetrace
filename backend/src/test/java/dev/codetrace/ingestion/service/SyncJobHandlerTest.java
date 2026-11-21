package dev.codetrace.ingestion.service;

import dev.codetrace.ingestion.git.GitSourceManager;
import dev.codetrace.ingestion.git.RevisionDiscovery;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.codetrace.jobs.model.Job;
import dev.codetrace.source.repository.SourceRepositoryRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncJobHandlerTest {
    @Test
    void permanentFailureMarksTheRepositorySyncFailed() {
        SourceRepositoryRepository repos = mock(SourceRepositoryRepository.class);
        SyncJobHandler handler = new SyncJobHandler(repos, mock(GitSourceManager.class), mock(RevisionDiscovery.class));
        UUID repoId = UUID.randomUUID();
        Job job = new Job(UUID.randomUUID(), repoId, "SYNC", "FAILED", null, 1, null, null, OffsetDateTime.now(), null,
                null, null, null, null, null);

        handler.onPermanentFailure(job);

        verify(repos).markSyncFailed(repoId);
    }
}
