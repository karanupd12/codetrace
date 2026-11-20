import StatusPanel from "./StatusPanel";
import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.main}>
      <header className={styles.header}>
        <h1>CODETRACE</h1>
        <p>Evidence-driven codebase change-impact intelligence.</p>
      </header>

      <section className={styles.card} aria-labelledby="connect-title">
        <h2 id="connect-title">Connect a repository</h2>
        <p className={styles.muted}>
          Paste a public GitHub URL or a local path to index it and analyze the
          impact of a change. Available in milestone M2.
        </p>
        <form className={styles.form}>
          <input
            type="text"
            placeholder="https://github.com/owner/repo"
            disabled
            aria-label="Repository URL or local path"
          />
          <button type="submit" disabled>
            Connect
          </button>
        </form>
      </section>

      <StatusPanel />
    </main>
  );
}
