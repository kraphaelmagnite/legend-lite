package com.legend.queries;

import com.legend.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * DataCube's saved pages held in memory, as the page hands its IndexedDB rows in (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md
 * B6): read from their JSON ({@code {"id": row, ...}}), and every page a call wrote or removed kept apart
 * ({@link #changed}: the row, or null for a page removed), which the page writes back.
 */
public final class MemoryCubeRecords implements SavedCubes.Records {

    private final Map<String, Json.Obj> held = new TreeMap<>();
    private final Map<String, Json.@com.legend.base.Nullable Obj> changed = new LinkedHashMap<>();

    /**
     * The rows as JSON: each page's row by its id. Their shape is checked, not cast (in the page's WebAssembly a failed
     * cast stops the module): a row that is not a page is left out, as every host leaves it ({@link SavedCubes#isRow}),
     * so the page's other pages still open and a page saved under its id replaces it.
     */
    public static MemoryCubeRecords of(String json) {
        MemoryCubeRecords out = new MemoryCubeRecords();
        // strip().isEmpty(), not isBlank(): TeaVM reads only a space as blank
        Json.Obj all = Json.parseObject(json.strip().isEmpty() ? "{}" : json);
        for (Map.Entry<String, Json.Node> e : all.fields().entrySet()) {
            if (e.getValue() instanceof Json.Obj row && SavedCubes.isRow(e.getKey(), row)) {
                out.held.put(e.getKey(), row);
            }
        }
        return out;
    }

    @Override
    public Map<String, Json.Obj> all() {
        return new TreeMap<>(held);
    }

    @Override
    public Json.@com.legend.base.Nullable Obj find(String id) {
        return held.get(id);
    }

    @Override
    public void put(Json.Obj row) {
        String id = ((Json.Str) row.get("id")).value();
        held.put(id, row);
        changed.put(id, row);
    }

    @Override
    public void remove(String id) {
        held.remove(id);
        changed.put(id, null);
    }

    /** Every page written (its row) or removed (null) since these rows were read, by its id: what the page writes back. */
    public Map<String, Json.@com.legend.base.Nullable Obj> changed() {
        return changed;
    }
}
