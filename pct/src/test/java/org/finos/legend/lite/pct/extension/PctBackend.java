// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.model.ConnectionDefinition.DatabaseType;
import com.legend.testing.EmbeddedClickHouse;
import com.legend.testing.EmbeddedMariaDb;
import com.legend.testing.EmbeddedMySql;
import com.legend.testing.EmbeddedPostgres;
import com.legend.testing.EmbeddedTrino;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The database a PCT lane runs its suite on, chosen by the target ({@code LEGENDLITE_PCT_BACKEND}, an
 * environment variable: it must survive the fork into the test JVM), one connection per executed
 * expression:
 *
 * <ul>
 *   <li>unset: a fresh in-memory DuckDB;</li>
 *   <li>{@code h2}: a fresh in-memory H2, with the portability sweep's session settings;</li>
 *   <li>{@code postgres}: this JVM's embedded Postgres 16 (leg P2), a new session on its database;</li>
 *   <li>{@code sqlite}: a fresh in-memory SQLite (the driver's own build, in-process);</li>
 *   <li>{@code mariadb}: this JVM's embedded MariaDB 11.4, a new session on its database {@code pct};</li>
 *   <li>{@code mysql}: this JVM's embedded MySQL 8.4, a new session on its database {@code pct};</li>
 *   <li>{@code trino}: this JVM's embedded Trino 483, a new session on its memory catalog's {@code default} schema;</li>
 *   <li>{@code clickhouse}: this JVM's embedded ClickHouse 25.8, a new session (its driver's HTTP) on {@code default};</li>
 *   <li>{@code databricks}, {@code snowflake}, {@code bigquery}: a real account's warehouse, one session held for the
 *       suite ({@link CloudLane}: its coordinates and credentials from {@code LEGENDLITE_PCT_CLOUD_PROPERTIES}).</li>
 * </ul>
 *
 * Session settings are the dialect's, applied at the platform's connection seam
 * ({@code SqlDialect.sessionSetup}, from {@code Compiler.dialectOf}); this only opens the connection.
 *
 * <p>A server lane is also the host of a SYSTEM CONNECTION ({@link #systemConnections}): where the platform's system
 * database for that engine lives (a query reading the metamodel store runs there, apart from the user's session;
 * exec.SystemDatabase). This harness gives it the lane's own server and credentials, as its choice; a host
 * configures it apart. An in-process lane needs none (the platform opens its own private instance).
 *
 * <p>Both channels run on it (2026-10-11, the user's ask): channel A's adapter ({@link PctExecuteNative}) and channel
 * B's runner (channelb.ChannelB), so a lane's two verdicts come from the same database.
 */
public final class PctBackend {

    private PctBackend() {
    }

    /** The lanes' databases: each one's declared type (the storeless runtime's connection), its session and, for a
     *  server, its system connection. */
    private enum Lane {
        DUCKDB(DatabaseType.DuckDB) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:duckdb:");
            }
        },
        H2(DatabaseType.H2) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:h2:mem:" + com.legend.exec.H2Settings.SETTINGS, "sa", "");
            }
        },
        SQLITE(DatabaseType.SQLite) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection("jdbc:sqlite::memory:");
            }
        },
        MARIADB(DatabaseType.MariaDB) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedMariaDb.shared().jdbcUrl("pct"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedMariaDb.shared().jdbcUrl("pct"));
            }
        },
        MYSQL(DatabaseType.MySQL) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedMySql.shared().jdbcUrl("pct"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedMySql.shared().jdbcUrl("pct"));
            }
        },
        TRINO(DatabaseType.Trino) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedTrino.shared().jdbcUrl("default"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedTrino.shared().jdbcUrl("default"));
            }
        },
        CLICKHOUSE(DatabaseType.ClickHouse) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedClickHouse.shared().jdbcUrl("default"));
            }

            @Override
            Connection openSystem() throws SQLException {
                // a session of its own: ClickHouse keeps a temporary table only in an HTTP session
                return DriverManager.getConnection(EmbeddedClickHouse.shared().sessionJdbcUrl("default"));
            }
        },
        POSTGRES(DatabaseType.Postgres) {
            @Override
            Connection open() throws SQLException {
                return DriverManager.getConnection(EmbeddedPostgres.shared().jdbcUrl("postgres"));
            }

            @Override
            Connection openSystem() throws SQLException {
                return DriverManager.getConnection(EmbeddedPostgres.shared().jdbcUrl("postgres"));
            }
        },
        DATABRICKS(DatabaseType.Databricks) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.DATABRICKS);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.DATABRICKS);
            }
        },
        SNOWFLAKE(DatabaseType.Snowflake) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.SNOWFLAKE);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.SNOWFLAKE);
            }
        },
        BIGQUERY(DatabaseType.BigQuery) {
            @Override
            Connection open() throws SQLException {
                return CloudLane.connect(CloudLane.Cloud.BIGQUERY);
            }

            @Override
            Connection openSystem() throws SQLException {
                return CloudLane.openSystem(CloudLane.Cloud.BIGQUERY);
            }
        };

        final DatabaseType type;

        Lane(DatabaseType type) {
            this.type = type;
        }

        abstract Connection open() throws SQLException;

        /** A NEW session for the platform's system database on this server (closing it closes it). An in-process
         *  lane has none: asking is a harness bug. */
        Connection openSystem() throws SQLException {
            throw new IllegalStateException(this + " is in-process: the platform's system database is its own"
                    + " private instance, on no system connection");
        }

        boolean inProcess() {
            return com.legend.exec.Sessions.inProcess(type);
        }
    }

    private static Lane lane() {
        String backend = System.getenv("LEGENDLITE_PCT_BACKEND");
        if (backend == null) {
            return Lane.DUCKDB;
        }
        return switch (backend) {
            case "h2" -> Lane.H2;
            case "postgres" -> Lane.POSTGRES;
            case "sqlite" -> Lane.SQLITE;
            case "mariadb" -> Lane.MARIADB;
            case "mysql" -> Lane.MYSQL;
            case "trino" -> Lane.TRINO;
            case "clickhouse" -> Lane.CLICKHOUSE;
            case "databricks" -> Lane.DATABRICKS;
            case "snowflake" -> Lane.SNOWFLAKE;
            case "bigquery" -> Lane.BIGQUERY;
            default -> throw new IllegalStateException("LEGENDLITE_PCT_BACKEND=" + backend + ": a PCT lane runs on"
                    + " DuckDB (unset), h2, postgres, sqlite, mariadb, mysql, trino, clickhouse, databricks, snowflake or"
                    + " bigquery");
        };
    }

    /** The lane's name, as {@code LEGENDLITE_PCT_BACKEND} spells it ({@code duckdb} when unset). */
    public static String name() {
        return lane().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether this lane is the reference lane, an in-memory DuckDB. */
    public static boolean isDuckDb() {
        return lane() == Lane.DUCKDB;
    }

    /** This lane's storeless runtime declared alone: the session {@link #connect} opens. */
    public static String storelessRuntime() {
        return withStorelessRuntime("");
    }

    /** {@code model} with this lane's storeless runtime declared beside it: the session {@link #connect} opens. */
    static String withStorelessRuntime(String model) {
        Lane lane = lane();
        return switch (lane) {
            case DUCKDB, H2, SQLITE -> com.legend.test.StorelessRuntime.with(model, lane.type);
            case POSTGRES -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedPostgres.shared().port(), "postgres");
            case MARIADB -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedMariaDb.shared().port(), "pct");
            case MYSQL -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedMySql.shared().port(), "pct");
            // the coordinates of the session connect() opens, declared as Trino's own specification
            case TRINO -> com.legend.test.StorelessRuntime.declaring(model, lane.type, "specification: Trino { host:"
                    + " '127.0.0.1'; port: " + EmbeddedTrino.shared().port() + "; catalog: 'memory'; schema: 'default';"
                    + " };\n  auth: Test");
            case CLICKHOUSE -> com.legend.test.StorelessRuntime.onServer(model, lane.type, "127.0.0.1",
                    EmbeddedClickHouse.shared().port(), "default");
            case DATABRICKS -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.DATABRICKS);
            case SNOWFLAKE -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.SNOWFLAKE);
            case BIGQUERY -> CloudLane.withStorelessRuntime(model, CloudLane.Cloud.BIGQUERY);
        };
    }

    /** A session on this lane's database. */
    public static Connection connect() throws SQLException {
        return lane().open();
    }

    /** The host's system connections: a server lane's own engine, opened by {@link Lane#openSystem}; none for an
     *  in-process lane. */
    public static java.util.Map<DatabaseType, com.legend.exec.Sessions.Opener> systemConnections() {
        Lane lane = lane();
        return lane.inProcess() ? java.util.Map.of() : java.util.Map.of(lane.type, lane::openSystem);
    }
}
