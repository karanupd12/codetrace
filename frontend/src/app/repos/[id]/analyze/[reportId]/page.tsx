"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  getImpactReport,
  listFindings,
  reportInProgress,
  reportMarkdownUrl,
  requestImpactReport,
  type Finding,
  type FindingType,
  type Hop,
  type ImpactReport,
} from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { usePoll } from "@/lib/usePoll";
import { EDGE_WORD, fileName, plural, shortName } from "@/lib/words";
import Icon from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import {
  Badge,
  Banner,
  Button,
  ButtonLink,
  Card,
  Details,
  Help,
  RelativeTime,
  RiskBadge,
  RiskMeter,
  Sha,
  ShowMore,
  Skeleton,
  controlClass,
  riskWord,
  type Tone,
} from "@/components/ui";
import s from "./report.module.css";

const PAGE = 10;

const CHANGE: Record<string, { label: string; tone: Tone }> = {
  ADDED: { label: "Added", tone: "green" },
  MODIFIED: { label: "Changed", tone: "orange" },
  REMOVED: { label: "Removed", tone: "red" },
};

/** One step of an evidence chain in words: "Checkout.pay(double) calls Order.price(double) · Checkout.java:12". */
function HopLine({ hop }: { hop: Hop }) {
  return (
    <li className={s.hop}>
      <code>{shortName(hop.from)}</code> <span className={s.hopVerb}>{EDGE_WORD[hop.edgeType] ?? hop.edgeType.toLowerCase()}</span>{" "}
      <code>{shortName(hop.to)}</code>
      {hop.resolution !== "RESOLVED" || hop.edgeType === "OVERRIDDEN_BY" ? (
        <Badge tone="yellow">best guess</Badge>
      ) : null}
      <span className={s.hopWhere}>
        {fileName(hop.path)}:{hop.line}
      </span>
    </li>
  );
}

function Why({ chain }: { chain: Hop[] }) {
  if (!chain?.length) return null;
  return (
    <details className={s.why2}>
      <summary>Why?</summary>
      <ol className={s.chain}>
        {chain.map((h, i) => (
          <HopLine key={i} hop={h} />
        ))}
      </ol>
    </details>
  );
}

function FindingRow({ f }: { f: Finding }) {
  if (f.findingType === "CHANGED") {
    const c = CHANGE[f.changeKind ?? "MODIFIED"];
    return (
      <li className={s.row}>
        <Badge tone={c.tone}>{c.label}</Badge>
        <div className={s.rowMain}>
          <code className={s.name} title={f.name}>
            {shortName(f.name)}
          </code>
          <span className={s.where}>
            {f.path}:{f.line}
            {f.directDependents ? ` · used directly in ${plural(f.directDependents, "place")}` : ""}
          </span>
        </div>
      </li>
    );
  }
  if (f.findingType === "ENDPOINT") {
    const c = f.changeKind ? CHANGE[f.changeKind] : { label: "Affected", tone: "yellow" as Tone };
    return (
      <li className={s.row}>
        <Badge tone={c.tone}>{c.label}</Badge>
        <div className={s.rowMain}>
          <code className={s.name}>{f.name}</code>
          <span className={s.where}>
            handled by <code>{shortName(f.changedSymbol)}</code> · {fileName(f.path)}:{f.line}
            {f.distance ? ` · ${plural(f.distance, "step")} from the change` : ""}
          </span>
          <Why chain={f.chain} />
        </div>
      </li>
    );
  }
  if (f.findingType === "POSSIBLE") {
    return (
      <li className={s.row}>
        <Icon name="question" />
        <div className={s.rowMain}>
          <code className={s.name} title={f.name}>
            {shortName(f.name)}
          </code>
          <span className={s.where}>
            may call <code>{shortName(f.changedSymbol)}</code> · {fileName(f.path)}:{f.line}
          </span>
        </div>
      </li>
    );
  }
  return (
    <li className={s.row}>
      <Icon name={f.test ? "test" : "code"} />
      <div className={s.rowMain}>
        <span className={s.nameLine}>
          <code className={s.name} title={f.name}>
            {shortName(f.name)}
          </code>
          {f.certain === false && <Badge tone="yellow">possibly</Badge>}
        </span>
        <span className={s.where}>
          {f.module && f.module !== "(root)" ? `${f.module} · ` : ""}
          {fileName(f.path)}:{f.line} · {plural(f.distance ?? 0, "step")} from <code>{shortName(f.changedSymbol)}</code>
        </span>
        <Why chain={f.chain} />
      </div>
    </li>
  );
}

