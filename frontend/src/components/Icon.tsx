/** A small set of line icons drawn for this app (16px grid, currentColor). */
const PATHS: Record<string, string> = {
  logo: "M3 4h6M3 8h4M3 12h6M11 4l2 4-2 4",
  repo: "M4 2.5h7.5a1 1 0 0 1 1 1V13H5a1 1 0 0 1-1-1V2.5Zm0 9.5a1 1 0 0 1 1-1h7.5M6.5 5h3",
  commit: "M2 8h3.5m5 0H14M8 10.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
  map: "M3.5 4.5a1.5 1.5 0 1 0 0-.01M12.5 4.5a1.5 1.5 0 1 0 0-.01M8 12.5a1.5 1.5 0 1 0 0-.01M4.7 5.3 7 11M11.3 5.3 9 11M5 4h6",
  pulse: "M1.5 8h3l1.5-4 3 8 1.5-4h4",
  diff: "M5 2.5v7M2.5 6h5M11 6.5v7M8.5 13.5h5M8.5 3.5h5",
  code: "M5.5 4.5 2 8l3.5 3.5M10.5 4.5 14 8l-3.5 3.5",
  list: "M5.5 4h8M5.5 8h8M5.5 12h8M2.5 4h.01M2.5 8h.01M2.5 12h.01",
  check: "M3 8.5 6.5 12 13 4.5",
  x: "M4 4l8 8M12 4l-8 8",
  alert: "M8 2.5 14 13H2L8 2.5ZM8 6.5v3M8 11.2v.01",
  info: "M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12ZM8 7.2V11M8 5v.01",
  sun: "M8 10.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM8 1.5v1.5M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1 1M11.6 11.6l1 1M3.4 12.6l1-1M11.6 4.4l1-1",
  moon: "M13 9.5A5.5 5.5 0 0 1 6.5 3 5.5 5.5 0 1 0 13 9.5Z",
  monitor: "M2 3h12v8H2zM6 14h4M8 11v3",
  copy: "M5.5 5.5h7v8h-7zM3.5 10.5v-8h7",
  arrowRight: "M3 8h10M9 4l4 4-4 4",
  chevronDown: "M4 6l4 4 4-4",
  chevronRight: "M6 4l4 4-4 4",
  search: "M7 12A5 5 0 1 0 7 2a5 5 0 0 0 0 10ZM10.7 10.7 14 14",
  refresh: "M13 3v3.5H9.5M3 13V9.5h3.5M12.6 6.5A5 5 0 0 0 3.7 5M3.4 9.5a5 5 0 0 0 8.9 1.5",
  play: "M5 3.5v9l7-4.5-7-4.5Z",
  download: "M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10",
  link: "M6.5 9.5l3-3M5 7.5 3.5 9a2.1 2.1 0 0 0 3 3L8 10.5M11 8.5 12.5 7a2.1 2.1 0 0 0-3-3L8 5.5",
  plus: "M8 3v10M3 8h10",
  clock: "M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12ZM8 4.5V8l2.5 1.5",
  filter: "M2.5 3.5h11L9.5 8.5V13l-3-1.5V8.5l-4-5Z",
  test: "M6 2.5v4L2.8 12.2A1 1 0 0 0 3.7 13.7h8.6a1 1 0 0 0 .9-1.5L10 6.5v-4M5 2.5h6",
  route: "M3 13.5V9a3 3 0 0 1 3-3h7M10 3l3 3-3 3",
  question: "M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12ZM6.3 6.2a1.8 1.8 0 1 1 2.6 1.6c-.6.3-.9.8-.9 1.4v.3M8 11.3v.01",
};

export type IconName = keyof typeof PATHS;

export default function Icon({ name, size = 16, label }: { name: IconName; size?: number; label?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={label ? "img" : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{ flex: "none" }}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
