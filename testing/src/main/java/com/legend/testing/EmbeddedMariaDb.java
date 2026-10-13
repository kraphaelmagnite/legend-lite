package com.legend.testing;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A REAL MariaDB for a test JVM (docs/CLOUD_BACKENDS_PCT_2026_10_11.md §13): the pinned server Bazel unpacked
 * ({@code @embedded_mariadb}, tools/mariadb/mariadb.bzl), started once per JVM on a free loopback port, its data
 * directory in the test's own temporary directory, and stopped when the JVM exits. No Docker and no jar: a test names
 * the install's root with {@code -Dembedded.mariadb.root=$(rlocationpath @embedded_mariadb//:mariadb/MARIADB_ROOT)} and
 * asks for {@link #shared()}.
 *
 * <p>The server is made for determinism, not durability: utf8mb4 with its binary collation (strings compare by code
 * point and case, as DuckDB's do), the zone UTC, the user {@code legend} with no password on 127.0.0.1 only, no binary
 * log. Its Unix socket is a short path in /tmp (a socket path is capped at 107 bytes, and a test's TEST_TMPDIR is
 * longer). It is this JVM's own child, in the test's process group, so a test that times out
 * takes it down with it; the JVM's exit stops it. MariaDB runs as root when told to ({@code --user=root}), so the
 * launcher passes the JVM's own user.
 */
public final class EmbeddedMariaDb {

    /** The account the server's init file makes: no password, every privilege, on a server bound to loopback only.
     *  Not {@code root}: a test compares the database's user with the JVM's own ({@code currentUserId()}), and a
     *  desk or container that runs as root would make the two equal by accident. */
    public static final String USER = "legend";

    private static EmbeddedMariaDb shared;
    /** Why the one start failed: every later {@link #shared()} says so instead of starting again. */
    private static IllegalStateException failed;

    private final Path root;
    private final Path data;
    private int port;
    private Process server;

    private EmbeddedMariaDb(Path root, Path data) {
        this.root = root;
        this.data = data;
    }

    /** This JVM's server, started on first use and stopped at exit. */
    public static synchronized EmbeddedMariaDb shared() {
        if (failed != null) {
            throw failed;
        }
        if (shared == null) {
            try {
                shared = start();
            } catch (IllegalStateException e) {
                failed = e;
                throw e;
            }
            EmbeddedMariaDb started = shared;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "embedded-mariadb-stop"));
        } else if (!shared.server.isAlive()) {
            // A STATEMENT KILLED THE SERVER (2026-10-11, measured on MariaDB: the kernel's OOM killer ended the server
            // at 12 GB while it planned a 9.8 MB statement, memory the session cap does not count). The test that sent
            // it has failed on its closed connection; the server starts again on the same data and port, so that
            // failure is that test's alone and not every later test's. Said on stderr, with the dead server's log.
            System.err.println("[embedded-mariadb] mariadbd died (exit " + shared.server.exitValue() + "); restarting it on port "
                    + shared.port + ". Its log's tail:\n" + shared.logTail());
            try {
                shared.startServer();
            } catch (IOException e) {
                throw new IllegalStateException("the embedded server did not restart: " + e.getMessage(), e);
            }
        }
        return shared;
    }

    /** The last few thousand characters of the server's log, to say why it died. */
    private String logTail() {
        try {
            String log = read(data.resolve("server.log"));
            return log.substring(Math.max(0, log.length() - 3000));
        } catch (IOException e) {
            return "(its log could not be read: " + e.getMessage() + ")";
        }
    }

    /** {@code jdbc:mariadb://127.0.0.1:<port>/<database>?user=legend}, the database made on the first connection. */
    public String jdbcUrl(String database) {
        return "jdbc:mariadb://127.0.0.1:" + port + "/" + database + "?user=" + USER + "&createDatabaseIfNotExist=true";
    }

    public int port() {
        return port;
    }

    private static EmbeddedMariaDb start() {
        String marker = System.getProperty("embedded.mariadb.root");
        if (marker == null) {
            throw new IllegalStateException("no -Dembedded.mariadb.root: give the test"
                    + " data = [\"@embedded_mariadb//:mariadb\", \"@embedded_mariadb//:mariadb/MARIADB_ROOT\"] and"
                    + " jvm_flags = [\"-Dembedded.mariadb.root=$(rlocationpath"
                    + " @embedded_mariadb//:mariadb/MARIADB_ROOT)\"]");
        }
        String tmp = System.getenv("TEST_TMPDIR");
        if (tmp == null) {
            throw new IllegalStateException("no TEST_TMPDIR: the embedded MariaDB runs under bazel test, its data in the"
                    + " test's own temporary directory");
        }
        try {
            // the install's real directory: the server finds its share/ beside its own binary
            Path root = Runfile.of(marker).toRealPath().getParent();
            Path data = Files.createTempDirectory(Path.of(tmp), "mariadb");
            EmbeddedMariaDb db = new EmbeddedMariaDb(root, data);
            List<String> install = new ArrayList<>(List.of(root.resolve("scripts/mariadb-install-db").toString(),
                    "--no-defaults", "--basedir=" + root, "--datadir=" + data.resolve("data"),
                    "--auth-root-authentication-method=normal", "--skip-test-db", "--skip-name-resolve",
                    "--user=" + System.getProperty("user.name")));
            db.run(install);
            IllegalStateException last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                    db.port = s.getLocalPort();
                }
                try {
                    db.startServer();
                    return db;
                } catch (IllegalStateException e) {
                    String log = Files.exists(data.resolve("server.log"))
                            ? Files.readString(data.resolve("server.log"), StandardCharsets.UTF_8) : "";
                    if (!log.contains("Address already in use") && !log.contains("Can't start server: Bind")) {
                        throw e;   // not a taken port: no retry hides it
                    }
                    last = e;   // the port was taken before the server bound it: another, up to three
                }
            }
            throw last;
        } catch (IOException e) {
            throw new IllegalStateException("the embedded MariaDB did not start: " + e.getMessage(), e);
        }
    }

    /** Starts the server on {@link #port}, returning once its log says it is ready for connections. A server that
     *  exits first fails. */
    private void startServer() throws IOException {
        Path log = data.resolve("server.log");
        Files.deleteIfExists(log);
        // /tmp, not java.io.tmpdir: under Bazel that is TEST_TMPDIR, past a socket path's 107 bytes
        Path socket = Files.createTempFile(Path.of("/tmp"), "mdb", ".sock");
        Files.delete(socket);
        Path init = data.resolve("init.sql");
        Files.writeString(init, "CREATE USER IF NOT EXISTS '" + USER + "'@'%';\n"
                + "GRANT ALL PRIVILEGES ON *.* TO '" + USER + "'@'%';\n", StandardCharsets.UTF_8);
        server = new ProcessBuilder(root.resolve("bin/mariadbd").toString(), "--no-defaults",
                "--basedir=" + root, "--datadir=" + data.resolve("data"), "--port=" + port,
                "--bind-address=127.0.0.1", "--socket=" + socket, "--user=" + System.getProperty("user.name"),
                "--character-set-server=utf8mb4", "--collation-server=utf8mb4_bin", "--default-time-zone=+00:00",
                "--skip-name-resolve", "--skip-log-bin", "--max-connections=200", "--innodb-buffer-pool-size=64M",
                "--innodb-flush-log-at-trx-commit=0",
                // a statement past 2 GB of session memory is an error, not the test's death by the OOM killer
                // (measured: one planned past 11 GB)
                "--max-session-mem-used=2147483648",
                // a statement's text up to 16 MB (the default, made explicit): a longer one is refused, the connection
                // closed ("Socket error"); allowed, a 24 MB one took the server past 4 GB in parsing, which the session
                // cap above does not count, and the OOM killer ended it, and a grouped JSON_ARRAYAGG with a LIMIT
                // answered NULL (both measured at 1 GB)
                "--max-allowed-packet=16777216", "--init-file=" + init, "--log-error=" + log)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("stdout.log")
                        .toFile())).start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("mariadbd exited " + server.exitValue() + " before accepting"
                        + " connections:\n" + read(log));
            }
            if (read(log).contains("ready for connections")) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for mariadbd", e);
            }
        }
        stop();
        throw new IllegalStateException("mariadbd did not accept connections in 120 s:\n" + read(log));
    }

    private static String read(Path log) throws IOException {
        return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    }

    private void stop() {
        if (server != null && server.isAlive()) {
            server.destroy();   // SIGTERM: a normal shutdown
            try {
                if (!server.waitFor(30, TimeUnit.SECONDS)) {
                    server.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.destroyForcibly();
            }
        }
    }

    /** Runs one of the server's tools to completion; a failure says what it printed. */
    private void run(List<String> command) throws IOException {
        Path out = data.resolve("tool.out");
        Process p = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true)
                .redirectOutput(out.toFile()).start();
        try {
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " did not finish in 180 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + command.get(0), e);
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " exited " + p.exitValue() + ":\n"
                    + Files.readString(out, StandardCharsets.UTF_8));
        }
    }
}
