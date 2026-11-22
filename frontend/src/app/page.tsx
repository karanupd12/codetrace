"use client";

import Link from "next/link";
import { useState } from "react";
import { listRepositories, type Repository } from "@/lib/api";
import { usePoll } from "@/lib/usePoll";
import Icon from "@/components/Icon";
import { Badge, Banner, Button, Card, RelativeTime, Sha, Skeleton } from "@/components/ui";
import ConnectBox from "./ConnectBox";
import s from "./page.module.css";

const STEPS = [
  { title: "Connect", body: "Paste a link to a public GitHub repository (Java). Nothing in it is ever run." },
  { title: "Prepare a version", body: "CODETRACE reads the code and maps which parts depend on which. You can watch each step." },
  { title: "Analyze a change", body: "Pick a before and after version to see what could break and which tests to run." },
];

function SyncBadge({ repo }: { repo: Repository }) {
  if (repo.syncStatus === "PENDING") return <Badge tone="yellow" dot>Connecting</Badge>;
  if (repo.syncStatus === "FAILED") return <Badge tone="red" dot>Connection failed</Badge>;
  return <Badge tone="green" dot>Connected</Badge>;
}

function RepoCard({ repo }: { repo: Repository }) {
  return (
    <Link href={`/repos/${repo.id}`} className={s.repoCard}>
      <div className={s.repoCardTop}>
        <span className={s.repoName}>
          <Icon name="repo" />
          <span>{repo.displayName}</span>
        </span>
        <SyncBadge repo={repo} />
      </div>
      <div className={s.repoMeta}>
        <span>{repo.sourceType === "GIT_URL" ? "GitHub" : "Local folder"}</span>
        {repo.headCommit && (
          <span>
            <Icon name="commit" size={14} /> {repo.defaultBranch ?? "HEAD"} · <Sha value={repo.headCommit} copy={false} />
          </span>
        )}
        <span>
          Added <RelativeTime iso={repo.createdAt} />
        </span>
      </div>
    </Link>
  );
}

export default function Home() {
  const { data, error } = usePoll(listRepositories, 3000, (repos) => repos.some((r) => r.syncStatus === "PENDING"));
  const [connecting, setConnecting] = useState(false);
  const empty = data !== null && data.length === 0;

  return (
    <main className={`${s.page} ${s.narrow}`}>
      {error && !data && (
        <Banner tone="red" title="The service can't be reached">
          {error}. Make sure the backend is running, then refresh.
        </Banner>
      )}

      {empty ? (
        <>
          <section className={s.hero}>
            <h1>See what a code change could break</h1>
            <p>
              Connect a repository, pick two versions, and get a plain answer: what changed, what depends on it, and which
              tests to run, with evidence for every claim.
            </p>
          </section>
          <ol className={s.steps}>
            {STEPS.map((step, i) => (
              <li key={step.title} className={s.step}>
                <span className={s.stepNumber}>{i + 1}</span>
                <h3>{step.title}</h3>
                <p>{step.body}</p>
              </li>
            ))}
          </ol>
          <Card title="Connect a repository">
            <ConnectBox autoFocus />
          </Card>
        </>
      ) : (
        <>
          <div className={s.pageHeader}>
            <div>
              <h1>Your repositories</h1>
              <p>Open a repository to prepare versions and analyze changes.</p>
            </div>
            {!connecting && (
              <Button variant="primary" icon="plus" onClick={() => setConnecting(true)}>
                Connect a repository
              </Button>
            )}
          </div>
          {connecting && (
            <Card title="Connect a repository" actions={<Button variant="invisible" size="sm" onClick={() => setConnecting(false)}>Cancel</Button>}>
              <ConnectBox autoFocus />
            </Card>
          )}
          {data === null ? (
            <Card>
              <Skeleton lines={4} />
            </Card>
          ) : (
            <div className={s.repoGrid}>
              {data.map((r) => (
                <RepoCard key={r.id} repo={r} />
              ))}
            </div>
          )}
        </>
      )}
    </main>
  );
}
