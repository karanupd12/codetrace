package dev.codetrace.ingestion;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.codetrace.jobs.JobDao.Job;
import dev.codetrace.repository.RepositoryDao;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SyncJobHandlerTest {
    @Test
    void permanentFailureMarksTheRepositorySyncFailed() {
        RepositoryDao repos = mock(RepositoryDao.class);
        SyncJobHandler handler = new SyncJobHandler(repos, mock(GitSourceManager.class), mock(RevisionDiscovery.class));
        UUID repoId = UUID.randomUUID();
        Job job = new Job(UUID.randomUUID(), repoId, "SYNC", "FAILED", null, 1, null, null, OffsetDateTime.now(), null,
                null, null, null, null, null);

        handler.onPermanentFailure(job);

        verify(repos).markSyncFailed(repoId);
    }
}
