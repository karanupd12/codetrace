"use client";

import { useEffect, useState } from "react";
import {
  listDiagnostics,
  listEndpoints,
  searchSymbols,
  type CodeSymbol,
  type Diagnostic,
  type Endpoint,
  type Page,
} from "@/lib/api";
import styles from "../../page.module.css";

const PAGE = 25;

export default function SymbolBrowser({ jobId }: { jobId: string }) {
  const [kind, setKind] = useState("");
  const [q, setQ] = useState("");
  const [offset, setOffset] = useState(0);
  const [symbols, setSymbols] = useState<Page<CodeSymbol> | null>(null);
  const [diagnostics, setDiagnostics] = useState<Page<Diagnostic> | null>(null);
  const [endpoints, setEndpoints] = useState<Page<Endpoint> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      searchSymbols(jobId, { kind: kind || undefined, q: q.trim() || undefined, limit: PAGE, offset }, ctrl.signal)
        .then((p) => {
          setSymbols(p);
          setError(null);
        })
        .catch((e: unknown) => {
          if (!ctrl.signal.aborted) setError(e instanceof Error ? e.message : "Failed to load symbols");
        });
    }, 200); // debounce typing
    return () => {
      ctrl.abort();
      clearTimeout(timer);
    };
  }, [jobId, kind, q, offset]);

  useEffect(() => {
    const ctrl = new AbortController();
    listDiagnostics(jobId, 20, ctrl.signal)
      .then(setDiagnostics)
      .catch(() => undefined);
    listEndpoints(jobId, 200, ctrl.signal)
      .then(setEndpoints)
      .catch(() => undefined);
    return () => ctrl.abort();
  }, [jobId]);

  const total = symbols?.total ?? 0;

  return (
    <>
      <section className={styles.card}>
        <h2>Symbols {symbols ? `(${total})` : ""}</h2>
        <div className={styles.form}>
          <select
            aria-label="Symbol kind"
            value={kind}
            onChange={(e) => {
              setKind(e.target.value);
              setOffset(0);
            }}
          >
            <option value="">All kinds</option>
            <option value="TYPE">Types</option>
            <option value="METHOD">Methods</option>
            <option value="FIELD">Fields</option>
          </select>
          <input
            type="search"
            placeholder="Filter by qualified name, e.g. Order#addLine"
            aria-label="Filter symbols"
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setOffset(0);
            }}
          />
        </div>
        {error && <p role="alert" className={styles.error}>{error}</p>}
        {symbols && symbols.items.length === 0 && <p className={styles.muted}>No symbols match.</p>}
        {symbols && symbols.items.length > 0 && (
          <table className={styles.table}>
            <thead>
              <tr><th>Kind</th><th>Qualified name</th><th>File : line</th></tr>
            </thead>
            <tbody>
              {symbols.items.map((s) => (
                <tr key={`${s.path}|${s.qualifiedName}`}>
                  <td>{s.subtype}</td>
                  <td><code>{s.qualifiedName}</code></td>
                  <td className={styles.muted}>{s.path}:{s.startLine}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {symbols && total > PAGE && (
          <p className={styles.pager}>
            <button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>Previous</button>
            <span>{offset + 1}–{Math.min(offset + PAGE, total)} of {total}</span>
            <button disabled={offset + PAGE >= total} onClick={() => setOffset(offset + PAGE)}>Next</button>
          </p>
        )}
      </section>

      {endpoints && endpoints.total > 0 && (
        <section className={styles.card}>
          <h2>HTTP endpoints ({endpoints.total})</h2>
          <table className={styles.table}>
            <thead>
              <tr><th>Method</th><th>Route</th><th>Handler</th><th>File : line</th></tr>
            </thead>
            <tbody>
              {endpoints.items.map((e, i) => (
                <tr key={i}>
                  <td>{e.httpMethod}</td>
                  <td>{e.route ? <code>{e.route}</code> : <span className={styles.muted}>not a literal</span>}</td>
                  <td><code>{e.handlerQualifiedName}</code></td>
                  <td className={styles.muted}>{e.path}:{e.line}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {endpoints.total > endpoints.items.length && (
            <p className={styles.muted}>Showing the first {endpoints.items.length}.</p>
          )}
        </section>
      )}

      {diagnostics && diagnostics.total > 0 && (
        <section className={styles.card}>
          <h2>Parser diagnostics ({diagnostics.total})</h2>
          <ul className={styles.list}>
            {diagnostics.items.map((d, i) => (
              <li key={i} className={styles.row}>
                <span className={`${styles.badge} ${d.severity === "ERROR" ? styles.badgeFAILED : styles.badgePENDING}`}>
                  {d.code}
                </span>
                <span className={styles.label}>{d.path}:{d.line}</span>
                <span className={styles.detail}>{d.message}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
