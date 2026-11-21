"use client";

import { useEffect, useState } from "react";
import {
  listFindings,
  reportInProgress,
  reportMarkdownUrl,
  requestImpactReport,
  type Finding,
  type ImpactReport,
  type Revision,
  type RiskLevel,
} from "@/lib/api";
import page from "../../page.module.css";
import styles from "./impact.module.css";

const short = (h: string) => h.slice(0, 7);
const LEVEL_COLOR: Record<RiskLevel, string> = { LOW: "#22a559", MEDIUM: "#c98a00", HIGH: "#e0651a", CRITICAL: "#cf2f2f" };

function Level({ level }: { level: RiskLevel | null }) {
  if (!level) return null;
  return <span className={`${styles.level} ${styles["level" + level]}`}>{level}</span>;
}

/** Pick two graphed revisions, run an analysis, and list earlier reports. */
export default function ImpactPanel({
  repoId,
  revisions,
  graphed,
  reports,
  onRequested,
}: {
  repoId: string;
  revisions: Revision[];
  graphed: Set<string>;
  reports: ImpactReport[];
  onRequested: () => void;
}) {
  const options = revisions.filter((r) => graphed.has(r.commitHash)); // newest first
  const [head, setHead] = useState("");
  const [base, setBase] = useState("");
  const [depth, setDepth] = useState(4);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<string | null>(null);

  // sensible defaults: newest graphed revision vs the one before it
  useEffect(() => {
    if (!head && options[0]) setHead(options[0].commitHash);
    if (!base && options[1]) setBase(options[1].commitHash);
  }, [options, head, base]);

  const opened = reports.find((r) => r.id === open) ?? null;

  async function analyze() {
    setError(null);
    setBusy(true);
    try {
      const r = await requestImpactReport(repoId, base, head, depth);
      setOpen(r.report.id);
      onRequested();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start the analysis");
    } finally {
      setBusy(false);
    }
  }

  const label = (r: Revision) => `${short(r.commitHash)} · ${r.summary.slice(0, 60)}`;

  return (
    <section className={page.card}>
      <h2>Change impact</h2>
      {options.length < 2 ? (
        <p className={page.muted}>
          Build the dependency graph for at least two revisions to compare them. The base is the code before the change; the
          head is the code after it.
        </p>
      ) : (
        <div className={styles.form}>
          <label>
            Base (before)
            <select value={base} onChange={(e) => setBase(e.target.value)} aria-label="Base revision">
              {options.map((r) => (
                <option key={r.commitHash} value={r.commitHash}>{label(r)}</option>
              ))}
            </select>
          </label>
          <label>
            Head (after)
            <select value={head} onChange={(e) => setHead(e.target.value)} aria-label="Head revision">
              {options.map((r) => (
                <option key={r.commitHash} value={r.commitHash}>{label(r)}</option>
              ))}
            </select>
          </label>
          <label>
            Depth
            <select value={depth} onChange={(e) => setDepth(Number(e.target.value))} aria-label="Traversal depth">
              {[1, 2, 3, 4, 5, 6].map((d) => <option key={d} value={d}>{d} hop{d > 1 ? "s" : ""}</option>)}
            </select>
          </label>
          <button onClick={analyze} disabled={busy || !base || !head || base === head}>
            {busy ? "Starting…" : "Analyze change"}
          </button>
        </div>
      )}
      {base && head && base === head && options.length >= 2 && <p className={styles.notice}>Pick two different revisions.</p>}
      {error && <p role="alert" className={page.error}>{error}</p>}

      {reports.length > 0 && (
        <ul className={styles.reports} aria-label="Impact reports">
          {reports.map((r) => (
            <li key={r.id} aria-current={r.id === open}>
              {r.status === "COMPLETED" ? (
                <Level level={r.riskLevel} />
              ) : (
                <span className={`${page.badge} ${page["badge" + (r.status === "FAILED" ? "FAILED" : "RUNNING")]}`}>{r.status}</span>
              )}
              <span><code>{short(r.baseCommit)}</code> → <code>{short(r.headCommit)}</code></span>
              <span className={page.muted}>
                {r.status === "COMPLETED"
                  ? `${r.riskScore}/100 · ${r.changedCount} changed · ${r.affectedCount} affected · ${r.testsAffectedCount} tests · depth ${r.depth}`
                  : r.status === "FAILED"
                    ? r.failureReason
                    : "analysing…"}
              </span>
              {r.status === "COMPLETED" && (
                <button onClick={() => setOpen(r.id === open ? null : r.id)}>{r.id === open ? "Close" : "Open"}</button>
              )}
            </li>
          ))}
        </ul>
      )}

      {opened && opened.status === "COMPLETED" && <ReportView key={opened.id} report={opened} />}
      {opened && reportInProgress(opened.status) && <p className={styles.notice}>Analysing the change…</p>}
    </section>
  );
}

