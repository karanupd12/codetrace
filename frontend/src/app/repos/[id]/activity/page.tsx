"use client";

import { useState } from "react";
import { isActive, type Job } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { duration, JOB_TITLE, jobSummary } from "@/lib/words";
import Icon from "@/components/Icon";
import LiveTimeline from "@/components/LiveTimeline";
import { Badge, Card, EmptyState, RelativeTime, Sha, ShowMore, type Tone } from "@/components/ui";
import s from "./activity.module.css";

const PAGE = 25;

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

function JobRow({ job, summaryOf }: { job: Job; summaryOf: (c: string) => string }) {
  const [open, setOpen] = useState(isActive(job.status));
  const st = state(job);
  const took = job.startedAt && job.finishedAt ? duration(Date.parse(job.finishedAt) - Date.parse(job.startedAt)) : null;
  return (
    <li className={s.item}>
      <button type="button" className={s.row} aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <Icon name="chevronRight" size={14} />
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
            {took && ` · took ${took}`}
            {job.attempts > 1 && ` · ${job.attempts} attempts`}
          </span>
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

  return (
    <Card
      title="Activity"
      subtitle="Everything CODETRACE has done for this repository, newest first. Open a task to see each of its steps."
      padded={data.jobs.length === 0}
    >
      {data.jobs.length === 0 ? (
        <EmptyState icon="list" title="Nothing yet">
          Tasks appear here as soon as they start.
        </EmptyState>
      ) : (
        <>
          <ul className={s.list}>
            {data.jobs.slice(0, shown).map((j) => (
              <JobRow key={j.id} job={j} summaryOf={summaryOf} />
            ))}
          </ul>
          <div className={s.more}>
            <ShowMore shown={Math.min(shown, data.jobs.length)} total={data.jobs.length} noun="tasks" onMore={() => setShown((n) => n + PAGE)} />
          </div>
        </>
      )}
    </Card>
  );
}