function FindingSection({ reportId, title, explain, type, test, empty, withModuleFilter }: {
  reportId: string;
  title: string;
  explain: ReactNode;
  type: FindingType;
  test?: boolean;
  empty?: string;
  withModuleFilter?: boolean;
}) {
  const [items, setItems] = useState<Finding[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [module, setModule] = useState("");
  const [modules, setModules] = useState<string[]>([]);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    listFindings(reportId, { type, test, module: module || undefined, limit: PAGE, offset: 0 }, ctrl.signal)
      .then((p) => {
        setItems(p.items);
        setTotal(p.total);
        setError(null);
        if (!module) setModules([...new Set(p.items.map((f) => f.module).filter(Boolean))].sort());
      })
      .catch((e: unknown) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not load"))
      .finally(() => !ctrl.signal.aborted && setLoading(false));
    return () => ctrl.abort();
  }, [reportId, type, test, module]);

  const more = () => {
    setLoading(true);
    listFindings(reportId, { type, test, module: module || undefined, limit: PAGE, offset: items.length })
      .then((p) => {
        setItems((cur) => [...cur, ...p.items]);
        setTotal(p.total);
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Could not load"))
      .finally(() => setLoading(false));
  };

  if (total === 0 && !module && empty === undefined) return null; // optional sections stay hidden when empty

  return (
    <Card
      title={
        <>
          {title} {total !== null && <span className={s.count}>{total.toLocaleString()}</span>}
        </>
      }
      subtitle={explain}
      padded={false}
      actions={
        withModuleFilter && modules.length > 1 ? (
          <select aria-label="Filter by module" className={controlClass} style={{ width: "auto" }} value={module} onChange={(e) => setModule(e.target.value)}>
            <option value="">All modules</option>
            {modules.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        ) : undefined
      }
    >
      <div className={s.sectionBody}>
        {error && <Banner tone="red">{error}</Banner>}
        {total === null ? (
          <Skeleton lines={3} />
        ) : total === 0 ? (
          <p className={s.empty}>{empty}</p>
        ) : (
          <>
            <ul className={s.rows}>
              {items.map((f) => (
                <FindingRow key={`${f.findingType}:${f.ordinal}`} f={f} />
              ))}
            </ul>
            <ShowMore shown={items.length} total={total} loading={loading} onMore={more} />
          </>
        )}
      </div>
    </Card>
  );
}

function Summary({ report, before, after }: { report: ImpactReport; before: string; after: string }) {
  const level = report.riskLevel ?? "LOW";
  const tests = report.testsAffectedCount ?? 0;
  const production = (report.affectedCount ?? 0) - tests;
  const reasons = report.riskFactors.filter((f) => f.points > 0);
  return (
    <section className={s.summary}>
      <div className={s.summaryTop}>
        <div className={s.verdict}>
          <RiskBadge level={level} />
          <h2>
            {riskWord(level)} risk: {plural(production, "place")} in production code and {plural(tests, "test")} depend on what
            changed.
          </h2>
          <p className={s.versions}>
            <span>{before}</span> <Icon name="arrowRight" size={13} /> <span>{after}</span>
          </p>
        </div>
        <div className={s.scoreBox}>
          <span className={s.score}>{report.riskScore ?? 0}</span>
          <span className={s.scoreOf}>/ 100</span>
          <RiskMeter score={report.riskScore ?? 0} level={level} />
        </div>
      </div>
      <div className={s.why}>
        <h3>Why this risk</h3>
        {reasons.length === 0 ? (
          <p className={s.muted}>Nothing raised the risk: the change touches little that other code relies on.</p>
        ) : (
          <ul>
            {reasons.map((f) => (
              <li key={f.signal}>{f.reason}</li>
            ))}
          </ul>
        )}
        <Details summary="How the score is made">
          <table className={s.factors}>
            <tbody>
              {report.riskFactors.map((f) => (
                <tr key={f.signal}>
                  <td>{f.signal.replace(/^\d+-/, "").replace(/-/g, " ")}</td>
                  <td className={s.num}>
                    {f.points} / {f.maxPoints}
                  </td>
                  <td className={s.muted}>{f.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className={s.muted} style={{ marginTop: 8 }}>
            Seven independent checks each add points up to their own limit; the total is capped at 100. Analysis{" "}
            {report.algorithmVersion}, followed dependencies up to {report.depth} steps.
          </p>
        </Details>
      </div>
      {report.truncated && (
        <Banner tone="yellow" title="This is a lower bound">
          The analysis stopped at a size limit, so more code may be affected than is listed.
        </Banner>
      )}
    </section>
  );
}

export default function ReportPage() {
  const params = useParams<{ id: string; reportId: string }>();
  const { data, reload: reloadRepo } = useRepo();
  const { data: report, error, reload } = usePoll(
    (signal) => getImpactReport(params.reportId, signal),
    1200,
    (r) => reportInProgress(r.status),
  );
  const [retrying, setRetrying] = useState(false);

  const base = `/repos/${params.id}`;
  const summaryOf = (c: string) => data?.revisions.find((r) => r.commitHash === c)?.summary || c.slice(0, 7);

  if (!report) {
    return error ? <Banner tone="red" title="This analysis could not be loaded">{error}</Banner> : <Skeleton lines={6} />;
  }
  const job = data?.jobs.find((j) => j.id === report.jobId);

  const header = (
    <div className={s.header}>
      <Link href={`${base}/analyze`} className={s.back}>
        ← All analyses
      </Link>
      <div className={s.headerRow}>
        <div>
          <h1>What this change could break</h1>
          <p className={s.muted}>
            <Sha value={report.baseCommit} /> → <Sha value={report.headCommit} /> · started <RelativeTime iso={report.createdAt} />
          </p>
        </div>
        {report.status === "COMPLETED" && (
          <div className={s.headerActions}>
            <a className={s.download} href={reportMarkdownUrl(report.id)} download>
              <Icon name="download" /> Download report
            </a>
            <ButtonLink href={`${base}/analyze`} icon="pulse">
              Analyze another change
            </ButtonLink>
          </div>
        )}
      </div>
    </div>
  );

  if (reportInProgress(report.status)) {
    return (
      <div className={s.page}>
        {header}
        <Card title="Analyzing the change…" subtitle="This page updates by itself. It usually takes a few seconds.">
          {job ? <LiveTimeline tasks={[{ job }]} /> : <Skeleton lines={3} />}
        </Card>
      </div>
    );
  }

  if (report.status === "FAILED") {
    return (
      <div className={s.page}>
        {header}
        <Banner
          tone="red"
          title="The analysis did not finish"
          action={
            <Button
              size="sm"
              icon="refresh"
              loading={retrying}
              onClick={async () => {
                setRetrying(true);
                try {
                  await requestImpactReport(params.id, report.baseCommit, report.headCommit, report.depth);
                  reload();
                  reloadRepo();
                } finally {
                  setRetrying(false);
                }
              }}
            >
              Try again
            </Button>
          }
        >
          {report.failureReason ?? "Something went wrong."}
        </Banner>
        {job && (
          <Card title="What happened" headingLevel={3}>
            <LiveTimeline tasks={[{ job }]} />
          </Card>
        )}
      </div>
    );
  }

  return (
    <div className={s.page}>
      {header}
      <Summary report={report} before={summaryOf(report.baseCommit)} after={summaryOf(report.headCommit)} />
      <FindingSection
        reportId={report.id}
        type="CHANGED"
        title="What changed"
        explain="Classes, methods and fields that were added, changed or removed between the two versions."
        empty={report.unmappedHunks ? "No class, method or field changed; the edits touch only imports, comments or blank lines." : "No Java code changed."}
      />
      <FindingSection
        reportId={report.id}
        type="ENDPOINT"
        title="API endpoints"
        explain="HTTP endpoints this change adds, removes, or reaches through the code that handles them."
      />
      <FindingSection
        reportId={report.id}
        type="AFFECTED"
        test={false}
        title="What could break"
        explain={
          <>
            Production code that depends on what changed, nearest first. Open “Why?” to see the chain of calls.
            <Help>
              “Possibly” means at least one link in the chain is a best guess, for example a call that could go to more than
              one method.
            </Help>
          </>
        }
        empty="No production code depends on the changed code."
        withModuleFilter
      />
      <FindingSection
        reportId={report.id}
        type="AFFECTED"
        test
        title="Tests to run"
        explain="Tests that exercise the changed code, directly, through other code, or because they are named after it."
        empty="No test depends on the changed code. Consider adding one."
      />
      <FindingSection
        reportId={report.id}
        type="POSSIBLE"
        title="Worth a manual check"
        explain="Calls to a method with the same name as a changed one, where the object's type could not be worked out. They are not counted above."
      />
    </div>
  );
}