function ReportView({ report }: { report: ImpactReport }) {
  const [changed, setChanged] = useState<Finding[]>([]);
  const [affected, setAffected] = useState<Finding[]>([]);
  const [possible, setPossible] = useState<Finding[]>([]);
  const [totals, setTotals] = useState({ changed: 0, affected: 0, possible: 0 });
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    Promise.all([
      listFindings(report.id, { type: "CHANGED", limit: 200, offset: 0 }, ctrl.signal),
      listFindings(report.id, { type: "AFFECTED", limit: 300, offset: 0 }, ctrl.signal),
      listFindings(report.id, { type: "POSSIBLE", limit: 100, offset: 0 }, ctrl.signal),
    ])
      .then(([c, a, p]) => {
        setChanged(c.items);
        setAffected(a.items);
        setPossible(p.items);
        setTotals({ changed: c.total, affected: a.total, possible: p.total });
      })
      .catch((e: unknown) => {
        if (!ctrl.signal.aborted) setError(e instanceof Error ? e.message : "Could not load findings");
      });
    return () => ctrl.abort();
  }, [report.id]);

  const production = affected.filter((f) => !f.test);
  const tests = affected.filter((f) => f.test);
  const score = report.riskScore ?? 0;
  const level = report.riskLevel ?? "LOW";

  return (
    <div className={styles.section}>
      <div className={styles.score}>
        <span className={styles.scoreNumber}>{score}</span>
        <Level level={level} />
        <div className={styles.bar} aria-label={`Risk score ${score} of 100`}>
          <div className={styles.barFill} style={{ width: `${score}%`, background: LEVEL_COLOR[level] }} />
        </div>
      </div>
      <a className={styles.download} href={reportMarkdownUrl(report.id)} download>
        Download Markdown report
      </a>
      <p className={styles.notice}>
        Snapshots {report.baseSnapshotId.slice(0, 8)} → {report.headSnapshotId.slice(0, 8)} · algorithm {report.algorithmVersion} ·
        dependents looked up in the base revision, up to {report.depth} hop(s).
      </p>
      {report.truncated && <p className={styles.warn}>The analysis reached a configured limit, so the impact shown is a lower bound.</p>}
      {error && <p role="alert" className={page.error}>{error}</p>}

      <table className={styles.factors}>
        <thead>
          <tr><th>Signal</th><th>Points</th><th>Finding</th></tr>
        </thead>
        <tbody>
          {report.riskFactors.map((f) => (
            <tr key={f.signal}>
              <td>{f.signal.replace(/^\d+-/, "").replace(/-/g, " ")}</td>
              <td className={styles.num}>
                {f.points} / {f.maxPoints}
                <span className={styles.mini}>
                  <span className={styles.miniFill} style={{ width: `${(f.points / f.maxPoints) * 100}%` }} />
                </span>
              </td>
              <td>{f.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h3>Changed symbols ({totals.changed})</h3>
      {changed.length === 0 ? (
        <p className={styles.notice}>
          No Java declaration changed{report.unmappedHunks ? ` (${report.unmappedHunks} region(s) touch only imports, comments or blank lines)` : ""}.
        </p>
      ) : (
        <table className={page.table}>
          <thead>
            <tr><th>Change</th><th>Symbol</th><th>Location</th><th>Direct dependents</th></tr>
          </thead>
          <tbody>
            {changed.map((f) => (
              <tr key={f.ordinal}>
                <td><span className={`${styles.tag} ${styles["tag" + f.changeKind]}`}>{f.changeKind}</span></td>
                <td className={styles.sym}>{f.name}</td>
                <td className={styles.sym}>{f.path}:{f.line}</td>
                <td>{f.directDependents ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h3>Affected production code ({production.length}{totals.affected > affected.length ? "+" : ""})</h3>
      {production.length === 0 ? (
        <p className={styles.notice}>No production symbol depends on the changed code.</p>
      ) : (
        <AffectedTable findings={production} />
      )}

      <h3>Tests to run ({tests.length})</h3>
      {tests.length === 0 ? (
        <p className={styles.notice}>No test in the dependency graph depends on the changed code.</p>
      ) : (
        <AffectedTable findings={tests} />
      )}

      {possible.length > 0 && (
        <>
          <h3>Possible dependents, not proven ({totals.possible})</h3>
          <p className={styles.notice}>
            Calls to a method with the same name and argument count as a changed method, on an object whose type could not be
            determined. Check them by hand; they are not counted as affected.
          </p>
          <ul>
            {possible.map((f) => (
              <li key={f.ordinal} className={styles.sym}>
                {f.name} <small>({f.path}:{f.line})</small> may call {f.changedSymbol}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function AffectedTable({ findings }: { findings: Finding[] }) {
  return (
    <table className={page.table}>
      <thead>
        <tr><th>Symbol</th><th>Module</th><th>Distance</th><th>Evidence</th></tr>
      </thead>
      <tbody>
        {findings.map((f) => (
          <tr key={f.ordinal}>
            <td className={styles.sym}>
              {f.name}
              {f.certain === false && <> <span className={`${styles.tag} ${styles.tagUNCERTAIN}`}>uncertain</span></>}
            </td>
            <td>{f.module}</td>
            <td>{f.distance}</td>
            <td>
              <details className={styles.evidence}>
                <summary>{f.chain.length} link{f.chain.length === 1 ? "" : "s"} to {f.changedSymbol.split("#").pop()}</summary>
                <div className={styles.chain}>
                  {f.chain.map((h, i) => (
                    <div key={i}>
                      <span className={styles.sym}>{h.from} → {h.to}</span>
                      <small>
                        {h.edgeType.toLowerCase()} · {h.resolution.toLowerCase()} · confidence {h.confidence.toFixed(2)} · {h.path}:{h.line}
                      </small>
                    </div>
                  ))}
                </div>
              </details>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
