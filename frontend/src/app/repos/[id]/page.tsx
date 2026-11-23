"use client";

import Link from "next/link";
import { useState } from "react";
import type { ImpactReport, Revision } from "@/lib/api";
import { useRepo, type VersionState } from "@/lib/repo";
import { plural, type Hue } from "@/lib/words";
import Icon from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { prepareTasks, VersionBadge } from "@/components/VersionState";
import { Banner, Button, ButtonLink, Card, EmptyState, Help, RelativeTime, RISK_TONE, Sha, ShowMore, riskWord } from "@/components/ui";
import { IconTile, StatRow, StatTile, Stepper, useToast, type Step } from "@/components/viz";
import s from "./overview.module.css";

const PAGE = 25;

/** Where a version is in Connected → Scanned → Mapped → Ready. */
function stepsOf(v: VersionState): Step[] {
  const scanned = v.state === "ready" || (v.state === "preparing" && v.step === "map") || (v.state === "failed" && !!v.snapshot);
  const failedAt = v.state === "failed" ? (v.snapshot || v.graphJob ? "map" : "scan") : null;
  return [
    { label: "Downloaded", icon: "download", state: "done", sub: "from Git" },
    {
      label: "Scanned",
      icon: "code",
      sub: "code read",
      state: scanned ? "done" : failedAt === "scan" ? "failed" : v.state === "preparing" ? "active" : "todo",
    },
    {
      label: "Mapped",
      icon: "map",
      sub: "links found",
      state: v.state === "ready" ? "done" : failedAt === "map" ? "failed" : v.state === "preparing" && v.step === "map" ? "active" : "todo",
    },
    { label: "Ready", icon: "pulse", sub: "to analyze", state: v.state === "ready" ? "done" : "todo" },
  ];
}

const DOT: Record<VersionState["state"], string> = { ready: s.dotReady, preparing: s.dotPreparing, failed: s.dotFailed, none: "" };

function VersionRow({ rev, base, latest }: { rev: Revision; base: string; latest: boolean }) {
  const { versionOf, prepare } = useRepo();
  const toast = useToast();
  const v = versionOf(rev.commitHash);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      await prepare(rev.commitHash);
      toast("Preparing this version");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start");
    } finally {
      setBusy(false);
    }
  };
  return (
    <li className={s.versionRow}>
      <span className={s.rail} aria-hidden>
        <span className={`${s.dot} ${DOT[v.state]}`} />
      </span>
      <div className={s.versionBody}>
        <div className={s.versionMain}>
          <span className={s.versionMessage}>
            {rev.summary || "(no message)"}
            {latest && <span className={s.latestTag}>latest</span>}
          </span>
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
      </div>
    </li>
  );
}

