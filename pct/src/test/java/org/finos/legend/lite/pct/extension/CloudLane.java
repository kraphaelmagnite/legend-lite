// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package org.finos.legend.lite.pct.extension;

import com.legend.model.ConnectionDefinition.DatabaseType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A PCT lane on a cloud warehouse -- Databricks, Snowflake or BigQuery (docs/CLOUD_BACKENDS_PCT_2026_10_11.md) -- on
 * a real account. Its coordinates and credentials come from the properties file the run names in
 * {@code LEGENDLITE_PCT_CLOUD_PROPERTIES}, written from the account's secret store before the run and never from
 * the repository. For each cloud {@code c} (its {@link Cloud#key}):
 *
 * <ul>
 *   <li>{@code c.url}: the JDBC URL, credentials left out;</li>
 *   <li>{@code c.property.<name>}: each driver property the session opens with (the credentials among them);</li>
 *   <li>the coordinates the runtime declares, which are not secret: Databricks {@code hostname} and
 *       {@code httpPath}; Snowflake {@code account}, {@code warehouse}, {@code region} and {@code database};
 *       BigQuery {@code projectId} and {@code defaultDataset};</li>
 *   <li>{@code c.system.property.<name>}: each driver property the SYSTEM connection adds to the session's (the
 *       platform's system database for the engine, {@link #openSystem}): Snowflake's {@code schema}, where its
 *       temporary tables live; BigQuery's {@code EnableSession}, without which it makes no temporary table.</li>
 * </ul>
 *
 * A missing file or key is refused by name, never defaulted. One session per test JVM, opened at the first test and
 * held for the suite (a login costs a second or more): the expressions are storeless, so no test leaves state behind.
 */
final class CloudLane {

    /** The variable naming the properties file. */
    static final String PROPERTIES = "LEGENDLITE_PCT_CLOUD_PROPERTIES";

    /** The clouds, each with its declared database type and the coordinates its runtime declares. */
    enum Cloud {
        DATABRICKS("databricks", DatabaseType.Databricks) {
            @Override
            String specificationAndAuth(Properties p) {
                return "specification: Databricks { hostname: " + quoted(p, "hostname") + "; port: '443'; protocol:"
                        + " 'https'; httpPath: " + quoted(p, "httpPath") + "; };\n  auth: ApiToken { apiToken:"
                        + " 'databricks.token'; }";
            }
        },
        SNOWFLAKE("snowflake", DatabaseType.Snowflake) {
            @Override
            String specificationAndAuth(Properties p) {
                return "specification: Snowflake { name: " + quoted(p, "database") + "; account: "
                        + quoted(p, "account") + "; warehouse: " + quoted(p, "warehouse") + "; region: "
                        + quoted(p, "region") + "; };\n  auth: SnowflakePublic { publicUserName: 'snowflake.user';"
                        + " privateKeyVaultReference: 'snowflake.privateKey'; passPhraseVaultReference:"
                        + " 'snowflake.passPhrase'; }";
            }
        },
        BIGQUERY("bigquery", DatabaseType.BigQuery) {
            @Override
            String specificationAndAuth(Properties p) {
                return "specification: BigQuery { projectId: " + quoted(p, "projectId") + "; defaultDataset: "
                        + quoted(p, "defaultDataset") + "; };\n  auth: GCPApplicationDefaultCredentials";
            }
        };

        final String key;
        final DatabaseType type;

        Cloud(String key, DatabaseType type) {
            this.key = key;
            this.type = type;
        }

        /** The runtime's connection declaration: the coordinates as they are, the credential by reference. */
        abstract String specificationAndAuth(Properties p);

        /** {@code c.name}, as a Pure string literal. */
        String quoted(Properties p, String name) {
            return "'" + required(p, key + "." + name).replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
    }

    private static Properties properties;
    private static Connection session;
    private static IllegalStateException failed;

    private CloudLane() {
    }

    /** {@code model} with {@code cloud}'s storeless runtime declared beside it. */
    static synchronized String withStorelessRuntime(String model, Cloud cloud) {
        return com.legend.test.StorelessRuntime.declaring(model, cloud.type, cloud.specificationAndAuth(properties()));
    }

    /** The suite's one session on {@code cloud}: closing it is a no-op; the JVM's exit closes it. */
    static synchronized Connection connect(Cloud cloud) throws SQLException {
        if (failed != null) {
            throw failed;
        }
        if (session == null || session.isClosed()) {
            Properties p = properties();
            Properties driver = driverProperties(p, cloud.key + ".property.", new Properties());
            Connection opened;
            try {
                opened = DriverManager.getConnection(required(p, cloud.key + ".url"), driver);
            } catch (SQLException | RuntimeException e) {
                // one login attempt per suite: every later test fails with the first one's reason, at once
                failed = new IllegalStateException("opening the " + cloud.key + " session failed: " + e.getMessage(), e);
                throw failed;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    opened.close();
                } catch (SQLException e) {
                    System.err.println("[LegendLite PCT] closing the " + cloud.key + " session: " + e.getMessage());
                }
            }));
            session = opened;
        }
        Connection held = session;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        return null;
                    }
                    try {
                        return method.invoke(held, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * A NEW session on {@code cloud} for the platform's system database (closing it closes it, and the server drops its
     * temporary tables). This harness gives the system connection the suite's own account and credentials -- its
     * choice: a host configures its system connection apart -- with the system's own driver properties,
     * {@code c.system.property.<name>}, added.
     */
    static synchronized Connection openSystem(Cloud cloud) throws SQLException {
        Properties p = properties();
        Properties driver = driverProperties(p, cloud.key + ".system.property.",
                driverProperties(p, cloud.key + ".property.", new Properties()));
        return DriverManager.getConnection(required(p, cloud.key + ".url"), driver);
    }

    /** {@code into} with each {@code prefix<name>} of {@code p} set as the driver property {@code <name>}. */
    private static Properties driverProperties(Properties p, String prefix, Properties into) {
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                into.setProperty(name.substring(prefix.length()), p.getProperty(name));
            }
        }
        return into;
    }

    private static Properties properties() {
        if (properties == null) {
            String file = System.getenv(PROPERTIES);
            if (file == null || file.isEmpty()) {
                throw new IllegalStateException("a cloud PCT lane needs " + PROPERTIES + ": the properties file with"
                        + " the account's coordinates and credentials (docs/CLOUD_BACKENDS_PCT_2026_10_11.md)");
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(file))) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(PROPERTIES + "=" + file + " cannot be read", e);
            }
            properties = p;
        }
        return properties;
    }

    private static String required(Properties p, String name) {
        String value = p.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(PROPERTIES + " has no " + name + " (docs/CLOUD_BACKENDS_PCT_2026_10_11.md)");
        }
        return value;
    }
}
