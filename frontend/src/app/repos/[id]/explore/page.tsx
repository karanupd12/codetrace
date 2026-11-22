"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useMemo } from "react";
import type { Snapshot } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { plural } from "@/lib/words";
import { ButtonLink, Card, EmptyState, Field, Segmented, controlClass } from "@/components/ui";
import CompareView from "./CompareView";
import MapView from "./MapView";
import PathView from "./PathView";
import s from "./explore.module.css";

type View = "map" | "path" | "compare";

function Explore() {
  const { data } = useRepo();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();

  const ready = useMemo(() => {
    // one map per version: the newest published one
    const seen = new Set<string>();
    const out: Snapshot[] = [];
    for (const sn of data?.snapshots ?? []) {
      if (sn.status === "PUBLISHED" && !seen.has(sn.commitHash)) {
        seen.add(sn.commitHash);
        out.push(sn);
      }
    }
    // newest version first, in commit order
    const order = new Map((data?.revisions ?? []).map((r, i) => [r.commitHash, i]));
    return out.sort((a, b) => (order.get(a.commitHash) ?? 1e9) - (order.get(b.commitHash) ?? 1e9));
  }, [data]);

  const set = useCallback(
    (changes: Record<string, string | null>) => {
      const next = new URLSearchParams(params.toString());
      for (const [k, v] of Object.entries(changes)) {
        if (v === null) next.delete(k);
        else next.set(k, v);
      }
      router.replace(`${pathname}?${next}`, { scroll: false });
    },
    [params, pathname, router],
  );

  if (!data) return null;
  if (ready.length === 0) {
    return (
      <Card>
        <EmptyState
          icon="map"
          title="No version is prepared yet"
          action={
            <ButtonLink href={`/repos/${data.repo.id}`} variant="primary">
              Prepare a version
            </ButtonLink>
          }
        >
          The dependency map shows how classes and methods connect. Prepare a version on the Overview tab to explore it.
        </EmptyState>
      </Card>
    );
  }

  const view = (params.get("view") as View) || "map";
  const map = ready.find((sn) => sn.id === params.get("map")) ?? ready[0];
  const focus = params.get("focus");
  const summaryOf = (sn: Snapshot) => {
    const rev = data.revisions.find((r) => r.commitHash === sn.commitHash);
    return `${(rev?.summary ?? "").slice(0, 60) || sn.commitHash.slice(0, 7)} · ${sn.commitHash.slice(0, 7)}`;
  };

  return (
    <>
      <div className={s.toolbar}>
        <div className={s.toolbarLeft}>
          {view !== "compare" && (
            <div className={s.mapPicker}>
              <Field label="Version" htmlFor="map-version">
                <select id="map-version" className={controlClass} value={map.id} onChange={(e) => set({ map: e.target.value, focus: null })}>
                  {ready.map((sn) => (
                    <option key={sn.id} value={sn.id}>
                      {summaryOf(sn)}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
          )}
          <Segmented<View>
            label="View"
            value={view}
            onChange={(v) => set({ view: v === "map" ? null : v })}
            options={[
              { value: "map", label: "Dependency map", icon: "map" },
              { value: "path", label: "How is A connected to B?", icon: "route" },
              { value: "compare", label: "Compare versions", icon: "diff" },
            ]}
          />
        </div>
        {view !== "compare" && (
          <span style={{ color: "var(--muted)", fontSize: 13 }}>
            {plural(map.nodeCount ?? 0, "item")} · {plural(map.edgeCount ?? 0, "link")}
          </span>
        )}
      </div>
      {view === "map" && <MapView key={map.id} snapshot={map} focus={focus} onFocus={(id) => set({ focus: id })} />}
      {view === "path" && <PathView key={map.id} snapshot={map} onShow={(id) => set({ view: null, focus: id })} />}
      {view === "compare" && <CompareView ready={ready} label={summaryOf} />}
    </>
  );
}

export default function ExplorePage() {
  return (
    <Suspense>
      <Explore />
    </Suspense>
  );
}
