import * as http from "node:http";
import * as https from "node:https";

export interface AgentCatalogueEntry {
  id: string;
  name?: string;
  mcp?: string[];
  rag?: string[];
  peers?: string[];
}

export function catalogueEndpoint(value: string): URL {
  let endpoint: URL;
  try { endpoint = new URL(value); }
  catch { throw new Error("Enter a full HTTP(S) agent catalogue URL."); }
  if (!["http:", "https:"].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.hash) {
    throw new Error("Use an HTTP(S) catalogue URL without credentials or a fragment.");
  }
  return endpoint;
}

export function validateCatalogue(value: unknown): AgentCatalogueEntry[] {
  if (!Array.isArray(value)) throw new Error("The agent catalogue must return a JSON array.");
  const seen = new Set<string>();
  return value.map((raw: unknown) => {
    if (!raw || typeof raw !== "object") throw new Error("Invalid agent catalogue entry.");
    const entry = raw as Record<string, unknown>;
    if (typeof entry.id !== "string" || !entry.id.trim() || seen.has(entry.id)) {
      throw new Error("Agent catalogue entries need unique, nonempty ids.");
    }
    seen.add(entry.id);
    const projected: AgentCatalogueEntry = { id: entry.id };
    if (entry.name != null) {
      if (typeof entry.name !== "string") throw new Error("Invalid agent catalogue name.");
      projected.name = entry.name;
    }
    for (const field of ["mcp", "rag", "peers"] as const) {
      if (entry[field] == null) continue;
      const values = entry[field];
      if (!Array.isArray(values) || values.some(v => typeof v !== "string" || !v.trim())) {
        throw new Error("Invalid agent catalogue capabilities.");
      }
      projected[field] = [...new Set(values as string[])];
    }
    return projected;
  });
}

/** Called only by the extension host. No browser CORS, redirects, or webview-supplied URL. */
export async function fetchAgentCatalogue(
  value: string, authorization?: string,
  limits: { timeoutMs?: number; maxBytes?: number } = {}
): Promise<AgentCatalogueEntry[]> {
  const endpoint = catalogueEndpoint(value);
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(endpoint.hostname);
  if (authorization && endpoint.protocol !== "https:" && !loopback) {
    throw new Error("Catalogue authentication requires HTTPS, except on localhost.");
  }
  if (authorization && /[\r\n]/.test(authorization)) throw new Error("Invalid catalogue credential.");
  const timeoutMs = limits.timeoutMs ?? 10_000;
  const maxBytes = limits.maxBytes ?? 1_048_576;
  return new Promise((resolve, reject) => {
    const transport = endpoint.protocol === "https:" ? https : http;
    const headers: Record<string, string> = { Accept: "application/json" };
    if (authorization) headers.Authorization = authorization;
    const request = transport.request(endpoint, { method: "GET", headers }, response => {
      const status = response.statusCode ?? 0;
      if (status < 200 || status >= 300) {
        const message = status === 401 || status === 403
          ? "Catalogue access denied. Run EventConductor: Configure Agent Catalogue to set credentials."
          : status >= 300 && status < 400
            ? "Catalogue redirects are not followed. Configure the final endpoint URL."
            : `Catalogue unavailable (HTTP ${status}).`;
        response.destroy();
        clearTimeout(timer);
        reject(new Error(message));
        return;
      }
      const chunks: Buffer[] = [];
      let size = 0;
      response.on("data", (chunk: Buffer) => {
        size += chunk.length;
        if (size > maxBytes) {
          clearTimeout(timer);
          reject(new Error("Agent catalogue response is too large."));
          response.destroy();
          request.destroy();
          return;
        }
        chunks.push(chunk);
      });
      response.on("error", error => { clearTimeout(timer); reject(error); });
      response.on("end", () => {
        clearTimeout(timer);
        try { resolve(validateCatalogue(JSON.parse(Buffer.concat(chunks).toString("utf8")))); }
        catch (error) {
          reject(error instanceof SyntaxError ? new Error("The agent catalogue did not return valid JSON.") : error);
        }
      });
    });
    const timer = setTimeout(() => {
      reject(new Error("Agent catalogue request timed out."));
      request.destroy();
    }, timeoutMs);
    request.on("error", error => { clearTimeout(timer); reject(error); });
    request.end();
  });
}
