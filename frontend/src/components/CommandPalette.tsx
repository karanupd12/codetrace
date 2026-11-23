"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { searchGraphNodes, type GraphNode } from "@/lib/api";
import { useRepo } from "@/lib/repo";
import { KIND_HUE, KIND_WORD, packageOf, plural, shortName, type Hue } from "@/lib/words";
import Icon, { type IconName } from "./Icon";
import { IconTile } from "./viz";
import s from "./CommandPalette.module.css";

type Item = { key: string; group: string; label: string; hint?: string; icon: IconName; tone: Hue; href: string };

const TAB_ITEMS: { path: string; label: string; icon: IconName; tone: Hue }[] = [
  { path: "", label: "Overview", icon: "repo", tone: "blue" },
  { path: "/analyze", label: "Analyze a change", icon: "pulse", tone: "orange" },
  { path: "/explore", label: "Explore the dependency map", icon: "map", tone: "teal" },
  { path: "/explore?view=path", label: "How is A connected to B?", icon: "route", tone: "teal" },
  { path: "/explore?view=compare", label: "Compare versions", icon: "diff", tone: "teal" },
  { path: "/code", label: "Code", icon: "code", tone: "violet" },
  { path: "/activity", label: "Activity", icon: "list", tone: "pink" },
];

/** A quick-jump box (Ctrl+K or ⌘K): pages, versions, analyses and classes of the newest prepared version. */
export default function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { data } = useRepo();
  const router = useRouter();
  const [q, setQ] = useState("");
  const [active, setActive] = useState(0);
  const [nodes, setNodes] = useState<GraphNode[]>([]);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);

  const base = data ? `/repos/${data.repo.id}` : "";
  const snapshot = useMemo(() => {
    for (const r of data?.revisions ?? []) {
      const sn = data?.snapshots.find((x) => x.commitHash === r.commitHash && x.status === "PUBLISHED");
      if (sn) return sn;
    }
    return undefined;
  }, [data]);

  useEffect(() => {
    if (open) {
      setQ("");
      setActive(0);
      setNodes([]);
    }
  }, [open]);

  // classes matching the query, from the newest prepared version
  useEffect(() => {
    if (!open || !snapshot || q.trim().length < 2) {
      setNodes([]);
      return;
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchGraphNodes(snapshot.id, { type: "TYPE", q: q.trim(), limit: 6, offset: 0 }, ctrl.signal)
        .then((p) => setNodes(p.items))
        .catch(() => undefined);
    }, 180);
    return () => {
      clearTimeout(t);
      ctrl.abort();
    };
  }, [open, q, snapshot]);

  const items = useMemo<Item[]>(() => {
    if (!data) return [];
    const needle = q.trim().toLowerCase();
    const match = (text: string) => !needle || text.toLowerCase().includes(needle);
    const pages: Item[] = TAB_ITEMS.filter((t) => match(t.label)).map((t) => ({
      key: `page:${t.path}`,
      group: "Go to",
      label: t.label,
      icon: t.icon,
      tone: t.tone,
      href: base + t.path,
    }));
    const summaryOf = (c: string) => data.revisions.find((r) => r.commitHash === c)?.summary || c.slice(0, 7);
    const analyses: Item[] = data.reports
      .filter((r) => r.status === "COMPLETED")
      .filter((r) => match(`${summaryOf(r.baseCommit)} ${summaryOf(r.headCommit)} ${r.baseCommit} ${r.headCommit}`))
      .slice(0, 5)
      .map((r) => ({
        key: `report:${r.id}`,
        group: "Analyses",
        label: `${summaryOf(r.headCommit)}`,
        hint: `compared with ${r.baseCommit.slice(0, 7)} · ${r.riskLevel ? `${r.riskLevel.toLowerCase()} risk · ` : ""}${plural(r.affectedCount ?? 0, "place")} affected`,
        icon: "pulse",
        tone: "orange",
        href: `${base}/analyze/${r.id}`,
      }));
    const versions: Item[] = data.revisions
      .filter((r) => match(`${r.summary} ${r.commitHash}`))
      .slice(0, needle ? 6 : 4)
      .map((r) => {
        const ready = data.snapshots.find((sn) => sn.commitHash === r.commitHash && sn.status === "PUBLISHED");
        return {
          key: `rev:${r.commitHash}`,
          group: "Versions",
          label: r.summary || r.commitHash.slice(0, 7),
          hint: `${r.commitHash.slice(0, 7)} · ${ready ? "open its map" : "not prepared"}`,
          icon: "commit",
          tone: ready ? "green" : "neutral",
          href: ready ? `${base}/explore?map=${ready.id}` : base,
        };
      });
    const classes: Item[] = snapshot
      ? nodes.map((n) => ({
          key: `node:${n.id}`,
          group: "Classes",
          label: shortName(n.qualifiedName),
          hint: `${KIND_WORD[n.nodeType]} · ${packageOf(n.qualifiedName)}`,
          icon: "code",
          tone: KIND_HUE[n.nodeType] ?? "blue",
          href: `${base}/explore?map=${snapshot.id}&focus=${n.id}`,
        }))
      : [];
    return [...pages, ...classes, ...analyses, ...versions];
  }, [data, q, nodes, snapshot, base]);

  useEffect(() => setActive(0), [q]);
  useEffect(() => {
    list.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }, [active]);

  if (!open) return null;

  const go = (item: Item | undefined) => {
    if (!item) return;
    onClose();
    router.push(item.href);
  };

  const onKey = (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActive((a) => Math.min(items.length - 1, a + 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go(items[active]);
    } else if (e.key === "Escape") {
      onClose();
    }
  };

  let lastGroup = "";
  return (
    <div className={s.backdrop} onMouseDown={onClose}>
      <div className={s.box} role="dialog" aria-modal="true" aria-label="Jump to" onMouseDown={(e) => e.stopPropagation()} onKeyDown={onKey}>
        <div className={s.inputRow}>
          <Icon name="search" />
          <input
            ref={input}
            autoFocus
            className={s.input}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={snapshot ? "Jump to a page, version, analysis or class…" : "Jump to a page, version or analysis…"}
            aria-label="Search"
            role="combobox"
            aria-expanded="true"
            aria-controls="palette-list"
            aria-activedescendant={items[active] ? `palette-${active}` : undefined}
          />
          <kbd className={s.kbd}>Esc</kbd>
        </div>
        <ul ref={list} id="palette-list" className={s.list} role="listbox">
          {items.length === 0 && <li className={s.empty}>Nothing matches “{q}”.</li>}
          {items.map((item, i) => {
            const header = item.group !== lastGroup ? item.group : null;
            lastGroup = item.group;
            return (
              <li key={item.key} role="presentation">
                {header && <div className={s.group}>{header}</div>}
                <div
                  id={`palette-${i}`}
                  data-index={i}
                  role="option"
                  aria-selected={i === active}
                  className={s.item}
                  onMouseMove={() => setActive(i)}
                  onClick={() => go(item)}
                >
                  <IconTile icon={item.icon} tone={item.tone} size={28} />
                  <span className={s.itemText}>
                    <span className={s.itemLabel}>{item.label}</span>
                    {item.hint && <span className={s.itemHint}>{item.hint}</span>}
                  </span>
                  {i === active && <Icon name="arrowRight" size={14} />}
                </div>
              </li>
            );
          })}
        </ul>
        <div className={s.footer}>
          <span>
            <kbd className={s.kbd}>↑</kbd> <kbd className={s.kbd}>↓</kbd> to move
          </span>
          <span>
            <kbd className={s.kbd}>Enter</kbd> to open
          </span>
        </div>
      </div>
    </div>
  );
}
