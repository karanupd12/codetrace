"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { listImpactReports, listRepositories, listSnapshots, type ImpactReport, type Repository } from "@/lib/api";
import { usePoll } from "@/lib/usePoll";
import Icon, { type IconName } from "@/components/Icon";
import { Badge, Banner, Button, Card, RelativeTime, RISK_TONE, Sha, Skeleton, riskWord } from "@/components/ui";
import { IconTile, Illustration, Pill, RepoAvatar } from "@/components/viz";
import type { Hue } from "@/lib/words";
import ConnectBox from "./ConnectBox";
import s from "./page.module.css";

const STEPS: { title: string; body: string; icon: IconName; tone: Hue }[] = [
  { title: "Connect", body: "Paste a link to a public GitHub repository (Java). Nothing in it is ever run.", icon: "link", tone: "blue" },
  { title: "Prepare a version", body: "CODETRACE reads the code and maps which parts depend on which. You can watch each step.", icon: "map", tone: "teal" },
  { title: "Analyze a change", body: "Pick a before and after version to see what could break and which tests to run.", icon: "pulse", tone: "orange" },
];

const STATS_FOR = 12; // cards that load their own counts

type RepoStats = { prepared: number; analyses: number; latest?: ImpactReport };

function useRepoStats(repo: Repository, enabled: boolean): RepoStats | null {
  const [stats, setStats] = useState<RepoStats | null>(null);
  useEffect(() => {
    if (!enabled || repo.syncStatus !== "SYNCED") return;
    const ctrl = new AbortController();
    Promise.all([listSnapshots(repo.id, ctrl.signal), listImpactReports(repo.id, ctrl.signal)])
      .then(([snaps, reports]) => {
        const done = reports.filter((r) => r.status === "COMPLETED");
        setStats({
          prepared: new Set(snaps.filter((x) => x.status === "PUBLISHED").map((x) => x.commitHash)).size,
          analyses: done.length,
          latest: done[0],
        });
      })
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [repo.id, repo.syncStatus, enabled]);
  return stats;
}

function SyncBadge({ repo }: { repo: Repository }) {
  if (repo.syncStatus === "PENDING") return <Badge tone="yellow" dot>Connecting</Badge>;
  if (repo.syncStatus === "FAILED") return <Badge tone="red" dot>Connection failed</Badge>;
  return <Badge tone="green" dot>Connected</Badge>;
}

function RepoCard({ repo, withStats }: { repo: Repository; withStats: boolean }) {
  const stats = useRepoStats(repo, withStats);
  return (
    <Link href={`/repos/${repo.id}`} className={s.repoCard}>
      <div className={s.repoCardTop}>
        <span className={s.repoName}>
          <RepoAvatar name={repo.displayName} size={36} />
          <span className={s.repoNameText}>
            <span>{repo.displayName}</span>
            <span className={s.lang}>
              <span className={s.langDot} aria-hidden /> Java
            </span>
          </span>
        </span>
        <SyncBadge repo={repo} />
      </div>
      {stats && (
        <div className={s.miniStats}>
          <span className={s.miniStat}>
            <IconTile icon="check" tone="green" size={24} />
            <span>
              <strong>{stats.prepared}</strong> prepared
            </span>
          </span>
          <span className={s.miniStat}>
            <IconTile icon="pulse" tone="violet" size={24} />
            <span>
              <strong>{stats.analyses}</strong> {stats.analyses === 1 ? "analysis" : "analyses"}
            </span>
          </span>
          {stats.latest?.riskLevel && <Pill tone={RISK_TONE[stats.latest.riskLevel] as Hue}>{riskWord(stats.latest.riskLevel)} risk</Pill>}
        </div>
      )}
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
            <Illustration name="map" size={150} />
            <h1>See what a code change could break</h1>
            <p>
              Connect a repository, pick two versions, and get a plain answer: what changed, what depends on it, and which
              tests to run, with evidence for every claim.
            </p>
          </section>
          <ol className={s.steps}>
            {STEPS.map((step, i) => (
              <li key={step.title} className={s.step}>
                <span className={s.stepHead}>
                  <IconTile icon={step.icon} tone={step.tone} size={38} />
                  <span className={s.stepNumber}>Step {i + 1}</span>
                </span>
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
          <div className={`${s.pageHeader} ${s.banner}`}>
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
              {data.map((r, i) => (
                <RepoCard key={r.id} repo={r} withStats={i < STATS_FOR} />
              ))}
            </div>
          )}
        </>
      )}
    </main>
  );
}
