// whatwg-url ships no types. It is the URL standard's reference implementation of URL and URLSearchParams, so the
// platform's own declarations are its types.
declare module 'whatwg-url' {
  export const URL: typeof globalThis.URL;
  export const URLSearchParams: typeof globalThis.URLSearchParams;
}
