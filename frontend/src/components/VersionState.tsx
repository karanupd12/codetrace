"use client";

import type { VersionState } from "@/lib/repo";
import { JOB_TITLE } from "@/lib/words";
import type { TimelineTask } from "./LiveTimeline";
import { Badge } from "./ui";

/** The tasks shown while a version is prepared: the scan, then the map (shown as "next" until it starts). */
export function prepareTasks(v: VersionState): TimelineTask[] {
  const tasks: TimelineTask[] = [];
  if (v.indexJob) tasks.push({ job: v.indexJob });
  else tasks.push({ upcoming: JOB_TITLE.INDEX });
  const graphJob = "graphJob" in v ? v.graphJob : undefined;
  if (graphJob) tasks.push({ job: graphJob });
  else tasks.push({ upcoming: JOB_TITLE.GRAPH_BUILD });
  return tasks;
}

export function VersionBadge({ v }: { v: VersionState }) {
  switch (v.state) {
    case "ready":
      return <Badge tone="green" dot>Ready</Badge>;
    case "preparing":
      return <Badge tone="yellow" dot>{v.step === "scan" ? "Scanning…" : "Mapping…"}</Badge>;
    case "failed":
      return <Badge tone="red" dot title={v.reason}>Failed</Badge>;
    default:
      return <Badge>Not prepared</Badge>;
  }
}
