// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legend.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model's writer against legend-engine 4.145.0 itself (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15.6a): the files
 * one request writes -- two classes with their sample rows, their association and the data space -- are pinned under
 * {@code upstream-api/model-writer/}, with the engine's own answers to them: it compiled the project, and planned each
 * class's query and the association walked both ways. When the writer's files move, this fails; the pin and the
 * engine's answers are made again together
 * ({@code docs/datacube-model-homework-2026-10-11/record-upstream.sh}, against a legend-engine 4.145.0 server).
 */
@DisplayName("ModelChanges' files, as legend-engine 4.145.0 compiles and plans them")
class ModelWriterUpstreamTest {

    private static final String DIR = "upstream-api/model-writer/";

    /** Two grids' sources, each as the tab's table model writes it, each its own `local::DB`. */
    private static final String TRADES = """
            ###Relational
            Database local::DB ( Table trades ( trade_id INTEGER NOT NULL, customer_id INTEGER, region VARCHAR(8), \
            notional DOUBLE ) )
            ###Connection
            RelationalDatabaseConnection local::Conn { type: DuckDB; specification: DuckDB { }; auth: Test; }
            ###Runtime
            Runtime local::RT { mappings: []; connections: [ local::DB: [ c1: local::Conn ] ]; }
            """;
    private static final String CUSTOMERS = TRADES.replace(
            "Table trades ( trade_id INTEGER NOT NULL, customer_id INTEGER, region VARCHAR(8), notional DOUBLE )",
            "Table customers ( id INTEGER NOT NULL, name VARCHAR(20) )");

    private static String cls(String source, String table, String name, String columns, String samples) {
        return """
                {"source": {"_type": "text", "code": %s}, "lambda": %s, "name": "%s", "columns": %s,
                 "where": {"from": "local::DB", "runtime": "local::RT", "as": "Browser", "samples": {"%s": %s}}}"""
                .formatted(Json.toCompact(source), com.legend.parser.SpecParser.lambdaJson("|#>{local::DB." + table + "}#",
                        false), name, columns, table, samples);
    }

    /** The whole page in one go, as Create a Data Space… asks it. */
    private static final String REQUEST = """
            {"project": {"_type": "text", "code": ""}, "package": "trades",
             "classes": [%s, %s],
             "associations": [{"ends": [{"class": "trades::domain::Customer", "on": "id", "unique": true},
                                        {"class": "trades::domain::Trade", "on": "customerId", "unique": false}]}],
             "dataSpace": {"name": "Trades", "title": "Trades", "description": "The desk's trades"}}"""
            .formatted(
                    cls(TRADES, "trades", "Trade", "{\"customer_id\": {\"as\": \"key\"}}", """
                            {"columns": ["trade_id", "customer_id", "region", "notional"],
                             "rows": [["1", "10", "EMEA", "150.5"], ["2", null, "APAC", "20"], ["3", "11", "AMER", "300"]]}"""),
                    cls(CUSTOMERS, "customers", "Customer", "{}", """
                            {"columns": ["id", "name"], "rows": [["10", "Ann"], ["11", "Bo, Jr."]]}"""));

    /** The project the answer's files make, in the answer's order: the text the engine was given. */
    private static String project(Map<String, Object> answer) {
        StringBuilder out = new StringBuilder();
        for (Object c : (List<?>) answer.get("changes")) {
            String code = (String) ((Map<?, ?>) c).get("pureCode");
            out.append(out.length() == 0 ? "" : "\n").append(code.startsWith("###") ? "" : "###Pure\n").append(code);
        }
        return out.toString();
    }

    @Test
    @DisplayName("the files one request writes are the ones the engine compiled, and it planned every class and walk")
    void theEngineCompiledAndPlannedThem() {
        Map<String, Object> answer = ModelChanges.createDataSpace(Json.parseObject(REQUEST));
        assertEquals(resource(DIR + "one-go.pure"), project(answer),
                "the writer's files moved: pin them again, then record the engine's answers "
                        + "(docs/datacube-model-homework-2026-10-11/record-upstream.sh)");

        // the queries the engine planned: each class's own, as the answer gives it, and the association both ways
        List<String> queries = new ArrayList<>();
        for (Object c : (List<?>) answer.get("classes")) {
            Map<?, ?> made = (Map<?, ?>) c;
            queries.add("|" + made.get("query") + "->from(" + made.get("mapping") + ", " + made.get("runtime") + ")");
        }
        queries.add("|trades::domain::Trade.all()->project(~[name: x|$x.customer.name])"
                + "->from(trades::mapping::Model, trades::runtime::Browser)");
        queries.add("|trades::domain::Customer.all()->project(~[notional: x|$x.trades.notional])"
                + "->from(trades::mapping::Model, trades::runtime::Browser)");
        assertEquals(String.join("\n", queries) + "\n", resource(DIR + "queries.pure"), "the queries the engine was asked");

        Json.Obj engine = Json.parseObject(resource(DIR + "engine-answers.json"));
        assertEquals("4.145.0", engine.getString("engine"));
        // the answers are to these files: recorded with each one's digest, so a pin made again without them fails here
        for (String pinned : List.of("one-go.pure", "queries.pure")) {
            assertEquals(engine.getObj("pinned").getString(pinned), sha256(resource(DIR + pinned)),
                    pinned + " is not the file the engine answered: record its answers again");
        }
        Json.Obj compiled = engine.getObj("compile");
        assertEquals(200, compiled.getInt("status"), compiled.toString());
        Map<String, Integer> planned = new LinkedHashMap<>();
        for (Json.Node p : engine.getArr("plans").items()) {
            Json.Obj plan = (Json.Obj) p;
            planned.put(plan.getString("query"), plan.getInt("status"));
            assertTrue(plan.getString("sql").startsWith("select "), plan.toString());
        }
        Map<String, Integer> all200 = new LinkedHashMap<>();
        queries.forEach(q -> all200.put(q, 200));
        assertEquals(all200, planned, "the engine planned each query");
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = ModelWriterUpstreamTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
