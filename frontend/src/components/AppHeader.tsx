"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { fetchSystemInfo, type SystemInfo } from "@/lib/api";
import Icon, { type IconName } from "./Icon";
import s from "./AppHeader.module.css";

type Theme = "light" | "dark" | "system";
const THEME_KEY = "codetrace:theme";

function applyTheme(t: Theme) {
  if (t === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.setAttribute("data-theme", t);
}

function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>("system");
  useEffect(() => {
    try {
      const saved = localStorage.getItem(THEME_KEY) as Theme | null;
      if (saved === "light" || saved === "dark") setTheme(saved);
    } catch {
      /* no storage: follow the system */
    }
  }, []);
  const choose = (t: Theme) => {
    setTheme(t);
    applyTheme(t);
    try {
      if (t === "system") localStorage.removeItem(THEME_KEY);
      else localStorage.setItem(THEME_KEY, t);
    } catch {
      /* not remembered */
    }
  };
  const options: { value: Theme; icon: IconName; label: string }[] = [
    { value: "light", icon: "sun", label: "Light theme" },
    { value: "dark", icon: "moon", label: "Dark theme" },
    { value: "system", icon: "monitor", label: "Match system theme" },
  ];
  return (
    <div className={s.theme} role="group" aria-label="Theme">
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={theme === o.value} aria-label={o.label} title={o.label} onClick={() => choose(o.value)}>
          <Icon name={o.icon} size={14} />
        </button>
      ))}
    </div>
  );
}

function SystemStatus() {
  const [info, setInfo] = useState<SystemInfo | null>(null);
  const [down, setDown] = useState(false);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    const load = () =>
      fetchSystemInfo(ctrl.signal)
        .then((i) => {
          setInfo(i);
          setDown(false);
        })
        .catch(() => {
          if (!ctrl.signal.aborted) setDown(true);
        });
    void load();
    const t = setInterval(load, 30000);
    return () => {
      ctrl.abort();
      clearInterval(t);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  // the info endpoint reads the database, so an answer means the service and the database are up
  const dbUp = !down && info?.dbMigrationVersion != null;
  const redis = info?.redis;
  const ok = !down && dbUp && redis === "UP";
  const label = down ? "Service unreachable" : !info ? "Checking…" : ok ? "All systems working" : "Some services are down";

  return (
    <div className={s.status} ref={ref}>
      <button type="button" className={s.statusButton} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <span className={`${s.statusDot} ${down || (info && !ok) ? s.bad : info ? s.good : ""}`} aria-hidden />
        <span className={s.statusLabel}>{label}</span>
      </button>
      {open && (
        <div className={s.popover} role="dialog" aria-label="System status">
          <strong>System status</strong>
          <ul>
            <li>
              <span className={`${s.statusDot} ${down ? s.bad : s.good}`} />
              Service {info ? `· version ${info.version}` : ""}
            </li>
            <li>
              <span className={`${s.statusDot} ${dbUp ? s.good : s.bad}`} />
              Database {info?.dbMigrationVersion ? `· schema ${info.dbMigrationVersion}` : ""}
            </li>
            <li>
              <span className={`${s.statusDot} ${redis === "UP" ? s.good : s.bad}`} />
              Task queue
            </li>
          </ul>
          <span className={s.popoverHint}>Refreshed every 30 seconds.</span>
        </div>
      )}
    </div>
  );
}

export default function AppHeader() {
  return (
    <header className={s.header}>
      <div className={s.inner}>
        <Link href="/" className={s.brand}>
          <span className={s.logo} aria-hidden>
            <Icon name="logo" size={18} />
          </span>
          CODETRACE
        </Link>
        <nav className={s.nav} aria-label="Main">
          <Link href="/">Repositories</Link>
        </nav>
        <div className={s.right}>
          <SystemStatus />
          <ThemeSwitch />
        </div>
      </div>
    </header>
  );
}
