"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { requestImpactReport, type ImpactReport, type Revision } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { plural } from "@/lib/words";
import Icon from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { prepareTasks, VersionBadge } from "@/components/VersionState";
import {
  Badge,
  Banner,
  Button,
  Card,
  Details,
  EmptyState,
  Field,
  Help,
  RelativeTime,
  RiskBadge,
  Sha,
  controlClass,
} from "@/components/ui";
import s from "../analyze.module.css";

type Pending = { base: string; head: string; depth: number };
const PENDING_KEY = (repoId: string) => `codetrace:analyze:${repoId}`;

function savePending(repoId: string, p: Pending | null) {
  try {
    if (p) localStorage.setItem(PENDING_KEY(repoId), JSON.stringify(p));
    else localStorage.removeItem(PENDING_KEY(repoId));
  } catch {
    /* kept for this page only */
  }
}

function loadPending(repoId: string): Pending | null {
  try {
    const raw = localStorage.getItem(PENDING_KEY(repoId));
    return raw ? (JSON.parse(raw) as Pending) : null;
  } catch {
    return null;
  }
}

function versionLabel(r: Revision) {
  const when = r.authorTime ? new Date(r.authorTime).toLocaleDateString() : "";
  return `${r.summary.slice(0, 70) || "(no message)"} · ${r.commitHash.slice(0, 7)}${when ? ` · ${when}` : ""}`;
}

function ReportRow({ r, base, summaryOf }: { r: ImpactReport; base: string; summaryOf: (c: string) => string }) {
  return (
    <li>
      <Link href={`${base}/analyze/${r.id}`} className={s.reportRow}>
        <div className={s.reportMain}>
          <span className={s.reportTitle}>
            <span className={s.from}>{summaryOf(r.baseCommit)}</span>
            <Icon name="arrowRight" size={13} />
            <span>{summaryOf(r.headCommit)}</span>
          </span>
          <span className={s.reportMeta}>
            <Sha value={r.baseCommit} copy={false} /> → <Sha value={r.headCommit} copy={false} /> · <RelativeTime iso={r.createdAt} />
            {r.status === "COMPLETED" && (
              <> · {plural(r.affectedCount ?? 0, "place")} may be affected · {plural(r.testsAffectedCount ?? 0, "test")} to run</>
            )}
            {r.status === "FAILED" && <span className={s.fail}> · {r.failureReason}</span>}
          </span>
        </div>
        {r.status === "COMPLETED" && r.riskLevel ? (
          <RiskBadge level={r.riskLevel} />
        ) : r.status === "FAILED" ? (
          <Badge tone="red" dot>Failed</Badge>
        ) : (
          <Badge tone="yellow" dot>Analyzing…</Badge>
        )}
      </Link>
    </li>
  );
}

