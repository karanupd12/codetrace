"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Loads data now and keeps re-loading every `intervalMs` while `shouldContinue(data)` is true.
 * Errors back off to 2x the interval. `reload()` forces a fresh load (and restarts polling).
 */
export function usePoll<T>(
  load: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  shouldContinue: (data: T) => boolean,
) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  const continueRef = useRef(shouldContinue);

  useEffect(() => {
    loadRef.current = load;
    continueRef.current = shouldContinue;
  });

  useEffect(() => {
    const ctrl = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const run = async () => {
      try {
        const next = await loadRef.current(ctrl.signal);
        if (ctrl.signal.aborted) return;
        setData(next);
        setError(null);
        if (continueRef.current(next)) timer = setTimeout(run, intervalMs);
      } catch (e) {
        if (ctrl.signal.aborted) return;
        setError(e instanceof Error ? e.message : "Request failed");
        timer = setTimeout(run, intervalMs * 2);
      }
    };
    void run();
    return () => {
      ctrl.abort();
      if (timer) clearTimeout(timer);
    };
  }, [intervalMs, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, reload };
}
