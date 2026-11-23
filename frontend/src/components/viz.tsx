"use client";

import Link from "next/link";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import type { Hop } from "@/lib/api";
import { EDGE_WORD, KIND_HUE, fileName, shortName, type Hue } from "@/lib/words";
import Icon, { type IconName } from "./Icon";
import s from "./viz.module.css";

const hue = (h: Hue) => s[`hue-${h}`];

// ---- numbers ----

/** A number that counts up to its value when it first appears (instant under reduced motion). */
export function CountUp({ value }: { value: number }) {
  const [shown, setShown] = useState(value);
  const from = useRef(0);
  useEffect(() => {
    const reduce = typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (reduce || value === 0) {
      setShown(value);
      from.current = value;
      return;
    }
    const start = performance.now();
    const begin = from.current;
    let frame = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / 700);
      const eased = 1 - (1 - t) ** 3;
      setShown(Math.round(begin + (value - begin) * eased));
      if (t < 1) frame = requestAnimationFrame(tick);
      else from.current = value;
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return <>{shown.toLocaleString()}</>;
}

// ---- tiles ----

export function IconTile({ icon, tone, size = 36 }: { icon: IconName; tone: Hue; size?: number }) {
  return (
    <span className={`${s.iconTile} ${hue(tone)}`} style={{ width: size, height: size }} aria-hidden>
      <Icon name={icon} size={Math.round(size * 0.5)} />
    </span>
  );
}

/** A coloured tile with a big number; a link or button when it leads somewhere. */
export function StatTile({ icon, tone, value, label, href, onClick, display }: {
  icon: IconName;
  tone: Hue;
  value: number;
  label: ReactNode;
  href?: string;
  onClick?: () => void;
  /** Shown instead of the number, e.g. a risk word. */
  display?: ReactNode;
}) {
  const body = (
    <>
      <IconTile icon={icon} tone={tone} />
      <span className={s.statValue}>{display ?? <CountUp value={value} />}</span>
      <span className={s.statLabel}>{label}</span>
    </>
  );
  const cls = `${s.stat} ${hue(tone)}`;
  if (href) return <Link href={href} className={cls}>{body}</Link>;
  if (onClick) return <button type="button" className={cls} onClick={onClick}>{body}</button>;
  return <div className={cls}>{body}</div>;
}

export function StatRow({ children }: { children: ReactNode }) {
  return <div className={s.statRow}>{children}</div>;
}

// ---- risk gauge ----

const ZONES: { to: number; color: string }[] = [
  { to: 25, color: "var(--green)" },
  { to: 50, color: "var(--yellow)" },
  { to: 75, color: "var(--orange)" },
  { to: 100, color: "var(--red)" },
];

function arc(cx: number, cy: number, r: number, from: number, to: number) {
  // 0 = left end of the half circle, 100 = right end
  const a0 = Math.PI * (1 - from / 100);
  const a1 = Math.PI * (1 - to / 100);
  const p = (a: number) => `${(cx + r * Math.cos(a)).toFixed(2)} ${(cy - r * Math.sin(a)).toFixed(2)}`;
  return `M ${p(a0)} A ${r} ${r} 0 0 1 ${p(a1)}`;
}

/** A half-circle gauge from 0 to 100 with green, yellow, orange and red zones and a needle at the score. */
export function RiskGauge({ score, label, size = 200 }: { score: number; label: ReactNode; size?: number }) {
  const w = size;
  const h = size / 2 + 16;
  const cx = w / 2;
  const cy = size / 2 + 4;
  const r = size / 2 - 12;
  const [angle, setAngle] = useState(-90);
  useEffect(() => {
    const id = requestAnimationFrame(() => setAngle(-90 + (Math.max(0, Math.min(100, score)) / 100) * 180));
    return () => cancelAnimationFrame(id);
  }, [score]);
  let start = 0;
  return (
    <div className={s.gauge} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={score} aria-label={`Risk score ${score} of 100`}>
      <svg className={s.gaugeSvg} width={w} height={h} viewBox={`0 0 ${w} ${h}`} aria-hidden>
        {ZONES.map((z) => {
          const d = arc(cx, cy, r, start + (start === 0 ? 0 : 0.8), z.to - (z.to === 100 ? 0 : 0.8));
          start = z.to;
          return <path key={z.to} d={d} className={s.gaugeTrack} stroke={z.color} />;
        })}
        <line
          className={s.gaugeNeedle}
          x1={cx}
          y1={cy}
          x2={cx}
          y2={cy - r + 4}
          style={{ transform: `rotate(${angle}deg)`, transformOrigin: `${cx}px ${cy}px` }}
        />
        <circle className={s.gaugeHub} cx={cx} cy={cy} r={6} />
      </svg>
      <span className={s.gaugeScore}>
        {score}
        <span className={s.gaugeOf}> / 100</span>
      </span>
      {label}
    </div>
  );
}

// ---- bars ----

export type BarDatum = { key: string; label: ReactNode; value: number; max?: number; tone: Hue; valueText?: ReactNode; note?: ReactNode };

/** Labelled horizontal bars; each bar is its value out of its own maximum (or out of the largest value). */
export function Bars({ data }: { data: BarDatum[] }) {
  const top = Math.max(1, ...data.map((d) => d.value));
  return (
    <ul className={s.bars}>
      {data.map((d) => {
        const pct = (d.value / (d.max ?? top)) * 100;
        return (
          <li key={d.key} className={`${s.bar} ${hue(d.tone)}`} title={typeof d.note === "string" ? d.note : undefined}>
            <div className={s.barHead}>
              <span className={s.barLabel}>{d.label}</span>
              <span className={s.barValue}>{d.valueText ?? d.value.toLocaleString()}</span>
            </div>
            <div className={s.barTrack}>
              <div className={s.barFill} style={{ width: `${d.value > 0 ? Math.max(2, pct) : 0}%` }} />
            </div>
            {d.note && <span className={s.barNote}>{d.note}</span>}
          </li>
        );
      })}
    </ul>
  );
}

/** Parts of a whole in one bar, with a legend that names and counts each part. */
export function SplitBar({ parts, label }: { parts: { key: string; label: string; value: number; tone: Hue }[]; label: string }) {
  const total = parts.reduce((n, p) => n + p.value, 0);
  const shown = parts.filter((p) => p.value > 0);
  return (
    <div className={s.split}>
      <div className={s.splitTrack} role="img" aria-label={`${label}: ${parts.map((p) => `${p.label} ${p.value}`).join(", ")}`}>
        {total === 0 ? (
          <div className={`${s.splitPart} ${hue("neutral")}`} style={{ width: "100%", opacity: 0.35 }} />
        ) : (
          shown.map((p) => (
            <div key={p.key} className={`${s.splitPart} ${hue(p.tone)}`} style={{ width: `${(p.value / total) * 100}%` }} title={`${p.label}: ${p.value}`} />
          ))
        )}
      </div>
      <ul className={s.legend}>
        {parts.map((p) => (
          <li key={p.key} className={hue(p.tone)}>
            <span className={s.legendDot} />
            {p.label} <span className={s.legendValue}>{p.value.toLocaleString()}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

// ---- stepper ----

export type Step = { label: string; sub?: ReactNode; state: "done" | "active" | "failed" | "todo"; icon: IconName };

export function Stepper({ steps }: { steps: Step[] }) {
  return (
    <ol className={s.stepper}>
      {steps.map((st) => (
        <li
          key={st.label}
          className={`${s.stepItem} ${st.state === "done" ? s.stepDone : st.state === "active" ? s.stepActive : st.state === "failed" ? s.stepFailed : ""}`}
          aria-current={st.state === "active" ? "step" : undefined}
        >
          <span className={s.stepCircle}>
            <Icon name={st.state === "done" ? "check" : st.state === "failed" ? "x" : st.icon} size={16} />
          </span>
          <span className={s.stepLabel}>{st.label}</span>
          {st.sub && <span className={s.stepSub}>{st.sub}</span>}
        </li>
      ))}
    </ol>
  );
}

// ---- chips and pills ----

const KIND_ICON: Record<string, IconName> = {
  TYPE: "repo",
  METHOD: "code",
  FIELD: "list",
  TEST: "test",
  ENDPOINT: "route",
  FILE: "copy",
  PACKAGE: "repo",
};

/** A code symbol with its kind's colour and icon. */
export function KindChip({ kind, name, title }: { kind: string; name: string; title?: string }) {
  return (
    <span className={`${s.chip} ${hue(KIND_HUE[kind] ?? "neutral")}`} title={title ?? name}>
      <span className={s.chipIcon} aria-hidden>
        <Icon name={KIND_ICON[kind] ?? "code"} size={11} />
      </span>
      <span className={s.chipText}>{name}</span>
    </span>
  );
}

export function Pill({ tone, children }: { tone: Hue; children: ReactNode }) {
  return <span className={`${s.pill} ${hue(tone)}`}>{children}</span>;
}

/** How many steps away from the change: filled dots out of four. */
export function DistanceDots({ distance, tone = "orange" }: { distance: number; tone?: Hue }) {
  const n = Math.max(1, Math.min(4, distance));
  return (
    <span className={`${s.dots} ${hue(tone)}`} title={`${distance} step${distance === 1 ? "" : "s"} from the change`} aria-label={`${distance} steps from the change`}>
      {[1, 2, 3, 4].map((i) => (
        <span key={i} className={`${s.dot} ${i <= 5 - n ? s.dotOn : ""}`} />
      ))}
    </span>
  );
}

// ---- evidence chain ----

function kindOf(qualifiedName: string) {
  return qualifiedName.includes("#") ? "METHOD" : "TYPE";
}

/** An evidence chain as a vertical line of symbols joined by what connects them. */
export function ChainView({ chain }: { chain: Hop[] }) {
  if (!chain?.length) return null;
  const nodes = [chain[0].from, ...chain.map((h) => h.to)];
  return (
    <ol className={s.chain}>
      {nodes.map((name, i) => {
        const hop = chain[i];
        return (
          <li key={i}>
            <div className={`${s.chainNode} ${hue(KIND_HUE[kindOf(name)])}`}>
              <span className={s.chainRail}>
                <span className={s.chainDot} />
              </span>
              <KindChip kind={kindOf(name)} name={shortName(name)} title={name} />
            </div>
            {hop && (
              <div className={s.chainEdge}>
                <span className={`${s.chainLine} ${hop.resolution !== "RESOLVED" || hop.edgeType === "OVERRIDDEN_BY" ? s.chainLineGuess : ""}`} />
                <span className={s.chainVerb}>
                  {EDGE_WORD[hop.edgeType] ?? hop.edgeType.toLowerCase()}
                  {hop.resolution !== "RESOLVED" || hop.edgeType === "OVERRIDDEN_BY" ? " (best guess)" : ""} · {fileName(hop.path)}:{hop.line}
                </span>
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}

// ---- avatar ----

const AVATAR_HUES: Hue[] = ["blue", "teal", "violet", "pink", "green", "orange"];

/** A repository's initials on a colour picked from its name, so each repository keeps its colour. */
export function RepoAvatar({ name, size = 36 }: { name: string; size?: number }) {
  const short = name.slice(name.lastIndexOf("/") + 1) || name;
  const words = short.split(/[^A-Za-z0-9]+/).filter(Boolean);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : short.slice(0, 2)).toUpperCase();
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return (
    <span className={`${s.avatar} ${hue(AVATAR_HUES[h % AVATAR_HUES.length])}`} style={{ width: size, height: size, fontSize: size * 0.4 }} aria-hidden>
      {initials || "?"}
    </span>
  );
}

// ---- toast ----

const ToastContext = createContext<(message: string) => void>(() => undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<{ id: number; message: string }[]>([]);
  const show = useCallback((message: string) => {
    const id = Date.now() + Math.random();
    setItems((cur) => [...cur, { id, message }]);
    setTimeout(() => setItems((cur) => cur.filter((t) => t.id !== id)), 3200);
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className={s.toasts} role="status" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={s.toast}>
            <Icon name="check" />
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

// ---- illustrations ----

/** Small decorative scenes for empty states and the welcome hero. */
export function Illustration({ name, size = 120 }: { name: "map" | "analysis" | "repo" | "search"; size?: number }) {
  const common = { width: size, height: size * 0.75, viewBox: "0 0 160 120", className: s.illustration, "aria-hidden": true } as const;
  if (name === "map") {
    return (
      <svg {...common}>
        <path d="M40 40 L80 70 L120 35 M80 70 L60 98 M80 70 L112 92" stroke="var(--border)" strokeWidth="3" fill="none" />
        <circle cx="40" cy="40" r="14" fill="var(--blue-subtle)" stroke="var(--blue)" strokeWidth="3" />
        <circle cx="120" cy="35" r="12" fill="var(--teal-subtle)" stroke="var(--teal)" strokeWidth="3" />
        <circle cx="80" cy="70" r="16" fill="var(--green-subtle)" stroke="var(--green)" strokeWidth="3" />
        <circle cx="60" cy="98" r="10" fill="var(--violet-subtle)" stroke="var(--violet)" strokeWidth="3" />
        <circle cx="112" cy="92" r="10" fill="var(--pink-subtle)" stroke="var(--pink)" strokeWidth="3" />
      </svg>
    );
  }
  if (name === "analysis") {
    return (
      <svg {...common}>
        <path d="M24 92 A56 56 0 0 1 136 92" stroke="var(--inset)" strokeWidth="14" fill="none" />
        <path d="M24 92 A56 56 0 0 1 52 44" stroke="var(--green)" strokeWidth="14" fill="none" />
        <path d="M55 42 A56 56 0 0 1 80 36" stroke="var(--yellow)" strokeWidth="14" fill="none" />
        <path d="M83 36 A56 56 0 0 1 108 44" stroke="var(--orange)" strokeWidth="14" fill="none" />
        <path d="M111 46 A56 56 0 0 1 136 92" stroke="var(--red)" strokeWidth="14" fill="none" />
        <line x1="80" y1="92" x2="58" y2="52" stroke="var(--fg)" strokeWidth="4" strokeLinecap="round" />
        <circle cx="80" cy="92" r="7" fill="var(--fg)" />
      </svg>
    );
  }
  if (name === "search") {
    return (
      <svg {...common}>
        <circle cx="70" cy="56" r="30" fill="var(--blue-subtle)" stroke="var(--blue)" strokeWidth="5" />
        <line x1="92" y1="78" x2="122" y2="106" stroke="var(--blue)" strokeWidth="8" strokeLinecap="round" />
        <path d="M56 56 h28 M56 46 h20 M56 66 h16" stroke="var(--blue)" strokeWidth="4" strokeLinecap="round" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <rect x="30" y="22" width="100" height="80" rx="10" fill="var(--green-subtle)" stroke="var(--green)" strokeWidth="3" />
      <path d="M50 46 h60 M50 62 h44 M50 78 h52" stroke="var(--green)" strokeWidth="5" strokeLinecap="round" />
      <circle cx="122" cy="26" r="14" fill="var(--violet)" />
      <path d="M116 26 l4 4 l8 -8" stroke="#fff" strokeWidth="3" fill="none" strokeLinecap="round" />
    </svg>
  );
}
