"use client";

import { useState } from "react";
import { registerRepository, type SourceType } from "@/lib/api";
import styles from "./page.module.css";

export default function ConnectForm({ onRegistered }: { onRegistered: () => void }) {
  const [sourceType, setSourceType] = useState<SourceType>("GIT_URL");
  const [location, setLocation] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await registerRepository(sourceType, location.trim());
      setLocation("");
      onRegistered();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not connect repository");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={styles.card} aria-labelledby="connect-title">
      <h2 id="connect-title">Connect a repository</h2>
      <p className={styles.muted}>
        Paste a public GitHub (or GitLab/Bitbucket) HTTPS URL, or a path to a Git repository on the server.
        Repository code is only read, never executed.
      </p>
      <form className={styles.form} onSubmit={submit}>
        <select
          aria-label="Source type"
          value={sourceType}
          onChange={(e) => setSourceType(e.target.value as SourceType)}
        >
          <option value="GIT_URL">GitHub / Git URL</option>
          <option value="LOCAL">Local path</option>
        </select>
        <input
          type="text"
          required
          maxLength={2048}
          placeholder={sourceType === "GIT_URL" ? "https://github.com/owner/repo" : "/repos/demo-java"}
          aria-label="Repository URL or local path"
          value={location}
          onChange={(e) => setLocation(e.target.value)}
        />
        <button type="submit" disabled={busy || !location.trim()}>
          {busy ? "Connecting…" : "Connect"}
        </button>
      </form>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
    </section>
  );
}
