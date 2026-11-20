export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export type SystemInfo = {
  name: string;
  version: string;
  role: string;
  dbMigrationVersion: string | null;
  redis: "UP" | "DOWN";
};

export type HealthComponent = { status: string };
export type Health = {
  status: string;
  components?: Record<string, HealthComponent>;
};

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${API_BASE_URL}${path}`, {
    cache: "no-store",
    signal,
  });
  // Actuator returns 503 with a JSON body when DOWN; still parse it.
  if (!res.ok && res.status !== 503) {
    throw new Error(`HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

export const fetchSystemInfo = (signal?: AbortSignal) =>
  getJson<SystemInfo>("/api/v1/system/info", signal);

export const fetchHealth = (signal?: AbortSignal) =>
  getJson<Health>("/actuator/health", signal);
