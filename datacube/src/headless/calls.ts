// The calls an embedding host answers (host.ts says what for), and `crypto.subtle.digest` over them. A module that
// imports nothing beyond web.ts, so install-web.ts can install the digest before any module that needs it loads.

import { base64, fromBase64 } from './web.ts';

/** What the host answers. Bytes cross as base64: an engine and its host share strings, not memory. */
export interface HostCalls {
  /** One planner request (planner-protocol.ts's Request, as JSON), answered as its Response (JSON). */
  planner(request: string): Promise<string>;
  /** One SQL statement run by the host's database: its result as an Arrow IPC stream, base64. */
  query(sql: string): Promise<string>;
  /** A file the database can read by `name` (DuckDB-WASM's registerFileBuffer): its bytes, base64. */
  registerFile(name: string, bytes: string): Promise<void>;
  /** A digest (crypto.subtle.digest): the algorithm's name ('SHA-256', ...) and the data, base64; the digest, base64. */
  digest(algorithm: string, data: string): Promise<string>;
}

/** The host's calls, defined as `legendLiteHost` before the bundle loads; none is a bundle run where it does not belong. */
export function hostCalls(): HostCalls {
  const calls = (globalThis as { legendLiteHost?: HostCalls }).legendLiteHost;
  if (calls === undefined) {
    throw new Error("no host: this code runs inside legend-lite's Python host, which defines legendLiteHost first");
  }
  return calls;
}

/** `crypto.subtle.digest` by the host, where the engine has no crypto (cube-document.ts's content digest). */
export function installDigest(calls: HostCalls, g: Record<string, unknown> = globalThis as unknown as Record<string, unknown>): void {
  const crypto = (g['crypto'] ?? {}) as { subtle?: unknown };
  if (crypto.subtle !== undefined) return;
  const toBytes = (data: ArrayBuffer | ArrayBufferView): Uint8Array =>
    ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
  crypto.subtle = {
    async digest(algorithm: string | { name: string }, data: ArrayBuffer | ArrayBufferView): Promise<ArrayBuffer> {
      const name = typeof algorithm === 'string' ? algorithm : algorithm.name;
      const bytes = fromBase64(await calls.digest(name, base64(toBytes(data))));
      return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    },
  };
  if (g['crypto'] === undefined) {
    Object.defineProperty(g, 'crypto', { value: crypto, writable: true, configurable: true, enumerable: false });
  }
}
