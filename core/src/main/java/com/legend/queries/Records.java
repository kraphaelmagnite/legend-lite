package com.legend.queries;

import com.legend.json.Json;

import java.util.List;
import java.util.Map;

/**
 * Where a host keeps its saved queries' versions (docs/ONE_PLATFORM_PROGRAM_2026_10_12.md B5): the one thing that
 * differs between legend-lite's server, Python's native library and the page's module, which all answer with
 * {@link SavedQueries}' rules. Each query is every version of it, oldest first, the current one the version whose
 * {@code validUntil} is null.
 */
public interface Records {

    /** Every version of the query {@code id}, oldest first; empty when there is none. */
    List<Json.Obj> versions(String id);

    /** The query {@code id}'s versions, all of them, in place of what was kept. */
    void write(String id, List<Json.Obj> versions);

    /** Every query's versions, by its id, in the order of the ids (so every host's search ties alike). */
    Map<String, List<Json.Obj>> all();
}
