"use client";

import Link from "next/link";
import { useEffect, useState, type ButtonHTMLAttributes, type ReactNode } from "react";
import Icon, { type IconName } from "./Icon";
import s from "./ui.module.css";

export type Tone = "neutral" | "green" | "red" | "orange" | "yellow";

const cx = (...names: (string | false | undefined | null)[]) => names.filter(Boolean).join(" ");

// ---- buttons ----

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "default" | "primary" | "danger" | "invisible";
  size?: "sm" | "md" | "lg";
  icon?: IconName;
  loading?: boolean;
};

export function Button({ variant = "default", size = "md", icon, loading, children, className, disabled, ...rest }: ButtonProps) {
  return (
    <button
      type="button"
      className={cx(s.button, variant !== "default" && s[variant], size !== "md" && s[size], className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner size={14} /> : icon ? <Icon name={icon} /> : null}
      {children}
    </button>
  );
}

export function ButtonLink({ href, variant = "default", size = "md", icon, children }: {
  href: string;
  variant?: "default" | "primary" | "invisible";
  size?: "sm" | "md" | "lg";
  icon?: IconName;
  children: ReactNode;
}) {
  return (
    <Link href={href} className={cx(s.button, variant !== "default" && s[variant], size !== "md" && s[size])}>
      {icon && <Icon name={icon} />}
      {children}
    </Link>
  );
}

// ---- badges ----

export function Badge({ tone = "neutral", dot, children, title }: { tone?: Tone; dot?: boolean; children: ReactNode; title?: string }) {
  return (
    <span className={cx(s.badge, s[`badge-${tone}`])} title={title}>
      {dot && <span className={s.dot} aria-hidden />}
      {children}
    </span>
  );
}

// ---- cards ----

export function Card({ title, subtitle, actions, children, padded = true, headingLevel = 2 }: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  padded?: boolean;
  headingLevel?: 2 | 3;
}) {
  const H = headingLevel === 2 ? "h2" : "h3";
  return (
    <section className={s.card}>
      {(title || actions) && (
        <header className={s.cardHeader}>
          <div className={s.cardTitle}>
            {title && <H>{title}</H>}
            {subtitle && <span className={s.cardSubtitle}>{subtitle}</span>}
          </div>
          {actions}
        </header>
      )}
      <div className={padded ? s.cardBody : undefined}>{children}</div>
    </section>
  );
}

// ---- banners ----

export function Banner({ tone = "neutral", title, children, action }: {
  tone?: "neutral" | "red" | "yellow" | "green";
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  const icon: IconName = tone === "red" ? "alert" : tone === "yellow" ? "alert" : tone === "green" ? "check" : "info";
  return (
    <div className={cx(s.banner, tone !== "neutral" && s[`banner-${tone}`])} role={tone === "red" ? "alert" : "status"}>
      <Icon name={icon} />
      <div className={s.bannerBody}>
        {title && <strong>{title}</strong>}
        {children && <p>{children}</p>}
      </div>
      {action}
    </div>
  );
}

// ---- loading ----

export function Spinner({ size = 16, label }: { size?: number; label?: string }) {
  return (
    <span
      className={s.spinner}
      style={{ width: size, height: size }}
      role={label ? "status" : undefined}
      aria-label={label}
    />
  );
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className={s.skeletonStack} aria-hidden>
      {Array.from({ length: lines }, (_, i) => (
        <div key={i} className={s.skeleton} style={{ width: `${90 - ((i * 23) % 40)}%` }} />
      ))}
    </div>
  );
}

// ---- empty state ----

export function EmptyState({ icon = "info", title, children, action }: {
  icon?: IconName;
  title: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className={s.empty}>
      <span className={s.emptyIcon}>
        <Icon name={icon} size={20} />
      </span>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {action && <div className={s.emptyAction}>{action}</div>}
    </div>
  );
}

// ---- help ----

/** A small "?" that explains a term on hover or keyboard focus. */
export function Help({ children, label = "What does this mean?" }: { children: ReactNode; label?: string }) {
  return (
    <span className={s.help}>
      <button type="button" className={s.helpButton} aria-label={label}>
        <Icon name="question" size={14} />
      </button>
      <span role="tooltip" className={s.helpTip}>
        {children}
      </span>
    </span>
  );
}

