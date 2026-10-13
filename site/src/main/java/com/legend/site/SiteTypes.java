package com.legend.site;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE MEDIA TYPES OF legend-lite'S SITES (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C4): the files DataCube's site is made
 * of, by their extension -- one table, which every host that serves a site answers with: the warehouse's server
 * ({@code WarehouseServer}), and Python's engine ({@code legend_lite.engine}, through the native library's
 * {@code lite_site_types}). Anything else is served as bytes (RFC 9110 §8.3).
 */
public final class SiteTypes {
    private SiteTypes() {
    }

    /** Each extension (without its dot) and its media type, in this order. */
    public static final Map<String, String> BY_EXTENSION = byExtension();

    private static Map<String, String> byExtension() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("html", "text/html; charset=utf-8");
        out.put("js", "text/javascript; charset=utf-8");
        out.put("mjs", "text/javascript; charset=utf-8");
        out.put("css", "text/css; charset=utf-8");
        out.put("json", "application/json");
        out.put("wasm", "application/wasm");
        out.put("pure", "text/plain; charset=utf-8");
        out.put("svg", "image/svg+xml");
        out.put("woff2", "font/woff2");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The table as one JSON object, for a host in another language (Python's, through the native library). */
    public static String json() {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<String, String> e : BY_EXTENSION.entrySet()) {
            // extensions and media types are plain ASCII words: nothing in them needs a JSON escape
            out.append(out.length() > 1 ? "," : "").append('"').append(e.getKey()).append("\":\"")
                    .append(e.getValue()).append('"');
        }
        return out.append('}').toString();
    }
}
