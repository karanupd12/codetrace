"use client";

import Link from "next/link";
import { listRepositories } from "@/lib/api";
import { usePoll } from "@/lib/usePoll";
import ConnectForm from "./ConnectForm";
import styles from "./page.module.css";

export default function RepoList() {
  const { data, error, reload } = usePoll(
    (signal) => listRepositories(signal),
    3000,
    (repos) => repos.some((r) => r.syncStatus === "PENDING"),
  );

  return (
    <>
      <ConnectForm onRegistered={reload} />
      <section className={styles.card} aria-labelledby="repos-title">
        <h2 id="repos-title">Repositories</h2>
        {error && <p role="alert" className={styles.error}>API unreachable: {error}</p>}
        {data && data.length === 0 && <p className={styles.muted}>No repositories yet. Connect one above.</p>}
        {data && data.length > 0 && (
          <ul className={styles.list}>
            {data.map((r) => (
              <li key={r.id} className={styles.row}>
                <span className={`${styles.badge} ${styles["badge" + r.syncStatus]}`}>{r.syncStatus}</span>
                <Link href={`/repos/${r.id}`} className={styles.label}>
                  {r.displayName}
                </Link>
                <span className={styles.detail}>
                  {r.sourceType === "GIT_URL" ? "Git URL" : "Local"}
                  {r.headCommit ? ` · ${r.defaultBranch ?? "HEAD"} @ ${r.headCommit.slice(0, 7)}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
