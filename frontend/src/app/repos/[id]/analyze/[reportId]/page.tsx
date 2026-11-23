"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import {
  getImpactReport,
  getImpactSummary,
  listFindings,
  reportInProgress,
  reportMarkdownUrl,
  requestImpactReport,
  type Finding,
  type FindingType,
  type Hop,
  type ImpactReport,
  type ImpactSummary,
  type RiskFactor,
} from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { usePoll } from "@/lib/usePoll";
import { fileName, findingKind, methodHue, plural, shortName, type Hue } from "@/lib/words";
import Icon, { type IconName } from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import {
  Banner,
  Button,
  ButtonLink,
  Card,
  Details,
  Help,
  RelativeTime,
  RiskBadge,
  Sha,
  ShowMore,
  Skeleton,
  controlClass,
  riskWord,
} from "@/components/ui";
import { Bars, ChainView, DistanceDots, IconTile, KindChip, Pill, RiskGauge, SplitBar, StatRow, StatTile, type BarDatum } from "@/components/viz";
import s from "./report.module.css";

const PAGE = 10;

const CHANGE: Record<string, { label: string; tone: Hue }> = {
  ADDED: { label: "Added", tone: "green" },
  MODIFIED: { label: "Changed", tone: "orange" },
  REMOVED: { label: "Removed", tone: "red" },
};

/** "Why?" opens the evidence chain as a line of connected symbols. */
function Why({ chain }: { chain: Hop[] }) {
  if (!chain?.length) return null;
  return (
    <details className={s.why2}>
      <summary>
        <Icon name="link" size={13} /> Why?
      </summary>
      <ChainView chain={chain} />
    </details>
  );
}

