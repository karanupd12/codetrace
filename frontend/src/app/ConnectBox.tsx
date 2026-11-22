"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { registerRepository, type SourceType } from "@/lib/api";
import { Banner, Button, controlClass } from "@/components/ui";
import s from "./page.module.css";

const EXAMPLE = "https://github.com/google/gson";

/** A web link is a Git URL; anything else is treated as a folder on the server. */
function sourceOf(value: string): SourceType {
  return /^https?:\/\//i.test(value.trim()) ? "GIT_URL" : "LOCAL";
}

export default function ConnectBox({ autoFocus }: { autoFocus?: boolean }) {
  const router = useRouter();
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function connect(e: React.FormEvent) {
    e.preventDefault();
    const location = value.trim();
    if (!location) return;
    setBusy(true);
    setError(null);
    try {
      const r = await registerRepository(sourceOf(location), location);
      router.push(`/repos/${r.repository.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not connect the repository.");
      setBusy(false);
    }
  }

  return (
    <form className={s.connect} onSubmit={connect}>
      <div className={s.connectRow}>
        <label htmlFor="repo-location" className="visually-hidden">
          Repository link or folder
        </label>
        <input
          id="repo-location"
          className={controlClass}
          placeholder="Paste a public GitHub link, e.g. https://github.com/owner/project"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          autoFocus={autoFocus}
          autoComplete="off"
          spellCheck={false}
        />
        <Button type="submit" variant="primary" size="lg" loading={busy} disabled={!value.trim()}>
          Connect
        </Button>
      </div>
      <div className={s.examples}>
        <span>Try an example:</span>
        <button type="button" className={s.linkButton} onClick={() => setValue(EXAMPLE)}>
          google/gson
        </button>
        <span aria-hidden>·</span>
        <span>
          Local folder? Put it in <code>data/local-repos</code> and enter <code>/repos/&lt;folder&gt;</code>.
        </span>
      </div>
      {error && <Banner tone="red" title="Could not connect">{error}</Banner>}
    </form>
  );
}
