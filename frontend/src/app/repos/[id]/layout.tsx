"use client";

import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { isActive, resync } from "@/lib/api";
import { RepoProvider, useRepo } from "@/lib/repo";
import { JOB_TITLE, type Hue } from "@/lib/words";
import CommandPalette from "@/components/CommandPalette";
import Icon, { type IconName } from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { Badge, Banner, Button, Skeleton } from "@/components/ui";
import { RepoAvatar, ToastProvider } from "@/components/viz";
import s from "./repo.module.css";

const TABS: { href: string; label: string; icon: IconName; tone: Hue }[] = [
  { href: "", label: "Overview", icon: "repo", tone: "blue" },
  { href: "/analyze", label: "Analyze a change", icon: "pulse", tone: "orange" },
  { href: "/explore", label: "Explore", icon: "map", tone: "teal" },
  { href: "/code", label: "Code", icon: "code", tone: "violet" },
  { href: "/activity", label: "Activity", icon: "list", tone: "pink" },
];

function RepoHeader({ onJump }: { onJump: () => void }) {
  const { data, reload } = useRepo();
  const pathname = usePathname();
  const params = useParams<{ id: string }>();
  const [checking, setChecking] = useState(false);
  const base = `/repos/${params.id}`;
  const repo = data?.repo;
  const analyses = data?.reports.filter((r) => r.status === "COMPLETED").length ?? 0;
  const running = data?.jobs.some((j) => isActive(j.status)) ?? false;

  return (
    <div className={s.repoHeader}>
      <div className={s.repoHeaderInner}>
        <div className={s.titleRow}>
          <div className={s.title}>
            <RepoAvatar name={repo?.displayName ?? ""} size={34} />
            <Link href="/" className={s.crumb}>
              Repositories
            </Link>
            <span className={s.slash}>/</span>
            <h1>{repo?.displayName ?? "…"}</h1>
            <span className={s.lang}>
              <span className={s.langDot} aria-hidden /> Java
            </span>
            {repo?.syncStatus === "PENDING" && <Badge tone="yellow" dot>Connecting</Badge>}
            {repo?.syncStatus === "FAILED" && <Badge tone="red" dot>Connection failed</Badge>}
          </div>
          {repo && (
            <div className={s.titleActions}>
              <button type="button" className={s.jump} onClick={onJump} aria-label="Jump to (Ctrl+K)">
                <Icon name="search" size={14} />
                <span className={s.jumpText}>Jump to…</span>
                <kbd>Ctrl K</kbd>
              </button>
              {repo.originUrl && (
                <a href={repo.originUrl} target="_blank" rel="noreferrer" className={s.origin}>
                  <Icon name="link" size={14} /> View on GitHub
                </a>
              )}
              <Button
                size="sm"
                icon="refresh"
                loading={checking}
                onClick={async () => {
                  setChecking(true);
                  try {
                    await resync(repo.id);
                    reload();
                  } finally {
                    setChecking(false);
                  }
                }}
              >
                Check for new versions
              </Button>
            </div>
          )}
        </div>
        <nav className={s.tabs} aria-label="Repository">
          {TABS.map((t) => {
            const href = base + t.href;
            const active = t.href === "" ? pathname === base : pathname.startsWith(href);
            return (
              <Link key={t.label} href={href} className={`${s.tab} ${s[`tab-${t.tone}`]}`} aria-current={active ? "page" : undefined}>
                <Icon name={t.icon} size={15} />
                {t.label}
                {t.href === "/analyze" && analyses > 0 && <span className={s.tabCount}>{analyses}</span>}
                {t.href === "/activity" && running && <span className={s.tabLive} title="Something is running" />}
              </Link>
            );
          })}
        </nav>
      </div>
    </div>
  );
}

/** Shows live progress for every running task, on every repository page. */
function RunningNow() {
  const { data, reload } = useRepo();
  const [open, setOpen] = useState(true);
  const running = data?.jobs.filter((j) => isActive(j.status)) ?? [];
  if (running.length === 0) return null;
  const titles = [...new Set(running.map((j) => JOB_TITLE[j.operation]))];
  return (
    <aside className={s.dock} aria-label="Running now">
      <button type="button" className={s.dockHeader} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className={s.pulse} aria-hidden />
        <span className={s.dockTitle}>
          {running.length === 1 ? titles[0] : `${running.length} tasks running`}
        </span>
        <Icon name="chevronDown" size={14} />
      </button>
      {open && (
        <div className={s.dockBody}>
          <LiveTimeline compact tasks={[...running].reverse().map((job) => ({ job }))} onRetry={reload} />
        </div>
      )}
    </aside>
  );
}

function Body({ children }: { children: ReactNode }) {
  const { data, error } = useRepo();
  if (!data) {
    return (
      <main className={s.page}>
        {error ? (
          <Banner tone="red" title="This repository could not be loaded">
            {error}
          </Banner>
        ) : (
          <Skeleton lines={6} />
        )}
      </main>
    );
  }
  return (
    <main className={s.page}>
      {error && (
        <Banner tone="yellow" title="Showing the last known state">
          The service did not answer ({error}). Retrying automatically.
        </Banner>
      )}
      {data.repo.syncStatus === "FAILED" && (
        <Banner tone="red" title="The repository could not be downloaded">
          {data.jobs.find((j) => j.operation === "SYNC")?.errorMessage ?? "Check the link and try again."}
        </Banner>
      )}
      {children}
    </main>
  );
}

export default function RepoLayout({ children }: { children: ReactNode }) {
  const params = useParams<{ id: string }>();
  const [palette, setPalette] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPalette((p) => !p);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  return (
    <RepoProvider repoId={params.id}>
      <ToastProvider>
        <RepoHeader onJump={() => setPalette(true)} />
        <Body>{children}</Body>
        <RunningNow />
        <CommandPalette open={palette} onClose={() => setPalette(false)} />
      </ToastProvider>
    </RepoProvider>
  );
}
