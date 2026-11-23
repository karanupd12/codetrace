"use client";

import { useState } from "react";
import { isActive, type Job } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { duration, JOB_HUE, JOB_TITLE, jobSummary } from "@/lib/words";
import Icon, { type IconName } from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { Badge, Card, EmptyState, RelativeTime, Sha, ShowMore, type Tone } from "@/components/ui";
import { IconTile } from "@/components/viz";
import s from "./activity.module.css";

const PAGE = 25;

const JOB_ICON: Record<Job["operation"], IconName> = { SYNC: "refresh", INDEX: "code", GRAPH_BUILD: "map", IMPACT: "pulse" };

function state(j: Job): { label: string; tone: Tone } {
  switch (j.status) {
    case "QUEUED":
      return { label: "Waiting", tone: "yellow" };
    case "RUNNING":
      return { label: "Running", tone: "yellow" };
    case "SUCCEEDED":
      return { label: "Done", tone: "green" };
    default:
      return { label: "Failed", tone: "red" };
  }
}

const tookMs = (j: Job) => (j.startedAt && j.finishedAt ? Date.parse(j.finishedAt) - Date.parse(j.startedAt) : null);

/** "Today", "Yesterday" or the date, for grouping tasks by the day they started. */
function dayLabel(iso: string) {
  const d = new Date(iso);
  const today = new Date();
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((start(today) - start(d)) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  return d.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" });
}

function JobRow({ job, summaryOf, longest }: { job: Job; summaryOf: (c: string) => string; longest: number }) {
  const [open, setOpen] = useState(isActive(job.status));
  const st = state(job);
  const ms = tookMs(job);
  return (
    <li className={s.item}>
      <button type="button" className={s.row} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon name="chevronRight" size={14} />
        <IconTile icon={JOB_ICON[job.operation]} tone={JOB_HUE[job.operation]} size={30} />
        <span className={s.main}>
          <span className={s.title}>
            {JOB_TITLE[job.operation]}
            {job.commitHash && <span className={s.version}>{summaryOf(job.commitHash)}</span>}
          </span>
          <span className={s.meta}>
            {jobSummary(job)}
            {job.commitHash && (
              <>
                {" "}
                · <Sha value={job.commitHash} copy={false} />
              </>
            )}
            {" "}· <RelativeTime iso={job.createdAt} />
            {job.attempts > 1 && ` · ${job.attempts} attempts`}
          </span>
        </span>
        <span className={s.took} title={ms != null ? `Took ${duration(ms)}` : undefined}>
          {ms != null && (
            <>
              <span className={s.tookTrack} aria-hidden>
                <span className={`${s.tookFill} ${s[`fill-${JOB_HUE[job.operation]}`]}`} style={{ width: `${Math.max(4, (ms / longest) * 100)}%` }} />
              </span>
              <span className={s.tookText}>{duration(ms)}</span>
            </>
          )}
        </span>
        <Badge tone={st.tone} dot>
          {st.label}
        </Badge>
      </button>
      {open && (
        <div className={s.steps}>
          <LiveTimeline tasks={[{ job }]} />
        </div>
      )}
    </li>
  );
}

export default function Activity() {
  const { data } = useRepo();
  const [shown, setShown] = useState(PAGE);
  if (!data) return null;
  const summaryOf = (c: string) => data.revisions.find((r) => r.commitHash === c)?.summary.slice(0, 70) || c.slice(0, 7);
  const visible = data.jobs.slice(0, shown);
  const longest = Math.max(1, ...visible.map((j) => tookMs(j) ?? 0));
  const groups: { day: string; jobs: Job[] }[] = [];
  for (const j of visible) {
    const day = dayLabel(j.createdAt);
    if (groups.at(-1)?.day === day) groups.at(-1)!.jobs.push(j);
    else groups.push({ day, jobs: [j] });
  }

  return (
    <Card
      title={
        <span className={s.cardTitle}>
          <IconTile icon="list" tone="pink" size={26} />
          Activity
        </span>
      }
      subtitle="Everything CODETRACE has done for this repository, newest first. Open a task to see each of its steps."
      padded={data.jobs.length === 0}
    >
      {data.jobs.length === 0 ? (
        <EmptyState icon="list" title="Nothing yet">
          Tasks appear here as soon as they start.
        </EmptyState>
      ) : (
        <>
          {groups.map((g) => (
            <section key={g.day} className={s.group}>
              <h3 className={s.day}>{g.day}</h3>
              <ul className={s.list}>
                {g.jobs.map((j) => (
                  <JobRow key={j.id} job={j} summaryOf={summaryOf} longest={longest} />
                ))}
              </ul>
            </section>
          ))}
          <div className={s.more}>
            <ShowMore shown={Math.min(shown, data.jobs.length)} total={data.jobs.length} noun="tasks" onMore={() => setShown((n) => n + PAGE)} />
          </div>
        </>
      )}
    </Card>
  );
}
