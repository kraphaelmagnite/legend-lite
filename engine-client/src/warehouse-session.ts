// A warehouse session's end, as its own error: what the warehouse's driver (warehouse.ts) raises when the warehouse
// refuses the token, and what a page's code reads to offer signing in again -- apart from the driver, so a page loads
// the driver only when it signs in to a warehouse (datacube/src/services/browser.ts).

/**
 * The warehouse refused the token: expired, or issued by a warehouse that has since restarted.
 * Its own type, so the cube can offer to sign in again right where it says so.
 */
export class SessionExpired extends Error {
  readonly baseUrl: string;
  readonly principal: string;
  constructor(baseUrl: string, principal: string) {
    super(`the warehouse session for ${principal} has expired — sign in again`);
    this.name = 'SessionExpired';
    this.baseUrl = baseUrl;
    this.principal = principal;
  }
}

/** Whether `error`, or anything in its cause chain, is a `SessionExpired`. */
export function sessionExpired(error: unknown): SessionExpired | null {
  for (let e = error, depth = 0; e && depth < 10; e = (e as { cause?: unknown }).cause, depth++) {
    if (e instanceof SessionExpired) return e;
  }
  return null;
}
