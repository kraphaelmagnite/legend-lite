package com.legend.queries;

import com.legend.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * DataCube's saved pages in a directory, one {@code <id>.json} file each, the row as the page keeps it (its content the
 * exact JSON text): Python's {@code ll.home()/pages} through the native library -- the layout its pages were kept in
 * before these rules were Java, so they open as they were -- and legend-lite's server's {@code --query-store DIR/dataCube}.
 * A write goes to a file beside it, then replaces it. A file that is not a page's row -- written by something else, cut
 * short, or holding another id than its name says -- is left out ({@link SavedCubes#isRow}): not listed, not found, and
 * every other page still opens. The ids are a page's own spelling ({@link SavedCubes}' rules refuse any other first; refused here again, as
 * the file's name), so a name never reaches outside the directory (no separator, no Windows device name).
 */
public final class FileCubeRecords implements SavedCubes.Records {

    private final Path directory;

    public FileCubeRecords(Path directory) {
        this.directory = directory;
    }

    @Override
    public Map<String, Json.Obj> all() {
        Map<String, Json.Obj> out = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                // a page by its file's name, as find reads it: a name that is no page's id is no page (get refuses it)
                String name = p.getFileName().toString();
                String id = name.substring(0, name.length() - ".json".length());
                Json.Obj row = SavedCubes.isPageId(id) ? read(p, id) : null;
                if (row != null) {
                    out.put(((Json.Str) row.get("id")).value(), row);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public Json.@com.legend.base.Nullable Obj find(String id) {
        Path p = file(id);
        return Files.isRegularFile(p) ? read(p, id) : null;
    }

    /** The page {@code id}'s row in {@code p}, or null when the file holds no such row (it is left out). */
    private static Json.@com.legend.base.Nullable Obj read(Path p, String id) {
        try {
            Json.Obj row = Json.parseObject(Files.readString(p));
            return SavedCubes.isRow(id, row) ? row : null;
        } catch (IllegalArgumentException | IOException unread) {
            // not a row: left out, the others still open
            return null;
        }
    }

    @Override
    public void put(Json.Obj row) {
        Path target = file(((Json.Str) row.get("id")).value());
        try {
            Files.createDirectories(directory);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, Json.toCompact(row));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void remove(String id) {
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String id) {
        SavedCubes.pageId(id);
        return directory.resolve(id + ".json");
    }
}
