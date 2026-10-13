package com.legend.warehouse.sqlapi.page;

import com.legend.warehouse.sqlapi.SqlApiCalls;

/**
 * THE WAREHOUSE'S API IN THE PAGE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3): the one binding ({@link SqlApiCalls})
 * compiled to WebAssembly, so the tab's driver ({@code engine-client/src/warehouse.ts}) makes the very calls, and reads
 * the answers as, Python's (the native library's {@code lite_warehouse_call} and {@code lite_warehouse_read}) and the
 * JVM's. Nothing is kept between calls; nothing is fetched here (the driver does every request).
 */
public final class SqlApiPage {
    private SqlApiPage() {}

    /** The call {@code op} makes with {@code args}, JSON ({@link SqlApiCalls#call}). */
    @org.teavm.jso.JSExport
    public static String call(String op, String args) {
        return SqlApiCalls.call(op, args);
    }

    /** What the answer to {@code op}'s call says, JSON ({@link SqlApiCalls#read}). */
    @org.teavm.jso.JSExport
    public static String read(String op, int status, String body) {
        return SqlApiCalls.read(op, status, body);
    }
}
