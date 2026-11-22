"use client";

import { useEffect, useId, useState } from "react";
import { searchGraphNodes, type GraphNode } from "@/lib/api";
import { KIND_WORD, packageOf, shortName } from "@/lib/words";
import { controlClass } from "@/components/ui";
import s from "./explore.module.css";

/** A search box that picks one class, method or field of a map. */
export default function NodePicker({ snapshotId, label, value, onChange }: {
  snapshotId: string;
  label: string;
  value: GraphNode | null;
  onChange: (n: GraphNode | null) => void;
}) {
  const id = useId();
  const [q, setQ] = useState(value ? shortName(value.qualifiedName) : "");
  const [results, setResults] = useState<GraphNode[]>([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open || q.trim().length < 2) {
      setResults([]);
      return;
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      searchGraphNodes(snapshotId, { q: q.trim(), limit: 20, offset: 0 }, ctrl.signal)
        .then((p) => setResults(p.items.filter((n) => n.nodeType !== "FILE" && n.nodeType !== "PACKAGE")))
        .catch(() => undefined);
    }, 200);
    return () => {
      ctrl.abort();
      clearTimeout(t);
    };
  }, [q, open, snapshotId]);

  return (
    <div className={s.picker}>
      <label htmlFor={id} style={{ display: "block", fontWeight: 600, fontSize: 13, marginBottom: 6 }}>
        {label}
      </label>
      <input
        id={id}
        className={controlClass}
        placeholder="Type a class or method name"
        value={q}
        autoComplete="off"
        role="combobox"
        aria-expanded={open && results.length > 0}
        aria-controls={`${id}-list`}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => {
          setQ(e.target.value);
          onChange(null);
        }}
      />
      {open && results.length > 0 && (
        <ul id={`${id}-list`} role="listbox" className={`${s.pickerList} ${s.results}`}>
          {results.map((n) => (
            <li key={n.id}>
              <button
                type="button"
                role="option"
                aria-selected={value?.id === n.id}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => {
                  onChange(n);
                  setQ(shortName(n.qualifiedName));
                  setOpen(false);
                }}
              >
                <span className={s.resultName}>{shortName(n.qualifiedName)}</span>
                <span className={s.resultPkg}>
                  {KIND_WORD[n.nodeType]} · {packageOf(n.qualifiedName)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