function LatestVersion({ rev, base }: { rev: Revision; base: string }) {
  const { versionOf, prepare } = useRepo();
  const toast = useToast();
  const v = versionOf(rev.commitHash);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true);
    try {
      await prepare(rev.commitHash);
      toast("Preparing the latest version");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={s.latest}>
      <div className={s.latestHead}>
        <div className={s.latestTitle}>
          <span className={s.eyebrow}>Latest version</span>
          <h2>{rev.summary || "(no message)"}</h2>
          <span className={s.versionMeta}>
            <Sha value={rev.commitHash} />
            {rev.authorTime && (
              <>
                <span aria-hidden>·</span>
                <RelativeTime iso={rev.authorTime} />
              </>
            )}
          </span>
        </div>
        <VersionBadge v={v} />
      </div>
      <div className={s.stepper}>
        <Stepper steps={stepsOf(v)} />
      </div>
      {v.state === "ready" && (
        <div className={s.readyRow}>
          <p>
            Its dependency map has {plural(v.snapshot.nodeCount ?? 0, "item")} and {plural(v.snapshot.edgeCount ?? 0, "link")}.
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
            Prepare this version to explore it and to analyze changes. It takes a few seconds for most projects.
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
    </section>
  );
}

const STRIPE: Record<string, string> = { green: s.stripeGreen, yellow: s.stripeYellow, orange: s.stripeOrange, red: s.stripeRed };

function RecentAnalyses({ reports, base, summaryOf }: { reports: ImpactReport[]; base: string; summaryOf: (c: string) => string }) {
  const done = reports.filter((r) => r.status === "COMPLETED").slice(0, 3);
  if (done.length === 0) {
    return (
      <Card title={<span className={s.cardTitle}><IconTile icon="pulse" tone="orange" size={26} />Recent analyses</span>} headingLevel={3}>
        <p className={s.muted}>No analyses yet. Pick two prepared versions on “Analyze a change” to see what a change could break.</p>
      </Card>
    );
  }
  return (
    <Card
      title={<span className={s.cardTitle}><IconTile icon="pulse" tone="orange" size={26} />Recent analyses</span>}
      headingLevel={3}
      actions={<Link href={`${base}/analyze`}>See all</Link>}
    >
      <ul className={s.analyses}>
        {done.map((r) => {
          const tone = r.riskLevel ? RISK_TONE[r.riskLevel] : "neutral";
          return (
            <li key={r.id}>
              <Link href={`${base}/analyze/${r.id}`} className={`${s.analysis} ${STRIPE[tone] ?? ""}`}>
                <span className={s.analysisScore}>
                  <span className={s.analysisNumber}>{r.riskScore ?? 0}</span>
                  <span className={s.analysisLevel}>{r.riskLevel ? riskWord(r.riskLevel) : ""}</span>
                </span>
                <span className={s.analysisBody}>
                  <span className={s.analysisTitle}>{summaryOf(r.headCommit)}</span>
                  <span className={s.analysisMeta}>
                    <span className={s.metaItem}>
                      <Icon name="alert" size={12} /> {(r.affectedCount ?? 0) - (r.testsAffectedCount ?? 0)} code
                    </span>
                    <span className={s.metaItem}>
                      <Icon name="test" size={12} /> {r.testsAffectedCount ?? 0} tests
                    </span>
                    <RelativeTime iso={r.completedAt ?? r.createdAt} />
                  </span>
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

const HOW: { icon: "play" | "pulse" | "map"; tone: Hue; title: string; text: string }[] = [
  { icon: "play", tone: "green", title: "Prepare", text: "two versions: the code before and after a change." },
  { icon: "pulse", tone: "orange", title: "Analyze the change", text: "to see what it touches and which tests to run." },
  { icon: "map", tone: "teal", title: "Explore", text: "the dependency map to see how any two parts connect." },
];

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
  const completed = data.reports.filter((r) => r.status === "COMPLETED");
  const last = completed[0];
  const summaryOf = (c: string) => data.revisions.find((r) => r.commitHash === c)?.summary || c.slice(0, 7);
  const lastTone = (last?.riskLevel ? RISK_TONE[last.riskLevel] : "neutral") as Hue;

  return (
    <div className={s.page}>
      <StatRow>
        <StatTile icon="commit" tone="blue" value={data.revisions.length} label="recent versions" onClick={() => document.getElementById("versions")?.scrollIntoView({ behavior: "smooth" })} />
        <StatTile icon="check" tone="green" value={prepared} label={prepared === 1 ? "version prepared" : "versions prepared"} />
        <StatTile icon="pulse" tone="violet" value={completed.length} label={completed.length === 1 ? "analysis" : "analyses"} href={`${base}/analyze`} />
        <StatTile
          icon="alert"
          tone={lastTone}
          value={last?.riskScore ?? 0}
          display={last?.riskLevel ? riskWord(last.riskLevel) : "–"}
          label={last ? "latest analysis" : "no analysis yet"}
          href={last ? `${base}/analyze/${last.id}` : undefined}
        />
      </StatRow>
      <div className={s.grid}>
        <div className={s.mainCol}>
          <LatestVersion rev={data.revisions[0]} base={base} />
          <div id="versions" className={s.anchor}>
            <Card
              title={<span className={s.cardTitle}><IconTile icon="commit" tone="blue" size={26} />Versions</span>}
              subtitle={`${plural(data.revisions.length, "recent commit")} on ${data.repo.defaultBranch ?? "the default branch"} · ${prepared} prepared`}
              padded={false}
            >
              <ul className={s.versions}>
                {data.revisions.slice(0, shown).map((r, i) => (
                  <VersionRow key={r.commitHash} rev={r} base={base} latest={i === 0} />
                ))}
              </ul>
              <div className={s.moreWrap}>
                <ShowMore shown={Math.min(shown, data.revisions.length)} total={data.revisions.length} noun="versions" onMore={() => setShown((n) => n + PAGE)} />
              </div>
            </Card>
          </div>
        </div>
        <div className={s.sideCol}>
          <RecentAnalyses reports={data.reports} base={base} summaryOf={summaryOf} />
          <Card title="How it works" headingLevel={3}>
            <ol className={s.how}>
              {HOW.map((h, i) => (
                <li key={h.title}>
                  <IconTile icon={h.icon} tone={h.tone} size={32} />
                  <span>
                    <span className={s.howStep}>Step {i + 1}</span>
                    <strong>{h.title}</strong> {h.text}
                  </span>
                </li>
              ))}
            </ol>
          </Card>
        </div>
      </div>
    </div>
  );
}
