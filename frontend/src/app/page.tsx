import RepoList from "./RepoList";
import StatusPanel from "./StatusPanel";
import styles from "./page.module.css";

export default function Home() {
  return (
    <main className={styles.main}>
      <header className={styles.header}>
        <h1>CODETRACE</h1>
        <p>Evidence-driven codebase change-impact intelligence.</p>
      </header>
      <RepoList />
      <StatusPanel />
    </main>
  );
}
