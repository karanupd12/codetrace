"use client";

import { useEffect, useState } from "react";
import {
  API_BASE_URL,
  fetchHealth,
  fetchSystemInfo,
  type Health,
  type SystemInfo,
} from "@/lib/api";
import styles from "./page.module.css";

type State =
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "ready"; info: SystemInfo; health: Health };

function Row({ label, ok, detail }: { label: string; ok: boolean; detail?: string }) {
  return (
    <li className={styles.row}>
      <span className={ok ? styles.dotUp : styles.dotDown} aria-hidden />
      <span className={styles.label}>{label}</span>
      <span className={styles.detail}>{ok ? "UP" : "DOWN"}{detail ? ` · ${detail}` : ""}</span>
    </li>
  );
}

export default function StatusPanel() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const ctrl = new AbortController();
    Promise.all([fetchSystemInfo(ctrl.signal), fetchHealth(ctrl.signal)])
      .then(([info, health]) => setState({ kind: "ready", info, health }))
      .catch((e: unknown) => {
        if (ctrl.signal.aborted) return;
        setState({
          kind: "error",
          message: e instanceof Error ? e.message : "Unknown error",
        });
      });
    return () => ctrl.abort();
  }, []);

  return (
    <section className={styles.card} aria-labelledby="status-title">
      <h2 id="status-title">System status</h2>
      {state.kind === "loading" && <p>Checking…</p>}
      {state.kind === "error" && (
        <p role="alert">
          API unreachable at {API_BASE_URL} ({state.message}).
        </p>
      )}
      {state.kind === "ready" && (
        <ul className={styles.list}>
          <Row label="API" ok={state.health.status === "UP"} detail={`${state.info.name} ${state.info.version}`} />
          <Row label="PostgreSQL" ok={state.health.components?.db?.status === "UP"} detail={state.info.dbMigrationVersion ? `migration v${state.info.dbMigrationVersion}` : undefined} />
          <Row label="Redis" ok={state.info.redis === "UP"} />
        </ul>
      )}
    </section>
  );
}
