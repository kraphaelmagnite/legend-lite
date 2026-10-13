package com.legend.queries;

import com.legend.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved queries held in memory, as the page hands its records in (its IndexedDB's, docs/ONE_PLATFORM_PROGRAM_2026_10_12.md
 * B5): read from their JSON ({@code {"id": [versions...], ...}}), and every query a call writes kept apart
 * ({@link #changed}), which the page writes back.
 */
public final class MemoryRecords implements Records {

    private final Map<String, List<Json.Obj>> held = new TreeMap<>();
    private final Map<String, List<Json.Obj>> changed = new LinkedHashMap<>();

    /**
     * The records as JSON: each query's versions by its id. Their shape is checked, not cast: in the page's
     * WebAssembly a failed cast stops the module, where records of another shape must be the 500 every host answers.
     */
    public static MemoryRecords of(String json) {
        MemoryRecords out = new MemoryRecords();
        // strip().isEmpty(), not isBlank(): TeaVM reads only a space as blank
        Json.Obj all = Json.parseObject(json.strip().isEmpty() ? "{}" : json);
        for (Map.Entry<String, Json.Node> e : all.fields().entrySet()) {
            if (!(e.getValue() instanceof Json.Arr held)) {
                throw new IllegalArgumentException("the records of query '" + e.getKey() + "' are not a list of versions");
            }
            List<Json.Obj> versions = new ArrayList<>();
            for (Json.Node n : held.items()) {
                if (!(n instanceof Json.Obj version)) {
                    throw new IllegalArgumentException("a version of query '" + e.getKey() + "' is not an object");
                }
                versions.add(version);
            }
            out.held.put(e.getKey(), versions);
        }
        return out;
    }

    @Override
    public List<Json.Obj> versions(String id) {
        return new ArrayList<>(held.getOrDefault(id, List.of()));
    }

    @Override
    public void write(String id, List<Json.Obj> versions) {
        held.put(id, new ArrayList<>(versions));
        changed.put(id, new ArrayList<>(versions));
    }

    @Override
    public Map<String, List<Json.Obj>> all() {
        Map<String, List<Json.Obj>> out = new LinkedHashMap<>();
        held.forEach((id, versions) -> out.put(id, new ArrayList<>(versions)));
        return out;
    }

    /** Every query written since these records were read, by its id: what the page writes back. */
    public Map<String, List<Json.Obj>> changed() {
        return changed;
    }
}