function FindingRow({ f }: { f: Finding }) {
  if (f.findingType === "CHANGED") {
    const c = CHANGE[f.changeKind ?? "MODIFIED"];
    return (
      <li className={s.row}>
        <Pill tone={c.tone}>{c.label}</Pill>
        <div className={s.rowMain}>
          <KindChip kind={findingKind(f.nodeType, f.test)} name={shortName(f.name)} title={f.name} />
          <span className={s.where}>
            {f.path}:{f.line}
            {f.directDependents ? ` · used directly in ${plural(f.directDependents, "place")}` : ""}
          </span>
        </div>
      </li>
    );
  }
  if (f.findingType === "ENDPOINT") {
    const c = f.changeKind ? CHANGE[f.changeKind] : null;
    const space = f.name.indexOf(" ");
    const method = space > 0 ? f.name.slice(0, space) : f.name;
    const route = space > 0 ? f.name.slice(space + 1) : "";
    return (
      <li className={s.row}>
        <Pill tone={methodHue(method)}>{method}</Pill>
        <div className={s.rowMain}>
          <span className={s.nameLine}>
            <code className={s.name}>{route}</code>
            {c ? <Pill tone={c.tone}>{c.label}</Pill> : null}
          </span>
          <span className={s.where}>
            handled by <code>{shortName(f.changedSymbol)}</code> · {fileName(f.path)}:{f.line}
          </span>
          {f.distance ? (
            <span className={s.where}>
              <DistanceDots distance={f.distance} /> {plural(f.distance, "step")} from the change
            </span>
          ) : null}
          <Why chain={f.chain} />
        </div>
      </li>
    );
  }
  if (f.findingType === "POSSIBLE") {
    return (
      <li className={s.row}>
        <IconTile icon="question" tone="yellow" size={28} />
        <div className={s.rowMain}>
          <KindChip kind="METHOD" name={shortName(f.name)} title={f.name} />
          <span className={s.where}>
            may call <code>{shortName(f.changedSymbol)}</code> · {fileName(f.path)}:{f.line}
          </span>
        </div>
      </li>
    );
  }
  return (
    <li className={s.row}>
      <DistanceDots distance={f.distance ?? 1} tone={f.test ? "violet" : "orange"} />
      <div className={s.rowMain}>
        <span className={s.nameLine}>
          <KindChip kind={findingKind(f.nodeType, f.test)} name={shortName(f.name)} title={f.name} />
          {f.certain === false && <Pill tone="yellow">possibly</Pill>}
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

function FindingSection({ id, reportId, title, icon, tone, explain, type, test, empty, withModuleFilter }: {
  id: string;
  reportId: string;
  title: string;
  icon: IconName;
  tone: Hue;
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
    <div id={id} className={s.anchor}>
      <Card
        title={
          <span className={s.sectionTitle}>
            <IconTile icon={icon} tone={tone} size={28} />
            {title} {total !== null && <span className={s.count}>{total.toLocaleString()}</span>}
          </span>
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
    </div>
  );
}

const jump = (id: string) => () => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });

const factorName = (f: RiskFactor) => {
  const n = f.signal.replace(/^\d+-/, "").replace(/-/g, " ");
  return n.charAt(0).toUpperCase() + n.slice(1);
};

/** How strongly a check pushed the score: its share of its own maximum. */
function factorTone(f: RiskFactor): Hue {
  const share = f.maxPoints ? f.points / f.maxPoints : 0;
  if (share >= 0.66) return "red";
  if (share >= 0.33) return "orange";
  if (share > 0) return "yellow";
  return "neutral";
}

function Hero({ report, summary, before, after }: { report: ImpactReport; summary: ImpactSummary | null; before: string; after: string }) {
  const level = report.riskLevel ?? "LOW";
  const tests = report.testsAffectedCount ?? 0;
  const production = (report.affectedCount ?? 0) - tests;
  const endpoints = summary ? Object.values(summary.endpoints).reduce((a, b) => a + b, 0) : 0;
  return (
    <section className={s.hero}>
      <div className={s.heroGauge}>
        <RiskGauge score={report.riskScore ?? 0} label={<RiskBadge level={level} />} />
      </div>
      <div className={s.heroMain}>
        <h2>
          {riskWord(level)} risk: {plural(production, "place")} in production code and {plural(tests, "test")} depend on what changed.
        </h2>
        <p className={s.versions}>
          <span className={s.versionChip}>{before}</span>
          <Icon name="arrowRight" size={14} />
          <span className={s.versionChip}>{after}</span>
        </p>
        <StatRow>
          <StatTile icon="diff" tone="blue" value={report.changedCount ?? 0} label="changed" onClick={jump("changed")} />
          <StatTile icon="alert" tone="orange" value={production} label="could break" onClick={jump("affected")} />
          <StatTile icon="test" tone="violet" value={tests} label={tests === 1 ? "test to run" : "tests to run"} onClick={jump("tests")} />
          {endpoints > 0 && <StatTile icon="route" tone="pink" value={endpoints} label="API endpoints" onClick={jump("endpoints")} />}
          {(report.possibleCount ?? 0) > 0 && (
            <StatTile icon="question" tone="yellow" value={report.possibleCount ?? 0} label="worth a check" onClick={jump("possible")} />
          )}
        </StatRow>
      </div>
    </section>
  );
}

function WhyRisk({ report }: { report: ImpactReport }) {
  const raised = report.riskFactors.filter((f) => f.points > 0).sort((a, b) => b.points - a.points);
  const quiet = report.riskFactors.filter((f) => f.points === 0);
  const bars = (list: RiskFactor[]): BarDatum[] =>
    list.map((f) => ({
      key: f.signal,
      label: factorName(f),
      value: f.points,
      max: f.maxPoints || 1,
      tone: factorTone(f),
      valueText: `${f.points} / ${f.maxPoints} pts`,
      note: f.reason,
    }));
  return (
    <Card title={<span className={s.sectionTitle}><IconTile icon="pulse" tone="red" size={28} />Why this risk</span>} subtitle="Each check adds points up to its own limit; the score is their total, capped at 100.">
      {raised.length === 0 ? (
        <p className={s.muted}>Nothing raised the risk: the change touches little that other code relies on.</p>
      ) : (
        <Bars data={bars(raised)} />
      )}
      {quiet.length > 0 && (
        <div className={s.quiet}>
          <Details summary={`${plural(quiet.length, "check")} found nothing`}>
            <Bars data={bars(quiet)} />
          </Details>
        </div>
      )}
      <p className={s.footnote}>
        Analysis {report.algorithmVersion}, followed dependencies up to {report.depth} steps.
      </p>
    </Card>
  );
}

function WhereItLands({ summary }: { summary: ImpactSummary }) {
  const production = summary.modules.reduce((n, m) => n + m.production, 0);
  const tests = summary.modules.reduce((n, m) => n + m.tests, 0);
  const total = summary.byType.AFFECTED;
  if (total === 0) {
    return (
      <Card title={<span className={s.sectionTitle}><IconTile icon="map" tone="teal" size={28} />Where the impact lands</span>}>
        <p className={s.muted}>Nothing else in the code depends on what changed.</p>
      </Card>
    );
  }
  const moduleBars: BarDatum[] = summary.modules.map((m) => ({
    key: m.module,
    label: m.module && m.module !== "(root)" ? m.module : "main module",
    value: m.production + m.tests,
    tone: "blue",
    valueText: m.tests ? `${m.production} code · ${plural(m.tests, "test")}` : `${m.production} code`,
  }));
  const distanceBars: BarDatum[] = summary.distances.map((d) => ({
    key: String(d.distance),
    label: d.distance === 1 ? "Directly" : `${d.distance} steps away`,
    value: d.production + d.tests,
    tone: "orange",
  }));
  return (
    <Card
      title={<span className={s.sectionTitle}><IconTile icon="map" tone="teal" size={28} />Where the impact lands</span>}
      subtitle={summary.uncertain > 0 ? `${plural(summary.uncertain, "result")} rely on a best guess somewhere in the chain.` : "Every link in these chains was resolved exactly."}
    >
      <div className={s.lands}>
        <div className={s.block}>
          <h3>Production code and tests</h3>
          <SplitBar
            label="Affected code"
            parts={[
              { key: "p", label: "Production code", value: production, tone: "blue" },
              { key: "t", label: "Tests", value: tests, tone: "violet" },
            ]}
          />
        </div>
        {summary.modules.length > 1 && (
          <div className={s.block}>
            <h3>By module</h3>
            <Bars data={moduleBars} />
            {summary.otherModules > 0 && <p className={s.footnote}>and {plural(summary.otherModules, "more result")} in smaller modules</p>}
          </div>
        )}
        {distanceBars.length > 0 && (
          <div className={s.block}>
            <h3>How far it spreads</h3>
            <Bars data={distanceBars} />
          </div>
        )}
      </div>
    </Card>
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
  const [summary, setSummary] = useState<ImpactSummary | null>(null);

  const completed = report?.status === "COMPLETED";
  useEffect(() => {
    if (!completed) return;
    const ctrl = new AbortController();
    getImpactSummary(params.reportId, ctrl.signal)
      .then(setSummary)
      .catch(() => undefined); // the charts are extra; the page works without them
    return () => ctrl.abort();
  }, [completed, params.reportId]);

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
      <Hero report={report} summary={summary} before={summaryOf(report.baseCommit)} after={summaryOf(report.headCommit)} />
      {report.notes?.map((note) => (
        <Banner key={note} tone="yellow" title="Not checked automatically">
          {note}
        </Banner>
      ))}
      {report.truncated && (
        <Banner tone="yellow" title="This is a lower bound">
          The analysis stopped at a size limit, so more code may be affected than is listed.
        </Banner>
      )}
      <div className={s.charts}>
        <WhyRisk report={report} />
        {summary ? <WhereItLands summary={summary} /> : <Card><Skeleton lines={5} /></Card>}
      </div>
      <FindingSection
        id="changed"
        reportId={report.id}
        type="CHANGED"
        icon="diff"
        tone="blue"
        title="What changed"
        explain="Classes, methods and fields that were added, changed or removed between the two versions."
        empty={report.unmappedHunks ? "No class, method or field changed; the edits touch only imports, comments or blank lines." : "No Java code changed."}
      />
      <FindingSection
        id="endpoints"
        reportId={report.id}
        type="ENDPOINT"
        icon="route"
        tone="pink"
        title="API endpoints"
        explain="HTTP endpoints this change adds, removes, or reaches through the code that handles them."
      />
      <FindingSection
        id="affected"
        reportId={report.id}
        type="AFFECTED"
        test={false}
        icon="alert"
        tone="orange"
        title="What could break"
        explain={
          <>
            Production code that depends on what changed, nearest first. The dots show how close each one is; open “Why?” to see the chain.
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
        id="tests"
        reportId={report.id}
        type="AFFECTED"
        test
        icon="test"
        tone="violet"
        title="Tests to run"
        explain="Tests that exercise the changed code, directly, through other code, or because they are named after it."
        empty="No test depends on the changed code. Consider adding one."
      />
      <FindingSection
        id="possible"
        reportId={report.id}
        type="POSSIBLE"
        icon="question"
        tone="yellow"
        title="Worth a manual check"
        explain="Calls to a method with the same name as a changed one, where the object's type could not be worked out. They are not counted above."
      />
    </div>
  );
}
