// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.sql.dialect;

/**
 * A database column type, as its CATALOG reports it, read by the database's own dialect
 * into what a Pure Database declares (docs/DATACUBE_TYPES_TO_SERVER_2026_09_27.md, T2), and how
 * a source must treat it (docs/DATACUBE_APP_PLAN_2026_10_02.md, leg B).
 *
 * @param read       how the column is read: see {@link Read}
 * @param declared   the store type the Database declares for the column ({@code VARCHAR(4096)},
 *                   {@code DECIMAL(20,0)}, {@code SEMISTRUCTURED}); null only for {@link Read#LEFT_OUT}
 * @param conversion the SQL, over {@code %s} (the column), that turns the stored value into the
 *                   declared type AT THE SOURCE -- {@code CAST(%s AS DECIMAL(20,0))}; set exactly for
 *                   {@link Read#CONVERTED} and {@link Read#COPY_CONVERTED}
 * @param reason     why no Database type holds the column; set exactly for {@link Read#LEFT_OUT}
 */
public record CatalogType(Read read, @com.legend.base.Nullable String declared,
        @com.legend.base.Nullable String conversion, @com.legend.base.Nullable String reason) {

    /** How a source reads a column of the type. */
    public enum Read {
        /** The stored value already is the declared type. */
        AS_STORED,
        /**
         * The stored value must be converted to be read at all (an unsigned 64-bit integer as an exact
         * decimal, a UUID as text): a source that cannot convert (a read-only table) leaves the column
         * out, naming it.
         */
        CONVERTED,
        /**
         * Read in place, the stored value reads as the declared type under the platform's session
         * contract (every reading session's zone is UTC: {@link SqlDialect#sessionSetup}); an upload's or
         * a frame's rebuild converts it, so it holds the declared type whatever zone later reads it. A
         * zoned timestamp: its UTC instant. A snap's copy is NOT converted: it is taken as it is, read
         * under the same UTC session, and so equals the live table in value and type
         * (docs/one-platform/evidence/d5b-snap-queries.out.txt; docs/ONE_PLATFORM_PROGRAM_2026_10_12.md D5b).
         */
        COPY_CONVERTED,
        /** No Pure Database type holds the value (bytes): the column is left out, naming it, on every source. */
        LEFT_OUT
    }

    public CatalogType {
        boolean declares = declared != null;
        boolean converts = conversion != null;
        boolean says = reason != null;
        boolean ok = switch (read) {
            case AS_STORED -> declares && !converts && !says;
            case CONVERTED, COPY_CONVERTED -> declares && converts && !says;
            case LEFT_OUT -> !declares && !converts && says;
        };
        if (!ok) {
            throw new IllegalArgumentException("a " + read + " catalog type with declared=" + declared
                    + ", conversion=" + conversion + ", reason=" + reason);
        }
    }

    public static CatalogType asStored(String declared) {
        return new CatalogType(Read.AS_STORED, declared, null, null);
    }

    public static CatalogType converted(String declared, String conversion) {
        return new CatalogType(Read.CONVERTED, declared, conversion, null);
    }

    public static CatalogType copyConverted(String declared, String conversion) {
        return new CatalogType(Read.COPY_CONVERTED, declared, conversion, null);
    }

    public static CatalogType leftOut(String reason) {
        return new CatalogType(Read.LEFT_OUT, null, null, reason);
    }
}
