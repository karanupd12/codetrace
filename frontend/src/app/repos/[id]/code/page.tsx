"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { listDiagnostics, listEndpoints, searchSymbols, type CodeSymbol, type Diagnostic, type Endpoint, type Job } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { packageOf, shortName } from "@/lib/words";
import {
  Badge,
  Banner,
  ButtonLink,
  Card,
  EmptyState,
  Field,
  Segmented,
  ShowMore,
  Skeleton,
  controlClass,
  tableClass,
} from "@/components/ui";

type Tab = "classes" | "endpoints" | "problems";
const PAGE = 25;
const KINDS: Record<string, string> = { TYPE: "Class", METHOD: "Method", FIELD: "Field" };

/** Loads a paged list and appends further pages. */
function usePaged<T>(load: (offset: number, signal?: AbortSignal) => Promise<{ items: T[]; total: number }>, deps: unknown[]) {
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const ctrl = new AbortController();
    setTotal(null);
    const t = setTimeout(() => {
      load(0, ctrl.signal)
        .then((p) => {
          setItems(p.items);
          setTotal(p.total);
          setError(null);
        })
        .catch((e: unknown) => !ctrl.signal.aborted && setError(e instanceof Error ? e.message : "Could not load"));
    }, 200);
    return () => {
      ctrl.abort();
      clearTimeout(t);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  const more = () => {
    setLoading(true);
    load(items.length)
      .then((p) => setItems((cur) => [...cur, ...p.items]))
      .finally(() => setLoading(false));
  };
  return { items, total, loading, error, more };
}

function Classes({ jobId }: { jobId: string }) {
  const [kind, setKind] = useState("");
  const [q, setQ] = useState("");
  const list = usePaged<CodeSymbol>(
    (offset, signal) => searchSymbols(jobId, { kind: kind || undefined, q: q.trim() || undefined, limit: PAGE, offset }, signal),
    [jobId, kind, q],
  );
  return (
    <Card
      title="Classes, methods and fields"
      padded
      actions={
        <div style={{ display: "flex", gap: 8 }}>
          <select aria-label="Kind" className={controlClass} style={{ width: "auto" }} value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">Everything</option>
            <option value="TYPE">Classes</option>
            <option value="METHOD">Methods</option>
            <option value="FIELD">Fields</option>
          </select>
          <input aria-label="Search" type="search" className={controlClass} style={{ width: 240 }} placeholder="Search by name" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      }
    >
      {list.error && <Banner tone="red">{list.error}</Banner>}
      {list.total === null ? (
        <Skeleton lines={5} />
      ) : list.total === 0 ? (
        <p style={{ color: "var(--muted)" }}>Nothing matches.</p>
      ) : (
        <>
          <table className={tableClass}>
            <thead>
              <tr>
                <th>Name</th>
                <th>Kind</th>
                <th>File</th>
              </tr>
            </thead>
            <tbody>
              {list.items.map((sym) => (
                <tr key={`${sym.path}|${sym.qualifiedName}`}>
                  <td>
                    <code>{shortName(sym.qualifiedName)}</code>
                    <div style={{ color: "var(--muted)", fontSize: 12 }}>{packageOf(sym.qualifiedName)}</div>
                  </td>
                  <td>{KINDS[sym.kind] ?? sym.kind}</td>
                  <td style={{ color: "var(--muted)", fontSize: 13, wordBreak: "break-all" }}>
                    {sym.path}:{sym.startLine}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ShowMore shown={list.items.length} total={list.total} loading={list.loading} onMore={list.more} />
        </>
      )}
    </Card>
  );
}

function Endpoints({ jobId }: { jobId: string }) {
  const list = usePaged<Endpoint>((offset, signal) => listEndpoints(jobId, PAGE, signal, offset), [jobId]);
  return (
    <Card title="API endpoints" subtitle="HTTP routes declared with Spring MVC or JAX-RS annotations.">
      {list.total === null ? (
        <Skeleton lines={4} />
      ) : list.total === 0 ? (
        <EmptyState icon="route" title="No API endpoints found">
          This version declares no Spring MVC or JAX-RS endpoints.
        </EmptyState>
      ) : (
        <>
          <table className={tableClass}>
            <thead>
              <tr>
                <th>Method</th>
                <th>Route</th>
                <th>Handled by</th>
              </tr>
            </thead>
            <tbody>
              {list.items.map((e, i) => (
                <tr key={i}>
                  <td>
                    <Badge>{e.httpMethod}</Badge>
                  </td>
                  <td>{e.route ? <code>{e.route}</code> : <span style={{ color: "var(--muted)" }}>not written as plain text</span>}</td>
                  <td>
                    <code>{shortName(e.handlerQualifiedName)}</code>
                    <div style={{ color: "var(--muted)", fontSize: 12 }}>
                      {e.path}:{e.line}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ShowMore shown={list.items.length} total={list.total} loading={list.loading} onMore={list.more} />
        </>
      )}
    </Card>
  );
}

function Problems({ jobId }: { jobId: string }) {
  const list = usePaged<Diagnostic>((offset, signal) => listDiagnostics(jobId, PAGE, signal, offset), [jobId]);
  return (
    <Card title="Files that could not be fully read" subtitle="Usually syntax errors or files over the size limit. The rest of the code is still analyzed.">
      {list.total === null ? (
        <Skeleton lines={3} />
      ) : list.total === 0 ? (
        <EmptyState icon="check" title="No problems">
          Every Java file in this version was read completely.
        </EmptyState>
      ) : (
        <>
          <table className={tableClass}>
            <thead>
              <tr>
                <th>File</th>
                <th>Problem</th>
              </tr>
            </thead>
            <tbody>
              {list.items.map((d, i) => (
                <tr key={i}>
                  <td style={{ wordBreak: "break-all" }}>
                    {d.path}
                    {d.line ? `:${d.line}` : ""}
                  </td>
                  <td>
                    <Badge tone={d.severity === "ERROR" ? "red" : "yellow"}>{d.code.replace(/_/g, " ").toLowerCase()}</Badge>{" "}
                    <span style={{ color: "var(--muted)" }}>{d.message}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <ShowMore shown={list.items.length} total={list.total} loading={list.loading} onMore={list.more} />
        </>
      )}
    </Card>
  );
}

function Code() {
  const { data } = useRepo();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  if (!data) return null;

  const scans: Job[] = [];
  const seen = new Set<string>();
  for (const j of data.jobs) {
    if (j.operation === "INDEX" && j.status === "SUCCEEDED" && j.commitHash && !seen.has(j.commitHash)) {
      seen.add(j.commitHash);
      scans.push(j);
    }
  }
  if (scans.length === 0) {
    return (
      <Card>
        <EmptyState icon="code" title="No version has been scanned yet" action={<ButtonLink href={`/repos/${data.repo.id}`} variant="primary">Prepare a version</ButtonLink>}>
          Prepare a version on the Overview tab to browse its classes, API endpoints and any files that could not be read.
        </EmptyState>
      </Card>
    );
  }

  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params.toString());
    next.set(k, v);
    router.replace(`${pathname}?${next}`, { scroll: false });
  };
  const job = scans.find((j) => j.id === params.get("version")) ?? scans[0];
  const tab = (params.get("tab") as Tab) || "classes";
  const label = (j: Job) => {
    const rev = data.revisions.find((r) => r.commitHash === j.commitHash);
    return `${(rev?.summary ?? "").slice(0, 60) || j.commitHash?.slice(0, 7)} · ${j.commitHash?.slice(0, 7)}`;
  };

  return (
    <>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 14, flexWrap: "wrap" }}>
        <div style={{ minWidth: 320 }}>
          <Field label="Version" htmlFor="code-version">
            <select id="code-version" className={controlClass} value={job.id} onChange={(e) => set("version", e.target.value)}>
              {scans.map((j) => (
                <option key={j.id} value={j.id}>
                  {label(j)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Segmented<Tab>
          label="Show"
          value={tab}
          onChange={(t) => set("tab", t)}
          options={[
            { value: "classes", label: "Classes & methods", icon: "code" },
            { value: "endpoints", label: "API endpoints", icon: "route" },
            { value: "problems", label: "Parse problems", icon: "alert" },
          ]}
        />
      </div>
      {tab === "classes" && <Classes key={job.id} jobId={job.id} />}
      {tab === "endpoints" && <Endpoints key={job.id} jobId={job.id} />}
      {tab === "problems" && <Problems key={job.id} jobId={job.id} />}
    </>
  );
}

export default function CodePage() {
  return (
    <Suspense>
      <Code />
    </Suspense>
  );
}
