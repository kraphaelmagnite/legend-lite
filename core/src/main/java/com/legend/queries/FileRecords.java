package com.legend.queries;

import com.legend.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Saved queries kept in a directory, one JSON file per query holding every version (its id URL-encoded as the name):
 * legend-lite's server's {@code --query-store DIR}, and Python's {@code ll.home()/queries} through the native library
 * (whose host holds the folder's lock across processes around each call). A write goes to a file beside it, then
 * replaces it, so a reader never sees half of one.
 */
public final class FileRecords implements Records {

    private final Path directory;

    public FileRecords(Path directory) {
        this.directory = directory;
    }

    @Override
    public List<Json.Obj> versions(String id) {
        Path p = file(id);
        return Files.exists(p) ? read(p) : new ArrayList<>();
    }

    @Override
    public void write(String id, List<Json.Obj> versions) {
        try {
            Files.createDirectories(directory);
            Path target = file(id);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, Json.toCompact(versions));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, List<Json.Obj>> all() {
        // by the ids themselves, not their file names: "q-2.json" sorts before "q.json", "q" before "q-2"
        Map<String, List<Json.Obj>> out = new TreeMap<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String name = p.getFileName().toString();
                out.put(URLDecoder.decode(name.substring(0, name.length() - ".json".length()), StandardCharsets.UTF_8),
                        read(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static List<Json.Obj> read(Path p) {
        try {
            List<Json.Obj> out = new ArrayList<>();
            for (Json.Node n : ((Json.Arr) Json.parse(Files.readString(p))).items()) {
                out.add((Json.Obj) n);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String id) {
        return directory.resolve(URLEncoder.encode(id, StandardCharsets.UTF_8) + ".json");
    }
}
