"use client";

import { useEffect, useState } from "react";

type ConnectionState = "checking" | "connected" | "unavailable";

const messages: Record<ConnectionState, string> = {
  checking: "Checking connection...",
  connected: "Backend connected.",
  unavailable: "Backend unavailable.",
};

export default function ConnectionStatus() {
  const [state, setState] = useState<ConnectionState>("checking");

  useEffect(() => {
    const controller = new AbortController();

    async function checkConnection() {
      try {
        const response = await fetch("/api/health", {
          cache: "no-store",
          signal: controller.signal,
        });
        if (!response.ok) throw new Error("Unavailable");
        const body: unknown = await response.json();
        const healthy = typeof body === "object" && body !== null &&
          "status" in body && body.status === "UP";
        if (!controller.signal.aborted) {
          setState(healthy ? "connected" : "unavailable");
        }
      } catch {
        if (!controller.signal.aborted) setState("unavailable");
      }
    }

    void checkConnection();
    return () => controller.abort();
  }, []);

  return <p role="status">{messages[state]}</p>;
}
