// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.warehouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legend.json.Json;
import com.legend.testing.Runfile;
import com.legend.warehouse.sqlapi.SqlApiCalls;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The binding as every driver calls it (SqlApiCalls; docs/ONE_PLATFORM_PROGRAM_2026_10_12.md C3), held to the shared
 * corpus: each recorded exchange's call is the one the binding names, and its reading of the recorded answer the one
 * recorded. The tab's driver and Python's replay the same corpus.
 */
class SqlApiCallsTest {

    private static Json.Obj corpus() throws Exception {
        return Json.parseObject(Files.readString(Runfile.property("warehouse.sqlapi.corpus"), StandardCharsets.UTF_8));
    }

    @Test
    void everyRecordedCallAndReadingIsTheBindings() throws Exception {
        int exchanges = 0;
        for (Json.Node s : corpus().getArr("scenarios").items()) {
            Json.Obj scenario = (Json.Obj) s;
            for (Json.Node e : scenario.getArr("exchanges").items()) {
                Json.Obj exchange = (Json.Obj) e;
                String op = exchange.getString("op");
                String where = scenario.getString("name") + ", " + op;
                assertEquals(Json.toCompact(exchange.get("call")),
                        Json.toCompact(Json.parse(SqlApiCalls.call(op, Json.toCompact(exchange.get("args"))))), where);
                Json.Obj answer = exchange.getObj("answer");
                // an Arrow chunk's body is the driver's: the binding reads only its status
                String body = answer.has("bodyBase64") ? "" : answer.getString("body");
                assertEquals(Json.toCompact(exchange.get("read")),
                        Json.toCompact(Json.parse(SqlApiCalls.read(op, answer.getInt("status"), body))), where);
                exchanges++;
            }
        }
        assertTrue(exchanges >= 15, "the corpus is read: " + exchanges);
    }

    @Test
    void wrongArgumentsAndAnAnswerNotTheApisAreRefusalsNeverThrown() {
        assertEquals("BAD_REQUEST", error(SqlApiCalls.call("login", "{\"user\":1}")).getString("code"));
        assertEquals("BAD_REQUEST", error(SqlApiCalls.call("fetchChunk", "{\"statementId\":\"s\",\"index\":-1,\"token\":\"t\"}")).getString("code"));
        assertEquals("BAD_REQUEST", error(SqlApiCalls.call("nope", "{}")).getString("code"));
        assertEquals("BAD_REQUEST", error(SqlApiCalls.call("login", "[]")).getString("code"));
        // a body whose values are of the wrong kinds: refused, as a page's WebAssembly could not catch a failed cast
        assertEquals("INTERNAL", error(SqlApiCalls.read("objects", 200, "[{\"catalog\":1}]")).getString("code"));
        assertEquals("INTERNAL", error(SqlApiCalls.read("objects", 200, "{}")).getString("code"));
        assertEquals("INTERNAL", error(SqlApiCalls.read("submit", 200, "{\"statementId\":\"s\",\"state\":7}")).getString("code"));
        assertEquals("INTERNAL", error(SqlApiCalls.read("login", 200, "not json")).getString("code"));
        // a proxy's page in place of the API's error: its status and its start, said
        Json.Obj proxy = error(SqlApiCalls.read("objects", 502, "<html>Bad Gateway</html>"));
        assertEquals("HTTP 502: <html>Bad Gateway</html>", proxy.getString("message"));
        // an error whose fields are of the wrong kinds is not the API's own: said as the status
        assertEquals("HTTP 500: {\"error\":{\"code\":5}}",
                error(SqlApiCalls.read("history", 500, "{\"error\":{\"code\":5}}")).getString("message"));
    }

    @Test
    void a401IsAnExpiredSessionOnlyWhereATokenWasCarried() {
        String body = "{\"error\":{\"code\":\"AUTH_INVALID\",\"message\":\"no\"}}";
        assertTrue(Json.parseObject(SqlApiCalls.read("objects", 401, body)).getBoolOr("expired", false));
        assertTrue(Json.parseObject(SqlApiCalls.read("fetchChunk", 401, body)).getBoolOr("expired", false));
        assertEquals(false, Json.parseObject(SqlApiCalls.read("login", 401, body)).getBoolOr("expired", false));
        assertEquals(false, Json.parseObject(SqlApiCalls.read("loginKey", 401, body)).getBoolOr("expired", false));
    }

    private static Json.Obj error(String answer) {
        return Json.parseObject(answer).getObj("error");
    }
}
