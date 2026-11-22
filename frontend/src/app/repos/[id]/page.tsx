"use client";

import Link from "next/link";
import { useState } from "react";
import type { ImpactReport, Revision } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { plural } from "@/lib/words";
import Icon from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { prepareTasks, VersionBadge } from "@/components/VersionState";
import {
  Badge,
  Banner,
  Button,
  ButtonLink,
  Card,
  EmptyState,
  Help,
  RelativeTime,
  RiskBadge,
  Sha,
  ShowMore,
} from "@/components/ui";
import s from "./overview.module.css";

const PAGE = 25;

function VersionRow({ rev, base }: { rev: Revision; base: string }) {
  const { versionOf, prepare } = useRepo();
  const v = versionOf(rev.commitHash);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await prepare(rev.commitHash);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start");
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className={s.versionRow}>
      <div className={s.versionMain}>
        <span className={s.versionMessage}>{rev.summary || "(no message)"}</span>
        <span className={s.versionMeta}>
          <Sha value={rev.commitHash} />
          {rev.authorTime && (
            <>
              <span aria-hidden>·</span>
              <RelativeTime iso={rev.authorTime} />
            </>
          )}
          {v.state === "failed" && <span className={s.failReason}>{v.reason}</span>}
          {error && <span className={s.failReason}>{error}</span>}
        </span>
      </div>
      <div className={s.versionActions}>
        <VersionBadge v={v} />
        {v.state === "ready" && (
          <ButtonLink size="sm" href={`${base}/explore?map=${v.snapshot.id}`} icon="map">
            Open map
          </ButtonLink>
        )}
        {v.state === "none" && (
          <Button size="sm" loading={busy} onClick={run}>
            Prepare
          </Button>
        )}
        {v.state === "failed" && (
          <Button size="sm" icon="refresh" loading={busy} onClick={run}>
            Try again
          </Button>
        )}
      </div>
      {v.state === "preparing" && (
        <div className={s.rowTimeline}>
          <LiveTimeline compact tasks={prepareTasks(v)} />
        </div>
      )}
    </li>
  );
}

function LatestVersion({ rev, base }: { rev: Revision; base: string }) {
  const { versionOf, prepare } = useRepo();
  const v = versionOf(rev.commitHash);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try {
      await prepare(rev.commitHash);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Latest version"
      subtitle={
        <>
          {rev.summary} · <Sha value={rev.commitHash} />
        </>
      }
      actions={<VersionBadge v={v} />}
    >
      {v.state === "ready" && (
        <div className={s.readyRow}>
          <p>
            This version is ready: its code has been scanned and its dependency map has{" "}
            {plural(v.snapshot.nodeCount ?? 0, "item")} and {plural(v.snapshot.edgeCount ?? 0, "link")}.
          </p>
          <div className={s.actions}>
            <ButtonLink href={`${base}/analyze`} variant="primary" icon="pulse">
              Analyze a change
            </ButtonLink>
            <ButtonLink href={`${base}/explore?map=${v.snapshot.id}`} icon="map">
              Explore the map
            </ButtonLink>
          </div>
        </div>
      )}
      {v.state === "none" && (
        <div className={s.readyRow}>
          <p>
            Prepare this version to explore it and to analyze changes. CODETRACE reads the code and maps what depends on what;
            it takes a few seconds for most projects.
            <Help>Nothing from the repository is run. Files are read from Git, parsed, and linked by name and type.</Help>
          </p>
          <div className={s.actions}>
            <Button variant="primary" icon="play" loading={busy} onClick={run}>
              Prepare this version
            </Button>
          </div>
        </div>
      )}
      {(v.state === "preparing" || v.state === "failed") && (
        <div className={s.progress}>
          <LiveTimeline tasks={prepareTasks(v)} onRetry={run} />
        </div>
      )}
    </Card>
  );
}

function RecentAnalyses({ reports, base }: { reports: ImpactReport[]; base: string }) {
  const done = reports.filter((r) => r.status === "COMPLETED").slice(0, 3);
  if (done.length === 0) return null;
  return (
    <Card title="Recent analyses" actions={<Link href={`${base}/analyze`}>See all</Link>}>
      <ul className={s.analyses}>
        {done.map((r) => (
          <li key={r.id}>
            <Link href={`${base}/analyze/${r.id}`} className={s.analysis}>
              <span className={s.analysisTitle}>
                <Sha value={r.baseCommit} copy={false} /> <Icon name="arrowRight" size={13} /> <Sha value={r.headCommit} copy={false} />
              </span>
              {r.riskLevel && <RiskBadge level={r.riskLevel} />}
              <span className={s.analysisMeta}>
                {plural(r.affectedCount ?? 0, "place")} may be affected · {plural(r.testsAffectedCount ?? 0, "test")} to run
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  );
}

export default function Overview() {
  const { data } = useRepo();
  const [shown, setShown] = useState(PAGE);
  if (!data) return null;
  const base = `/repos/${data.repo.id}`;
  const syncJob = data.jobs.find((j) => j.operation === "SYNC");

  if (data.repo.syncStatus === "PENDING") {
    return (
      <Card title="Connecting to the repository" subtitle="Downloading its recent history. This usually takes a few seconds.">
        {syncJob ? <LiveTimeline tasks={[{ job: syncJob }]} /> : <Banner>Starting…</Banner>}
      </Card>
    );
  }
  if (data.revisions.length === 0) {
    return (
      <Card>
        <EmptyState icon="commit" title="No versions found">
          The repository has no commits on its default branch yet. Use “Check for new versions” after pushing some.
        </EmptyState>
      </Card>
    );
  }

  const prepared = data.revisions.filter((r) => data.snapshots.some((s2) => s2.commitHash === r.commitHash && s2.status === "PUBLISHED")).length;

  return (
    <div className={s.grid}>
      <div className={s.mainCol}>
        <LatestVersion rev={data.revisions[0]} base={base} />
        <Card
          title="Versions"
          subtitle={`${plural(data.revisions.length, "recent commit")} on ${data.repo.defaultBranch ?? "the default branch"} · ${prepared} prepared`}
          padded={false}
        >
          <ul className={s.versions}>
            {data.revisions.slice(0, shown).map((r) => (
              <VersionRow key={r.commitHash} rev={r} base={base} />
            ))}
          </ul>
          <div className={s.moreWrap}>
            <ShowMore shown={Math.min(shown, data.revisions.length)} total={data.revisions.length} noun="versions" onMore={() => setShown((n) => n + PAGE)} />
          </div>
        </Card>
      </div>
      <div className={s.sideCol}>
        <RecentAnalyses reports={data.reports} base={base} />
        <Card title="How it works" headingLevel={3}>
          <ol className={s.how}>
            <li>
              <Badge tone="neutral">1</Badge> <strong>Prepare</strong> two versions (the code before and after a change).
            </li>
            <li>
              <Badge tone="neutral">2</Badge> <strong>Analyze the change</strong> to see what it touches and which tests to run.
            </li>
            <li>
              <Badge tone="neutral">3</Badge> <strong>Explore</strong> the dependency map to see how any two parts connect.
            </li>
          </ol>
        </Card>
      </div>
    </div>
  );
}
