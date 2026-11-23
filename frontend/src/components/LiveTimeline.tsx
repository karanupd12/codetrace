"use client";

import { useEffect, useRef, useState } from "react";
import { getJob, isActive, listJobEvents, type Job, type JobEvent } from "@/lib/api";
import { duration, JOB_HUE, JOB_TITLE, type Hue } from "@/lib/words";
import Icon from "./Icon";
import { Button, Spinner } from "./ui";
import s from "./LiveTimeline.module.css";

/** A task in the timeline: a running or finished job, or one that will start next. */
export type TimelineTask = { job: Job } | { upcoming: string };

type Row = {
  key: string;
  text: string;
  state: "done" | "active" | "failed" | "waiting" | "upcoming";
  at?: number;
  until?: number;
  hue?: Hue;
};

const FAILED = (j: Job) => j.status === "FAILED" || j.status === "DEAD_LETTERED";

/**
 * A live, plain-language feed of what background tasks are doing. New steps fade in; the newest step of a running
 * task shows a spinner; finished steps show how long they took. The feed follows the newest step unless the reader has
 * scrolled up.
 */
export default function LiveTimeline({ tasks, onRetry, compact }: {
  tasks: TimelineTask[];
  onRetry?: () => void;
  compact?: boolean;
}) {
  const [events, setEvents] = useState<Record<string, JobEvent[]>>({});
  const [jobs, setJobs] = useState<Record<string, Job>>({});
  const listRef = useRef<HTMLOListElement>(null);
  const [following, setFollowing] = useState(true);
  const jobIds = tasks.flatMap((t) => ("job" in t ? [t.job.id] : []));
  const idsKey = jobIds.join(",");

  // keep the newest job state we were given
  useEffect(() => {
    setJobs((cur) => {
      const next = { ...cur };
      for (const t of tasks) if ("job" in t) next[t.job.id] = t.job;
      return next;
    });
  }, [tasks]);

  // poll each job's steps while it runs, plus once after it finishes
  useEffect(() => {
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const seen: Record<string, number> = {};
    const finishedPolled = new Set<string>();
    const tick = async () => {
      let anyRunning = false;
      for (const id of idsKey ? idsKey.split(",") : []) {
        if (finishedPolled.has(id)) continue;
        try {
          const job = await getJob(id, ctrl.signal);
          const fresh = await listJobEvents(id, seen[id] ?? 0, ctrl.signal);
          if (ctrl.signal.aborted) return;
          if (fresh.length) {
            seen[id] = fresh[fresh.length - 1].seq;
            setEvents((cur) => ({ ...cur, [id]: [...(cur[id] ?? []), ...fresh.filter((e) => e.seq > ((cur[id] ?? []).at(-1)?.seq ?? 0))] }));
          }
          setJobs((cur) => ({ ...cur, [id]: job }));
          if (isActive(job.status)) anyRunning = true;
          else finishedPolled.add(id);
        } catch {
          if (ctrl.signal.aborted) return;
          anyRunning = true; // try again shortly
        }
      }
      if (anyRunning) timer = setTimeout(tick, 1000);
    };
    void tick();
    return () => {
      ctrl.abort();
      if (timer) clearTimeout(timer);
    };
  }, [idsKey]);

  const rows: Row[] = [];
  for (const t of tasks) {
    if ("upcoming" in t) {
      rows.push({ key: `up:${t.upcoming}`, text: t.upcoming, state: "upcoming" });
      continue;
    }
    const job = jobs[t.job.id] ?? t.job;
    const list = events[job.id] ?? [];
    const hue = JOB_HUE[job.operation];
    if (list.length === 0) {
      if (job.status === "QUEUED") rows.push({ key: `${job.id}:wait`, text: `${JOB_TITLE[job.operation]}: waiting to start`, state: "waiting", hue });
      else if (isActive(job.status)) rows.push({ key: `${job.id}:start`, text: `${JOB_TITLE[job.operation]}…`, state: "active", hue });
      else if (FAILED(job)) rows.push({ key: `${job.id}:fail`, text: job.errorMessage ?? "Something went wrong.", state: "failed" });
      else rows.push({ key: `${job.id}:done`, text: JOB_TITLE[job.operation], state: "done", hue });
      continue;
    }
    list.forEach((e, i) => {
      const last = i === list.length - 1;
      const at = Date.parse(e.at);
      const next = list[i + 1] ? Date.parse(list[i + 1].at) : job.finishedAt ? Date.parse(job.finishedAt) : undefined;
      let state: Row["state"] = "done";
      if (e.step === "FAILED") state = "failed";
      else if (last && isActive(job.status)) state = e.step === "RETRYING" ? "waiting" : "active";
      rows.push({ key: `${job.id}:${e.seq}`, text: e.message, state, at, until: state === "done" ? next : undefined, hue });
    });
    if (FAILED(job) && list.at(-1)?.step !== "FAILED") {
      rows.push({ key: `${job.id}:fail`, text: job.errorMessage ?? "Something went wrong.", state: "failed" });
    }
  }

  // follow the newest step unless the reader scrolled away
  useEffect(() => {
    const el = listRef.current;
    if (el && following) el.scrollTop = el.scrollHeight;
  }, [rows.length, following]);

  const failed = rows.some((r) => r.state === "failed");

  return (
    <div className={`${s.timeline} ${compact ? s.compact : ""}`}>
      <ol
        ref={listRef}
        className={s.list}
        aria-live="polite"
        aria-label="Progress"
        onScroll={(e) => {
          const el = e.currentTarget;
          setFollowing(el.scrollHeight - el.scrollTop - el.clientHeight < 24);
        }}
      >
        {rows.map((r) => (
          <li key={r.key} className={`${s.row} ${s[r.state]} ${r.hue ? s[`hue-${r.hue}`] : ""}`}>
            <span className={s.marker} aria-hidden>
              {r.state === "active" ? (
                <Spinner size={13} />
              ) : r.state === "done" ? (
                <Icon name="check" size={13} />
              ) : r.state === "failed" ? (
                <Icon name="x" size={13} />
              ) : r.state === "waiting" ? (
                <Icon name="clock" size={13} />
              ) : (
                <span className={s.hollow} />
              )}
            </span>
            <span className={s.text}>
              {r.text}
              {r.state === "active" && !r.text.endsWith("…") ? "…" : ""}
              {r.state === "active" && <span className={s.shimmer} aria-hidden />}
            </span>
            {r.state === "done" && r.at && r.until ? <span className={s.time}>{duration(r.until - r.at)}</span> : null}
          </li>
        ))}
      </ol>
      {!following && (
        <button type="button" className={s.jump} onClick={() => setFollowing(true)}>
          <Icon name="chevronDown" size={13} /> Latest
        </button>
      )}
      {failed && onRetry && (
        <div className={s.retry}>
          <Button size="sm" icon="refresh" onClick={onRetry}>
            Try again
          </Button>
        </div>
      )}
    </div>
  );
}