export default function Analyze() {
  const router = useRouter();
  const { data, versionOf, prepare, reload } = useRepo();
  const revisions = useMemo(() => data?.revisions ?? [], [data]);
  const [head, setHead] = useState("");
  const [base, setBase] = useState("");
  const [depth, setDepth] = useState(4);
  const [pending, setPending] = useState<Pending | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const started = useRef<string | null>(null); // the analysis already requested for the pending pair
  const repoId = data?.repo.id ?? "";

  // defaults: the newest version against the one before it; resume an analysis that was waiting for preparation
  useEffect(() => {
    if (!repoId) return;
    const saved = loadPending(repoId);
    if (saved) {
      setPending(saved);
      setBase(saved.base);
      setHead(saved.head);
      setDepth(saved.depth);
    }
  }, [repoId]);
  useEffect(() => {
    if (!head && revisions[0]) setHead(revisions[0].commitHash);
    if (!base && revisions[1]) setBase(revisions[1].commitHash);
  }, [revisions, head, base]);

  // once both versions are ready, start the analysis and open its page
  useEffect(() => {
    if (!pending || !data) return;
    const b = versionOf(pending.base);
    const h = versionOf(pending.head);
    if (b.state === "failed" || h.state === "failed") {
      setError("A version could not be prepared, so the analysis cannot start. Try again from the steps below.");
      return;
    }
    if (b.state !== "ready" || h.state !== "ready") return;
    const key = `${pending.base}:${pending.head}:${pending.depth}`;
    if (started.current === key) return;
    started.current = key;
    requestImpactReport(repoId, pending.base, pending.head, pending.depth)
      .then((r) => {
        savePending(repoId, null);
        setPending(null);
        reload();
        router.push(`/repos/${repoId}/analyze/${r.report.id}`);
      })
      .catch((e: unknown) => {
        started.current = null;
        setError(e instanceof Error ? e.message : "Could not start the analysis.");
        savePending(repoId, null);
        setPending(null);
      });
  }, [pending, data, versionOf, repoId, reload, router]);

  if (!data) return null;
  const pathBase = `/repos/${repoId}`;
  const summaryOf = (c: string) => revisions.find((r) => r.commitHash === c)?.summary.slice(0, 60) || c.slice(0, 7);

  async function analyze() {
    setError(null);
    setStarting(true);
    try {
      const p = { base, head, depth };
      for (const c of [base, head]) {
        const v = versionOf(c);
        if (v.state === "none" || v.state === "failed") await prepare(c);
      }
      savePending(repoId, p);
      setPending(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start.");
    } finally {
      setStarting(false);
    }
  }

  const sameVersion = base !== "" && base === head;
  const baseState = base ? versionOf(base) : null;
  const headState = head ? versionOf(head) : null;

  return (
    <div className={s.layout}>
      <Card
        title="What could this change break?"
        subtitle="Pick the version before the change and the version after it. CODETRACE finds what changed, what depends on it, and which tests to run."
      >
        {revisions.length < 2 ? (
          <EmptyState icon="commit" title="Two versions are needed">
            This repository has only {revisions.length} version so far. Push another commit, then use “Check for new versions”.
          </EmptyState>
        ) : pending ? (
          <div className={s.waiting}>
            <p className={s.waitingTitle}>
              <strong>Getting both versions ready…</strong> The analysis starts as soon as they are.
            </p>
            {[
              { label: "Before", commit: pending.base },
              { label: "After", commit: pending.head },
            ].map(({ label, commit }) => {
              const v = versionOf(commit);
              return (
                <div key={label} className={s.waitBlock}>
                  <div className={s.waitHeader}>
                    <span>
                      <strong>{label}:</strong> {summaryOf(commit)}
                    </span>
                    <VersionBadge v={v} />
                  </div>
                  {v.state !== "ready" && <LiveTimeline compact tasks={prepareTasks(v)} onRetry={() => void prepare(commit)} />}
                </div>
              );
            })}
            {error && <Banner tone="red">{error}</Banner>}
            <div>
              <Button
                variant="invisible"
                size="sm"
                onClick={() => {
                  savePending(repoId, null);
                  setPending(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <div className={s.form}>
            <div className={s.pickers}>
              <Field label="Before (the old version)" htmlFor="base">
                <select id="base" className={controlClass} value={base} onChange={(e) => setBase(e.target.value)}>
                  {revisions.map((r) => (
                    <option key={r.commitHash} value={r.commitHash}>
                      {versionLabel(r)}
                    </option>
                  ))}
                </select>
                {baseState && <VersionBadge v={baseState} />}
              </Field>
              <span className={s.arrow} aria-hidden>
                <Icon name="arrowRight" size={18} />
              </span>
              <Field label="After (the new version)" htmlFor="head">
                <select id="head" className={controlClass} value={head} onChange={(e) => setHead(e.target.value)}>
                  {revisions.map((r) => (
                    <option key={r.commitHash} value={r.commitHash}>
                      {versionLabel(r)}
                    </option>
                  ))}
                </select>
                {headState && <VersionBadge v={headState} />}
              </Field>
            </div>
            <Details summary="Advanced">
              <Field
                label={
                  <>
                    How far to follow dependencies
                    <Help>
                      How many steps away from the changed code to look. 4 finds most real impact; higher values find more
                      distant (and less likely) effects.
                    </Help>
                  </>
                }
                htmlFor="depth"
              >
                <select id="depth" className={controlClass} style={{ maxWidth: 220 }} value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
                  {[1, 2, 3, 4, 5, 6].map((d) => (
                    <option key={d} value={d}>
                      {d} step{d === 1 ? "" : "s"}
                      {d === 4 ? " (recommended)" : ""}
                    </option>
                  ))}
                </select>
              </Field>
            </Details>
            {sameVersion && <Banner tone="yellow">Pick two different versions.</Banner>}
            {error && <Banner tone="red" title="Could not start">{error}</Banner>}
            <div className={s.submit}>
              <Button variant="primary" size="lg" icon="pulse" loading={starting} disabled={!base || !head || sameVersion} onClick={analyze}>
                Analyze
              </Button>
              {(baseState?.state !== "ready" || headState?.state !== "ready") && !sameVersion && (
                <span className={s.note}>Versions that are not prepared yet are prepared first. You can watch each step.</span>
              )}
            </div>
          </div>
        )}
      </Card>

      <Card title="Past analyses" padded={data.reports.length === 0}>
        {data.reports.length === 0 ? (
          <EmptyState icon="pulse" title="No analyses yet">
            Your analyses appear here, newest first.
          </EmptyState>
        ) : (
          <ul className={s.reports}>
            {data.reports.map((r) => (
              <ReportRow key={r.id} r={r} base={pathBase} summaryOf={summaryOf} />
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