// ---- disclosure ----

export function Details({ summary, children, defaultOpen }: { summary: ReactNode; children: ReactNode; defaultOpen?: boolean }) {
  return (
    <details className={s.details} open={defaultOpen}>
      <summary>
        <Icon name="chevronRight" size={14} />
        {summary}
      </summary>
      <div className={s.detailsBody}>{children}</div>
    </details>
  );
}

// ---- segmented control ----

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T;
  options: { value: T; label: ReactNode; icon?: IconName }[];
  onChange: (v: T) => void;
  label: string;
}) {
  return (
    <div className={s.segmented} role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" className={s.segment} aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.icon && <Icon name={o.icon} size={14} />}
          {o.label}
        </button>
      ))}
    </div>
  );
}

// ---- form ----

export function Field({ label, hint, children, htmlFor }: { label: ReactNode; hint?: ReactNode; children: ReactNode; htmlFor?: string }) {
  return (
    <div className={s.field}>
      <label className={s.fieldLabel} htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {hint && <span className={s.fieldHint}>{hint}</span>}
    </div>
  );
}

export const controlClass = s.control;
export const tableClass = s.table;
export const monoClass = s.mono;
export const mutedClass = s.muted;

// ---- commit hash ----

export function Sha({ value, copy = true }: { value: string; copy?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <span className={s.sha}>
      <span title={value}>{value.slice(0, 7)}</span>
      {copy && (
        <button
          type="button"
          className={s.shaCopy}
          aria-label={copied ? "Copied" : "Copy full commit hash"}
          title={copied ? "Copied" : "Copy full hash"}
          onClick={() => {
            navigator.clipboard?.writeText(value).then(
              () => {
                setCopied(true);
                setTimeout(() => setCopied(false), 1500);
              },
              () => undefined,
            );
          }}
        >
          <Icon name={copied ? "check" : "copy"} size={13} />
        </button>
      )}
    </span>
  );
}

// ---- time ----

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 31536000],
  ["month", 2592000],
  ["week", 604800],
  ["day", 86400],
  ["hour", 3600],
  ["minute", 60],
];

export function relativeTime(iso: string, now = Date.now()): string {
  const seconds = Math.round((Date.parse(iso) - now) / 1000);
  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  for (const [unit, size] of UNITS) {
    if (Math.abs(seconds) >= size) return rtf.format(Math.round(seconds / size), unit);
  }
  return Math.abs(seconds) < 10 ? "just now" : rtf.format(seconds, "second");
}

/** "3 minutes ago", refreshed every 30 s; the exact time is in the tooltip. */
export function RelativeTime({ iso }: { iso: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(t);
  }, []);
  return (
    <time dateTime={iso} title={new Date(iso).toLocaleString()}>
      {relativeTime(iso, now)}
    </time>
  );
}

// ---- risk ----

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export const RISK_TONE: Record<RiskLevel, Tone> = { LOW: "green", MEDIUM: "yellow", HIGH: "orange", CRITICAL: "red" };
export const riskWord = (l: RiskLevel) => l.charAt(0) + l.slice(1).toLowerCase();

export function RiskBadge({ level }: { level: RiskLevel }) {
  return (
    <Badge tone={RISK_TONE[level]} dot>
      {riskWord(level)} risk
    </Badge>
  );
}

export function RiskMeter({ score, level }: { score: number; level: RiskLevel }) {
  return (
    <div className={s.meter} role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={score} aria-label={`Risk score ${score} of 100`}>
      <div className={cx(s.meterFill, s[`tone-${RISK_TONE[level]}`])} style={{ width: `${Math.max(3, score)}%` }} />
    </div>
  );
}

// ---- paging ----

export function ShowMore({ shown, total, loading, onMore, noun = "items" }: {
  shown: number;
  total: number;
  loading?: boolean;
  onMore: () => void;
  noun?: string;
}) {
  if (total === 0) return null;
  return (
    <div className={s.more}>
      <span>
        Showing {shown.toLocaleString()} of {total.toLocaleString()} {noun}
      </span>
      {shown < total && (
        <Button size="sm" onClick={onMore} loading={loading}>
          Show more
        </Button>
      )}
    </div>
  );
}
