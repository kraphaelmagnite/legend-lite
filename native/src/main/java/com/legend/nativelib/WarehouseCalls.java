package com.legend.nativelib;

import com.legend.warehouse.sqlapi.SqlApiCalls;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointer;
import planner.Folded;

/**
 * PYTHON'S WAREHOUSE (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3): the warehouse's SQL API as the one binding speaks it
 * ({@link SqlApiCalls}, the very calls and readings the page's module makes), for Python's driver
 * ({@code legend_lite/warehouse.py}), which only makes the HTTP calls these name. No I/O here; each answer is JSON,
 * {@code "OK\n<json>"}, a refusal included (the binding refuses as JSON and never throws).
 */
final class WarehouseCalls {
    private WarehouseCalls() {
    }

    /** The call {@code op} makes with {@code args} ({@link SqlApiCalls#call}). */
    @CEntryPoint(name = "lite_warehouse_call")
    static CCharPointer call(IsolateThread thread, CCharPointer op, CCharPointer args) {
        String a;
        try {
            String o = Compiler.text(op), g = Compiler.text(args);
            a = Folded.of(() -> SqlApiCalls.call(o, g));
        } catch (Throwable failure) {
            a = Folded.failure(failure);
        }
        return Compiler.answer(a);
    }

    /** What the answer ({@code status}, in digits; {@code body}) to {@code op}'s call says: SqlApiCalls.read. */
    @CEntryPoint(name = "lite_warehouse_read")
    static CCharPointer read(IsolateThread thread, CCharPointer op, CCharPointer status, CCharPointer body) {
        String a;
        try {
            String o = Compiler.text(op), s = Compiler.text(status), b = Compiler.text(body);
            a = Folded.of(() -> SqlApiCalls.read(o, Integer.parseInt(s), b));
        } catch (Throwable failure) {
            a = Folded.failure(failure);
        }
        return Compiler.answer(a);
    }
}
