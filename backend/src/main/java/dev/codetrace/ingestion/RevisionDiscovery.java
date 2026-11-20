package dev.codetrace.ingestion;

import dev.codetrace.config.IngestionProperties;
import dev.codetrace.ingestion.IngestionException.Category;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Component;

/** Lists the most recent commits reachable from HEAD (bounded). */
@Component
public class RevisionDiscovery {
    public record Discovered(String commitHash, String summary, OffsetDateTime authorTime) {
    }

    public record Result(String defaultBranch, String headCommit, List<Discovered> revisions) {
    }

    private final IngestionProperties props;

    public RevisionDiscovery(IngestionProperties props) {
        this.props = props;
    }

    public Result discover(Repository repo) {
        try {
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                throw new IngestionException(Category.NO_COMMITS, "Repository has no commits.");
            }
            List<Discovered> out = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(head));
                for (RevCommit c : walk) {
                    String summary = c.getShortMessage();
                    if (summary.length() > 200) summary = summary.substring(0, 200);
                    out.add(new Discovered(c.getName(), summary,
                            OffsetDateTime.ofInstant(c.getAuthorIdent().getWhenAsInstant(), ZoneOffset.UTC)));
                    if (out.size() >= props.maxRevisions()) break;
                }
            }
            String branch = repo.getBranch(); // short name, or the commit id when detached
            boolean detached = branch == null || branch.matches("^[0-9a-f]{40}$");
            return new Result(detached ? null : branch, head.getName(), out);
        } catch (IOException e) {
            throw new IngestionException(Category.GIT_ERROR, "Could not read commits.", e);
        }
    }
}
