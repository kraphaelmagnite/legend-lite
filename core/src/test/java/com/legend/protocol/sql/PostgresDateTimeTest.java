// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PostgresDateTime}: each text read as Postgres 16 reads it (DateStyle ISO, MDY), its answers and its errors
 * measured on Postgres (2026-10-13). A randomized differential of 10,000 spellings against Postgres agreed on every one
 * this reads; the rest it refuses by name.
 */
class PostgresDateTimeTest {

    private static final SqlAst.Pos POS = new SqlAst.Pos(1, 1);

    /** Postgres's answers: the type, the text, the value (yyyy-mm-dd, or yyyy-mm-ddThh:mm:ss[.f]). */
    private static final String[][] READ = {
                {"date", "1999-01-08", "1999-01-08"},
                {"date", "January 8, 1999", "1999-01-08"},
                {"date", "Jan 8, 1999", "1999-01-08"},
                {"date", "8 January 1999", "1999-01-08"},
                {"date", "1999-Jan-08", "1999-01-08"},
                {"date", "08-Jan-1999", "1999-01-08"},
                {"date", "Jan-08-1999", "1999-01-08"},
                {"date", "Wed Dec 17 1997", "1997-12-17"},
                {"date", "1/8/1999", "1999-01-08"},
                {"date", "1999/1/8", "1999-01-08"},
                {"date", "1-8-1999", "1999-01-08"},
                {"date", "1999.008", "1999-01-08"},
                {"date", "19990108", "1999-01-08"},
                {"date", "990108", "1999-01-08"},
                {"date", "J2451187", "1999-01-08"},
                {"date", "1/8/99", "1999-01-08"},
                {"date", "1/8/69", "2069-01-08"},
                {"date", "1/8/70", "1970-01-08"},
                {"date", "08-Jan-05", "2005-01-08"},
                {"date", "Jan 8, 00", "2000-01-08"},
                {"date", "January 8, 1999 AD", "1999-01-08"},
                {"date", "1999-01-08 23:59:59+02", "1999-01-08"},
                {"date", "Jan 8 1999 04:05 PM", "1999-01-08"},
                {"date", "2024.366", "2024-12-31"},
                {"date", "2023.366", "2024-01-01"},
                {"timestamp", "January 8, 1999 04:05:06", "1999-01-08T04:05:06"},
                {"timestamp", "1999-01-08 4:05:06 PM", "1999-01-08T16:05:06"},
                {"timestamp", "1999-01-08 12:05 AM", "1999-01-08T00:05:00"},
                {"timestamp", "1999-01-08 12:05 PM", "1999-01-08T12:05:00"},
                {"timestamp", "1999-01-08 04:05", "1999-01-08T04:05:00"},
                {"timestamp", "1999-01-08 04:", "1999-01-08T04:00:00"},
                {"timestamp", "1999-01-08 04:05.5", "1999-01-08T00:04:05.5"},
                {"timestamp", "99991201 00:00:00.0", "9999-12-01T00:00:00"},
                {"timestamp", "epoch", "1970-01-01T00:00:00"},
                {"timestamp", "1999-01-08T040506", "1999-01-08T04:05:06"},
                {"timestamp", "1999-01-08 040506.25", "1999-01-08T04:05:06.25"},
                {"timestamp", "1999-01-08 04:05:06.1234565", "1999-01-08T04:05:06.123456"},
                {"timestamp", "1999-01-08 04:05:06.9999996", "1999-01-08T04:05:07"},
                {"timestamp", "1999-01-08 23:59:60", "1999-01-09T00:00:00"},
                {"timestamp", "1999-01-08 24:00", "1999-01-09T00:00:00"},
                {"timestamp", "1999-01-08 04:05:06 -05:30", "1999-01-08T04:05:06"},
                {"timestamp", "J2451187 T 04:05", "1999-01-08T04:05:00"},
    };

    /** Postgres's errors, word for word. */
    private static final String[][] ERRORS = {
                {"date", "Feb 30, 2023", "date/time field value out of range: \"Feb 30, 2023\""},
                {"date", "13/8/1999", "date/time field value out of range: \"13/8/1999\""},
                {"date", "Wednesday", "invalid input syntax for type date: \"Wednesday\""},
                {"date", "Jan Feb 8 1999", "invalid input syntax for type date: \"Jan Feb 8 1999\""},
                {"timestamp", "1999-01-08 24:00:01", "date/time field value out of range: \"1999-01-08 24:00:01\""},
                {"timestamp", "1999-01-08 04:05:06+16",
                        "time zone displacement out of range: \"1999-01-08 04:05:06+16\""},
                {"timestamp", "1999-01-08 13:00 PM", "date/time field value out of range: \"1999-01-08 13:00 PM\""},
                {"timestamp", "Jan 8 1999 4pm", "invalid input syntax for type timestamp: \"Jan 8 1999 4pm\""},
    };

    @Test
    void everySpellingIsReadAsPostgresReadsIt() {
        for (String[] r : READ) {
            PostgresDateTime.Value v = PostgresDateTime.read(r[1], r[0], POS);
            String read = r[0].equals("date") ? v.date().toString() : spelled(v);
            assertEquals(r[2], read, r[0] + " '" + r[1] + "'");
        }
    }

    @Test
    void textPostgresRejectsIsItsError() {
        for (String[] r : ERRORS) {
            SqlTranslationException e = assertThrows(SqlTranslationException.class,
                    () -> PostgresDateTime.read(r[1], r[0], POS), r[1]);
            assertEquals(SqlTranslationException.Kind.INVALID, e.kind(), r[1]);
            assertEquals(r[2], e.getMessage(), r[1]);
        }
    }

    @Test
    void whatPostgresReadsOtherwiseIsRefusedByName() {
        String[][] refused = {
                {"date", "January 8, 99 BC", "'January 8, 99 BC' as a date is not supported yet (a year before"
                        + " Christ)"},
                {"date", "today", "'today' as a date is not supported yet (the word 'today')"},
                {"timestamp", "infinity", "'infinity' as a timestamp is not supported yet (the word 'infinity')"},
                {"timestamp", "1999-01-08 04:05:06 PST", "'1999-01-08 04:05:06 PST' as a timestamp is not supported"
                        + " yet (a time zone's name, or a word not read here)"},
                {"date", "Jan 8 12345", "'Jan 8 12345' as a date is not supported yet (a date outside the years 1 to"
                        + " 9999)"},
        };
        for (String[] r : refused) {
            SqlTranslationException e = assertThrows(SqlTranslationException.class,
                    () -> PostgresDateTime.read(r[1], r[0], POS), r[1]);
            assertEquals(SqlTranslationException.Kind.UNSUPPORTED, e.kind(), r[1]);
            assertEquals(r[2], e.getMessage(), r[1]);
        }
    }

    private static String spelled(PostgresDateTime.Value v) {
        LocalDateTime t = v.date().atStartOfDay().plus(v.micros(), ChronoUnit.MICROS);
        String s = t.toLocalDate() + "T" + String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", t.getHour(), t.getMinute(),
                t.getSecond());
        if (t.getNano() == 0) {
            return s;
        }
        String f = String.format(java.util.Locale.ROOT, "%06d", t.getNano() / 1000);
        int end = f.length();
        while (f.charAt(end - 1) == '0') {
            end--;
        }
        return s + "." + f.substring(0, end);
    }
}
