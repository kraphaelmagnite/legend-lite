// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.server;

import com.legend.compiler.element.ModelContext;
import com.legend.compiler.element.type.Multiplicity;
import com.legend.compiler.element.type.Type;
import com.legend.error.LegendCompileException;
import com.legend.error.ModelException;
import com.legend.json.Json;
import com.legend.parser.PmcdParser;
import com.legend.protocol.ModelComposer;
import com.legend.protocol.ProtocolReader;
import com.legend.protocol.PureComposer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CREATE A CLASS (docs/DATACUBE_MODEL_DESIGN_2026_10_10.md §15): a relation -- a DataCube grid's query -- made a class
 * of the model, with everything it needs to run, written as the files of the model's project. legend-engine's REPL
 * promotes a relation so ({@code PromoteRelationToModel}, {@code RelationClassMappingGenerator}): a function returning
 * the relation, typed as the relation's own columns; a class, each column a property; a mapping of the class to the
 * function ({@code Relation { ~func ... }}); in its layout, {@code <pkg>::domain}, {@code <pkg>::function},
 * {@code <pkg>::mapping}. This adds what the class needs to run where its rows are:
 *
 * <ul>
 *   <li><b>the store</b> {@code <pkg>::store::<Place>}: ONE Database for the place the rows are (the tab's DuckDB is
 *       one database, a table per source), the tables the relation reads copied into it from the Database it reads them
 *       from now -- once; a table already there and declared otherwise is refused, never overwritten. One Database per
 *       place, not per source, because lite plans a query over two stores only when one connection serves both, and a
 *       connection names its store;</li>
 *   <li><b>its connection</b> {@code <pkg>::connection::<Place>}, the one the relation's runtime reads that Database
 *       through now, for the new store; and <b>the runtime</b> {@code <pkg>::runtime::<Place>}, binding the store to it
 *       and naming the model's mapping;</li>
 *   <li><b>the model's mapping</b> {@code <pkg>::mapping::Model}: it includes every class's mapping (and holds the
 *       associations, Create an Association…); a runtime names it alone, since lite's planner refuses a runtime that
 *       names a mapping and another that includes it;</li>
 *   <li><b>sample rows</b> {@code <pkg>::data::<Table>Sample}: a {@code Data} element holding the source's first rows
 *       as relational CSV, written with its table, when the caller hands the rows in -- this writer runs nothing.</li>
 * </ul>
 *
 * <p>Each column is a property (named by upstream's rule, {@link #camelCase}, or as the caller names it), a KEY only (a
 * local property of the mapping, {@code +customerId: Integer[0..1]: customer_id}, for an association to join on, not
 * shown on the class), or left out. A property's type is its column's as the compiler types the relation, a decimal
 * {@code Decimal}; its multiplicity the column's, {@code [1]} or {@code [0..1]}.
 *
 * <p>The answer is the files to write in the SDLC's own change shape ({@code {type, path, pureCode}}, {@code type}
 * {@code CREATE} for an element the model does not hold, {@code MODIFY} for one it does), each element printed by
 * lite's model printer (upstream's composer, rule for rule), so a file reads as Studio writes it; the new model is
 * compiled whole, and the class read back and planned, before it is answered. What cannot be a class is refused by
 * name, as a model is ({@link ModelException}).
 */
public final class PromoteRelationToModel {

    private PromoteRelationToModel() {
    }

    private static final String RELATION = "meta::pure::metamodel::relation::Relation";
    private static final String DOC = "meta::pure::profiles::doc.doc";

    /**
     * The words Pure reads as literals, never as a name: a name spelled so cannot be written unquoted, and the model
     * printer -- upstream's composer, rule for rule ({@code PureGrammarComposerUtility.convertIdentifier}) -- writes it
     * unquoted, so a file holding one would not read back.
     */
    private static final Set<String> LITERAL_WORDS = Set.of("true", "false");

    /** One column of the relation, as the class takes it. */
    private record Column(String name, Type type, boolean required, String as, String property,
            @com.legend.base.Nullable String doc) {
    }

    /** Where the rows are, as the project names it: its package and the place's name ({@code Browser}). */
    private record Place(String pkg, String name) {
        String store() {
            return pkg + "::store::" + name;
        }

        String connection() {
            return pkg + "::connection::" + name;
        }

        String runtime() {
            return pkg + "::runtime::" + name;
        }

        /** The runtime's id for the store's connection. */
        String id() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    /** A table new to the store: its schema, its name, its columns' names. */
    private record AddedTable(String schema, String name, List<String> columns) {
    }

    /** One file to write: the SDLC's {@code PureChange}. */
    private record Change(String type, String path, String pureCode) {
        Map<String, Object> json() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", type);
            out.put("path", path);
            out.put("pureCode", pureCode);
            return out;
        }
    }

    /**
     * {@code request} ({@code model} read by the caller as {@code model}):
     * {@code {"lambda", "package", "class", "documentation"?, "columns"?: {column: {"as"?: "property"|"key"|"omit",
     * "name"?, "documentation"?}}, "where": {"from", "runtime", "as", "samples"?: {table: {"columns", "rows"}}}}}.
     * {@code where.from} is the Database the relation reads its tables from now, {@code where.runtime} the runtime it
     * runs on now, {@code where.as} the place's name ({@code Browser}).
     */
    public static Map<String, Object> promote(String model, Json.Obj request) {
        String pkg = packagePath(request.getString("package"));
        String name = request.getString("class");
        if (!isIdentifier(name)) {
            throw refused("a class's name is a Pure name (letters, digits and _, not led by a digit): '" + name + "'");
        }
        Json.Obj where = request.getObj("where");
        String from = where.getString("from");
        String as = where.getString("as");
        if (!isIdentifier(as)) {
            throw refused("a place's name is a Pure name: '" + as + "'");
        }
        Place place = new Place(pkg, as);
        String className = pkg + "::domain::" + name;
        String function = pkg + "::function::" + name + "Fn";
        String classMapping = pkg + "::mapping::" + name + "Mapping";
        String modelMapping = pkg + "::mapping::Model";
        String store = place.store();
        String runtime = place.runtime();

        Json.Obj lambda = request.getObj("lambda");
        if (!arrOf(lambda, "parameters").items().isEmpty()) {
            throw refused("a class is made of a relation that takes no parameters");
        }
        ModelContext before = com.legend.Compiler.compileModel(model);
        Type type = com.legend.Compiler.query(before, ProtocolReader.lambda(lambda)).resultType().type();
        Type.RelationType relation = Type.schemaView(type);
        if (relation == null) {
            throw refused("a class is made of a relation; this query returns " + type.typeName());
        }
        if (!relation.dynamicColumns().isEmpty()) {
            throw refused("a class has fixed properties, and this relation's columns are known only when it runs "
                    + "(a pivot's): take the pivot off first");
        }
        List<Column> columns = columns(relation.columns(), objOf(request, "columns"));

        Map<String, Json.Obj> elements = elementsOf(model);
        List<Change> changes = new ArrayList<>();
        // the relation, reading its tables from the store it is written with
        List<String[]> read = new ArrayList<>();
        Json.Node body = rewritten(lambda.getArr("body"), from, store, read);
        // every table the function reads is the project's store's: a file reading another store (the tab's own, which
        // the project does not hold) would compile here, beside the tab's model, and nowhere else (the audit's C1)
        for (String other : storesRead(body)) {
            if (!other.equals(store)) {
                throw refused("the relation reads the store '" + other + "', which this class's place does not write: "
                        + "a class reads the tables of one place");
            }
        }
        // a class made again is its own relation's: a class of the same name over other tables is another class, and
        // is not written over it (the audit's C2; the design's §15.4)
        Json.Obj made = elementWhoseName(elements, pkg + "::function", name + "Fn");
        if (made != null && !tablesRead(made.getArr("body")).equals(tablesRead(body))) {
            throw refused("'" + className + "' is already the class of " + String.join(", ", tablesRead(made.getArr("body")))
                    + ": name this class otherwise");
        }
        List<AddedTable> addedTables = new ArrayList<>();
        if (!from.equals(store)) {
            changes.addAll(storeAndConnection(elements, from, where.getString("runtime"), place, read, addedTables));
        }
        add(changes, elements, runtimeOf(elements.get(runtime), place, modelMapping));
        String relationType = relationType(columns);
        add(changes, elements, element("###Pure\nfunction " + function + "(): " + relationType + "[1]\n{\n"
                + bodyText(body) + "\n}\n"));
        add(changes, elements, element("###Pure\n" + classText(className, request.getStringOr("documentation", null),
                columns)));
        add(changes, elements, element("###Mapping\n" + mappingText(classMapping, className,
                function + "():" + relationType + "[1]", columns)));
        add(changes, elements, modelMappingOf(elements.get(modelMapping), modelMapping, classMapping));
        Json.Obj samples = objOf(where, "samples");
        List<String> notes = new ArrayList<>();
        for (AddedTable table : addedTables) {
            Json.Obj sample = samples.getObjOr(table.name(), null);
            if (sample != null) {
                // named after its table as it is (`trades_2` Trades_2Sample, `trades2` Trades2Sample), so two tables'
                // samples are two elements; one already holding another table's rows is not written over (C2)
                String held = identifier(table.name());
                String data = pkg + "::data::" + held.substring(0, 1).toUpperCase(Locale.ROOT) + held.substring(1) + "Sample";
                Json.Obj existing = elements.get(data);
                if (existing != null && !sampleTables(existing).equals(List.of(table.schema() + "." + table.name()))) {
                    throw refused("the sample '" + data + "' holds the rows of " + String.join(", ", sampleTables(existing))
                            + ", not of " + table.name() + ": name the source's table otherwise");
                }
                add(changes, elements, element("###Data\n" + dataText(data, table, sample, notes)));
            }
        }

        // the model the files make, compiled whole; the class read back through its mapping and planned
        String afterText = withChanges(model, changes);
        String query = classQuery(className, columns);
        try {
            ModelContext after = com.legend.Compiler.compileModel(afterText);
            com.legend.Compiler.query(after, "|" + query).plan(runtime);
        } catch (LegendCompileException e) {
            throw refused("the class would not compile: " + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changes", changes.stream().map(Change::json).toList());
        out.put("class", className);
        out.put("mapping", modelMapping);
        out.put("runtime", runtime);
        out.put("query", query);
        List<Map<String, Object>> properties = new ArrayList<>();
        for (Column c : columns) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("column", c.name());
            p.put("as", c.as());
            if (!"omit".equals(c.as())) {
                p.put("property", c.property());
                p.put("type", propertyType(c));
                p.put("multiplicity", c.required() ? "[1]" : "[0..1]");
            }
            properties.add(p);
        }
        out.put("properties", properties);
        out.put("notes", notes);
        return out;
    }

    // ---------------------------------------------------------------------
    // The columns, as the class takes them
    // ---------------------------------------------------------------------

    private static List<Column> columns(List<Type.Column> typed, Json.Obj asked) {
        Set<String> names = new HashSet<>();
        for (Type.Column c : typed) {
            names.add(c.name());
        }
        for (String k : asked.fields().keySet()) {
            if (!names.contains(k)) {
                throw refused("the relation has no column '" + k + "'");
            }
        }
        // the properties the caller names first, so a name made by the rule never takes one of theirs
        Set<String> taken = new HashSet<>();
        for (Type.Column c : typed) {
            Json.Obj a = objOf(asked, c.name());
            String given = a.getStringOr("name", null);
            if (given != null && !"omit".equals(asOf(c.name(), a))) {
                if (!isIdentifier(given)) {
                    throw refused("a property's name is a Pure name: '" + given + "'");
                }
                if (!taken.add(given)) {
                    throw refused("two columns are named '" + given + "'");
                }
            }
        }
        List<Column> out = new ArrayList<>();
        for (Type.Column c : typed) {
            Json.Obj a = objOf(asked, c.name());
            String as = asOf(c.name(), a);
            String property = a.getStringOr("name", null);
            if (property == null && !"omit".equals(as)) {
                String base = identifier(camelCase(c.name().replaceAll("[ /@~<>.]", "_").replace("\"", ""), false));
                property = base;
                for (int n = 2; !taken.add(property); n++) {
                    property = base + n;
                }
            }
            boolean required = c.multiplicity() instanceof Multiplicity.Bounded b && b.lower() == 1
                    && b.upper() != null && b.upper() == 1;
            Column col = new Column(c.name(), c.type(), required, as, property == null ? "" : property,
                    a.getStringOr("documentation", null));
            if (!"omit".equals(as)) {
                propertyType(col);
            }
            out.add(col);
        }
        if (out.stream().noneMatch(c -> "property".equals(c.as()))) {
            throw refused("a class needs at least one property: every column is a key or left out");
        }
        return out;
    }

    private static String asOf(String column, Json.Obj asked) {
        String as = asked.has("as") ? asked.getString("as") : "property";
        if (!Set.of("property", "key", "omit").contains(as)) {
            throw new IllegalArgumentException("the column '" + column + "' is asked to be '" + as
                    + "': a property, a key or omit");
        }
        return as;
    }

    /** Whether a property can hold a value of {@code t}. */
    private static boolean holdable(Type t) {
        return t instanceof Type.Primitive || t instanceof Type.PrecisionDecimal || t instanceof Type.EnumType
                || com.legend.compiler.element.type.PlatformTypes.isVariant(t);
    }

    /** A property's type: its column's, a decimal {@code Decimal}; a type a property cannot hold is refused by name. */
    private static String propertyType(Column c) {
        Type t = c.type();
        if (t instanceof Type.Primitive p) {
            return p.typeName();
        }
        if (t instanceof Type.PrecisionDecimal) {
            return "Decimal";
        }
        if (t instanceof Type.EnumType e) {
            return e.fqn();
        }
        if (com.legend.compiler.element.type.PlatformTypes.isVariant(t)) {
            return com.legend.compiler.element.type.PlatformTypes.VARIANT;
        }
        throw refused("the column '" + c.name() + "' is of " + t.typeName() + ", which a property cannot hold");
    }

    // ---------------------------------------------------------------------
    // The elements, as text first and printed by the model printer
    // ---------------------------------------------------------------------

    /** The relation's type as a function returns it: {@code Relation<(trade_id:Integer, 'Trade Note':String, ...)>}. */
    private static String relationType(List<Column> columns) {
        List<String> cols = new ArrayList<>();
        for (Column c : columns) {
            // a column left out is still the relation's: its type as the compiler names it, held by no property
            cols.add(quotedIfNeeded(c.name()) + ":" + ("omit".equals(c.as()) && !holdable(c.type()) ? c.type().typeName()
                    : propertyType(c)));
        }
        return RELATION + "<(" + String.join(", ", cols) + ")>";
    }

    private static String bodyText(Json.Node body) {
        List<String> lines = new ArrayList<>();
        for (Json.Node statement : ((Json.Arr) body).items()) {
            lines.add("  " + PureComposer.valueSpecification(statement, PureComposer.Style.STANDARD));
        }
        return String.join(";\n", lines);
    }

    private static String classText(String className, @com.legend.base.Nullable String doc, List<Column> columns) {
        StringBuilder out = new StringBuilder("Class ").append(docOf(doc)).append(className).append("\n{\n");
        for (Column c : columns) {
            if ("property".equals(c.as())) {
                out.append("  ").append(docOf(c.doc())).append(c.property()).append(": ").append(propertyType(c))
                        .append(c.required() ? "[1]" : "[0..1]").append(";\n");
            }
        }
        return out.append("}\n").toString();
    }

    private static String docOf(@com.legend.base.Nullable String doc) {
        return doc == null || doc.strip().isEmpty() ? "" : "{" + DOC + " = " + literal(doc) + "} ";
    }

    private static String mappingText(String mapping, String className, String descriptor, List<Column> columns) {
        List<String> lines = new ArrayList<>();
        for (Column c : columns) {
            if ("property".equals(c.as())) {
                lines.add("    " + c.property() + ": " + quoted(c.name()));
            } else if ("key".equals(c.as())) {
                lines.add("    +" + c.property() + ": " + propertyType(c) + (c.required() ? "[1]" : "[0..1]") + ": "
                        + quoted(c.name()));
            }
        }
        return "Mapping " + mapping + "\n(\n  *" + className + ": Relation\n  {\n    ~func " + descriptor + "\n"
                + String.join(",\n", lines) + "\n  }\n)\n";
    }

    /** {@code Trade.all()->project(~[trade_id: x|$x.tradeId, ...])}: the class read back as the relation's columns,
     *  under their own names, so a grid over the class names what its relation named. */
    private static String classQuery(String className, List<Column> columns) {
        List<String> cols = new ArrayList<>();
        for (Column c : columns) {
            if ("property".equals(c.as())) {
                cols.add(quotedIfNeeded(c.name()) + ": x|$x." + c.property());
            }
        }
        return className + ".all()->project(~[" + String.join(", ", cols) + "])";
    }

    /** The sample rows as relational CSV: a header of the table's columns, a line per row; a value holding a line
     *  break cannot be a CSV line here, and its row is left out, said in {@code notes}. */
    private static String dataText(String data, AddedTable table, Json.Obj sample, List<String> notes) {
        List<String> columns = sample.getStringArray("columns");
        if (!columns.equals(table.columns())) {
            throw new IllegalArgumentException("the sample of " + table.name() + " has the columns " + columns
                    + ", the table " + table.columns());
        }
        List<String> lines = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (String c : columns) {
            header.add(cell(c));
        }
        lines.add(String.join(",", header));
        int left = 0;
        for (Json.Node row : sample.getArr("rows").items()) {
            List<String> cells = new ArrayList<>();
            boolean fits = true;
            List<Json.Node> values = ((Json.Arr) row).items();
            if (values.size() != columns.size()) {
                throw new IllegalArgumentException("a sample row of " + table.name() + " has " + values.size()
                        + " values, the table " + columns.size() + " columns");
            }
            for (Json.Node v : values) {
                String text = switch (v) {
                    case Json.Null n -> "";
                    case Json.Str s -> s.value();
                    case Json.Bool b -> String.valueOf(b.value());
                    case Json.Num n -> Json.toCompact(n);
                    default -> throw new IllegalArgumentException("a sample's value is text, a number, true or "
                            + "false, or null");
                };
                fits &= text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
                cells.add(cell(text));
            }
            if (fits) {
                lines.add(String.join(",", cells));
            } else {
                left++;
            }
        }
        if (left > 0) {
            notes.add(left + " of " + table.name() + "'s sample rows left out: a value holds a line break, which relational "
                    + "test data cannot");
        }
        List<String> parts = new ArrayList<>();
        for (String line : lines) {
            parts.add("      " + literal(line + "\n"));
        }
        return "Data " + data + "\n{\n  Relational\n  #{\n    " + quotedIfNeeded(table.schema()) + "."
                + quotedIfNeeded(table.name()) + ":\n"
                + String.join("+\n", parts) + ";\n  }#\n}\n";
    }

    /** One CSV cell: quoted when it holds a comma or a quote, its quotes doubled (RFC 4180, the relational CSV
     *  reader's rule). An empty text and a null are one empty field: the relational CSV reader (setup's CsvSeed) reads
     *  an empty cell, quoted or not, as null -- a sample keeps no empty text apart from a missing value. */
    private static String cell(String text) {
        return text.indexOf(',') < 0 && text.indexOf('"') < 0 ? text : "\"" + text.replace("\"", "\"\"") + "\"";
    }

    // ---------------------------------------------------------------------
    // The store, its connection, the runtime, the model's mapping: merged into what the model holds
    // ---------------------------------------------------------------------

    /**
     * The tables the relation reads, from {@code from}, written into the place's store (made from {@code from}'s
     * declaration the first time), and the connection {@code fromRuntime} reads {@code from} through, made the
     * store's. {@code added} gains each table new to the store.
     */
    private static List<Change> storeAndConnection(Map<String, Json.Obj> elements, String from, String fromRuntime,
            Place place, List<String[]> read, List<AddedTable> added) {
        String store = place.store();
        String connection = place.connection();
        Json.Obj source = elements.get(from);
        if (source == null || !"relational".equals(source.getStringOr("_type", ""))) {
            throw refused("'" + from + "' is not a Database of the model");
        }
        for (String part : List.of("joins", "filters", "includedStores")) {
            if (!arrOf(source, part).items().isEmpty()) {
                throw refused("the Database '" + from + "' has " + part + ": a class is made over a source's tables "
                        + "alone");
            }
        }
        if (read.isEmpty()) {
            throw refused("the relation reads no table of '" + from + "'");
        }
        Json.Obj existing = elements.get(store);
        Json.Obj into = existing != null ? existing
                : renamed(source, place.pkg() + "::store", place.name(), Map.of("schemas", Json.arr()));
        for (String[] t : read) {
            Json.Obj table = tableOf(source, t[0], t[1]);
            if (table == null) {
                throw refused("'" + from + "' declares no table " + t[0] + "." + t[1]);
            }
            Json.Obj there = tableOf(into, t[0], t[1]);
            if (there == null) {
                into = withTable(into, t[0], table);
                added.add(new AddedTable(t[0], t[1], columnNames(table)));
            } else if (!there.equals(table)) {
                throw refused("the store '" + store + "' declares the table " + t[0] + "." + t[1] + " otherwise than '"
                        + from + "' does: make the class over a source of that table's columns");
            }
        }
        List<Change> out = new ArrayList<>();
        if (existing == null || !existing.equals(into)) {
            out.add(new Change(existing == null ? "CREATE" : "MODIFY", store, file(into)));
        }
        if (!elements.containsKey(connection)) {
            Json.Obj value = connectionValue(elements, fromRuntime, from);
            LinkedHashMap<String, Json.Node> fields = new LinkedHashMap<>(value.fields());
            fields.put("element", Json.str(store));
            Json.Obj spec = value.getObjOr("datasourceSpecification", null);
            if (spec != null && "duckDB".equals(spec.getStringOr("_type", "")) && !spec.has("path")) {
                // a host's own DuckDB (a table model names no path: the host runs the SQL on its engine): the tab's
                // is in memory, and upstream's DuckDB names its database
                fields.put("datasourceSpecification", with(spec, "path", Json.str(":memory:")));
            }
            out.add(new Change("CREATE", connection, file(renamed(Json.obj(), place.pkg() + "::connection",
                    place.name(), Map.of("_type", Json.str("connection"), "connectionValue", new Json.Obj(fields))))));
        }
        return out;
    }

    /** The connection {@code runtime} reads {@code store} through: a packageable connection's value, or the one the
     *  runtime holds itself. */
    private static Json.Obj connectionValue(Map<String, Json.Obj> elements, String runtime, String store) {
        Json.Obj rt = elements.get(runtime);
        if (rt == null || !"runtime".equals(rt.getStringOr("_type", ""))) {
            throw refused("'" + runtime + "' is not a runtime of the model");
        }
        for (Json.Node n : arrOf(rt.getObj("runtimeValue"), "connections").items()) {
            Json.Obj sc = (Json.Obj) n;
            if (store.equals(sc.getObj("store").getString("path"))) {
                for (Json.Node c : sc.getArr("storeConnections").items()) {
                    Json.Obj value = ((Json.Obj) c).getObj("connection");
                    if ("connectionPointer".equals(value.getStringOr("_type", ""))) {
                        Json.Obj pointed = elements.get(value.getString("connection"));
                        if (pointed == null) {
                            throw refused("the connection '" + value.getString("connection") + "' is not in the model");
                        }
                        return pointed.getObj("connectionValue");
                    }
                    return value;
                }
            }
        }
        throw refused("the runtime '" + runtime + "' binds no connection to '" + store + "'");
    }

    /** The runtime, made or as the model holds it, naming the model's mapping and binding the store to its
     *  connection; null when it already does both. */
    private static @com.legend.base.Nullable Json.Obj runtimeOf(@com.legend.base.Nullable Json.Obj existing,
            Place place, String modelMapping) {
        String store = place.store();
        Json.Obj rt = existing != null ? existing : renamed(Json.obj(), place.pkg() + "::runtime", place.name(),
                Map.of("_type", Json.str("runtime"),
                "runtimeValue", Json.of(Map.of("_type", "engineRuntime", "connectionStores", List.of(),
                        "connections", List.of(), "mappings", List.of()))));
        Json.Obj value = rt.getObj("runtimeValue");
        List<Json.Node> mappings = new ArrayList<>(arrOf(value, "mappings").items());
        Json.Node pointer = Json.of(Map.of("path", modelMapping, "type", "MAPPING"));
        if (!mappings.contains(pointer)) {
            mappings.add(pointer);
        }
        List<Json.Node> connections = new ArrayList<>(arrOf(value, "connections").items());
        boolean bound = false;
        for (Json.Node n : connections) {
            bound |= store.equals(((Json.Obj) n).getObj("store").getString("path"));
        }
        if (!bound) {
            connections.add(Json.of(Map.of("store", Map.of("path", store, "type", "STORE"), "storeConnections",
                    List.of(Map.of("connection", Map.of("_type", "connectionPointer", "connection",
                            place.connection()), "id", place.id())))));
        }
        LinkedHashMap<String, Json.Node> v = new LinkedHashMap<>(value.fields());
        v.put("mappings", new Json.Arr(mappings));
        v.put("connections", new Json.Arr(connections));
        Json.Obj made = with(rt, "runtimeValue", new Json.Obj(v));
        return made.equals(existing) ? null : made;
    }

    /** The model's mapping, made or as the model holds it, including the class's mapping; null when it already
     *  does. */
    private static @com.legend.base.Nullable Json.Obj modelMappingOf(@com.legend.base.Nullable Json.Obj existing,
            String modelMapping, String classMapping) {
        Json.Obj m = existing != null ? existing : element("###Mapping\nMapping " + modelMapping + "\n(\n)\n");
        List<Json.Node> includes = new ArrayList<>(arrOf(m, "includedMappings").items());
        Json.Node include = Json.of(Map.of("_type", "mappingIncludeMapping", "includedMapping", classMapping));
        if (includes.contains(include)) {
            return existing == null ? m : null;
        }
        includes.add(include);
        return with(m, "includedMappings", new Json.Arr(includes));
    }

    // ---------------------------------------------------------------------
    // The relation, reading the new store
    // ---------------------------------------------------------------------

    /** {@code node} with each store accessor of {@code from} ({@code #>{from.table}#}) reading {@code store}; each
     *  table read so is added to {@code read} as {schema, table}. */
    private static Json.Node rewritten(Json.Node node, String from, String store, List<String[]> read) {
        if (node instanceof Json.Arr a) {
            List<Json.Node> items = new ArrayList<>();
            for (Json.Node n : a.items()) {
                items.add(rewritten(n, from, store, read));
            }
            return new Json.Arr(items);
        }
        if (!(node instanceof Json.Obj o)) {
            return node;
        }
        if ("classInstance".equals(o.getStringOr("_type", "")) && ">".equals(o.getStringOr("type", ""))) {
            Json.Obj value = o.getObj("value");
            List<String> path = value.getStringArray("path");
            if (path.get(0).equals(from)) {
                String[] table = path.size() == 3 ? new String[] {path.get(1), path.get(2)}
                        : new String[] {"default", path.get(1)};
                if (read.stream().noneMatch(t -> t[0].equals(table[0]) && t[1].equals(table[1]))) {
                    read.add(table);
                }
                List<Json.Node> moved = new ArrayList<>();
                moved.add(Json.str(store));
                for (String p : path.subList(1, path.size())) {
                    moved.add(Json.str(p));
                }
                return with(o, "value", with(value, "path", new Json.Arr(moved)));
            }
            return o;
        }
        LinkedHashMap<String, Json.Node> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Json.Node> e : o.fields().entrySet()) {
            fields.put(e.getKey(), rewritten(e.getValue(), from, store, read));
        }
        return new Json.Obj(fields);
    }

    /** The stores {@code node} reads tables of ({@code #>{store.table}#}), in the order read. */
    private static java.util.Set<String> storesRead(Json.Node node) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (List<String> path : accessors(node)) {
            out.add(path.get(0));
        }
        return out;
    }

    /** The tables {@code node} reads, each {@code store.schema.table} ({@code default} when it names no schema). */
    private static List<String> tablesRead(Json.Node node) {
        List<String> out = new ArrayList<>();
        for (List<String> path : accessors(node)) {
            String t = path.size() == 3 ? path.get(0) + "." + path.get(1) + "." + path.get(2)
                    : path.get(0) + ".default." + path.get(1);
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Each store accessor's path in {@code node}. */
    private static List<List<String>> accessors(Json.Node node) {
        List<List<String>> out = new ArrayList<>();
        collectAccessors(node, out);
        return out;
    }

    private static void collectAccessors(Json.Node node, List<List<String>> out) {
        if (node instanceof Json.Arr a) {
            for (Json.Node n : a.items()) {
                collectAccessors(n, out);
            }
        } else if (node instanceof Json.Obj o) {
            if ("classInstance".equals(o.getStringOr("_type", "")) && ">".equals(o.getStringOr("type", ""))) {
                out.add(o.getObj("value").getStringArray("path"));
                return;
            }
            for (Json.Node n : o.fields().values()) {
                collectAccessors(n, out);
            }
        }
    }

    /** The function of {@code pkg} named {@code name} in the model (its element named by its signature), or null. */
    private static @com.legend.base.Nullable Json.Obj elementWhoseName(Map<String, Json.Obj> elements, String pkg,
            String name) {
        for (Json.Obj e : elements.values()) {
            if ("function".equals(e.getStringOr("_type", "")) && e.has("package") && pkg.equals(e.getString("package"))
                    && e.getString("name").startsWith(name + "_")) {
                return e;
            }
        }
        return null;
    }

    /** The tables a Data element's relational CSV holds, each {@code schema.table}. */
    private static List<String> sampleTables(Json.Obj data) {
        List<String> out = new ArrayList<>();
        Json.Obj value = objOf(data, "data");
        for (Json.Node t : arrOf(value, "tables").items()) {
            Json.Obj table = (Json.Obj) t;
            out.add(table.getString("schema") + "." + table.getString("table"));
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Elements as protocol
    // ---------------------------------------------------------------------

    /** The model's elements, by path, as protocol (no source information). */
    private static Map<String, Json.Obj> elementsOf(String model) {
        Map<String, Json.Obj> out = new LinkedHashMap<>();
        for (Json.Node n : Json.parseObject(PmcdParser.parseDocument(model, false)).getArr("elements").items()) {
            Json.Obj e = (Json.Obj) n;
            if (!"sectionIndex".equals(e.getStringOr("_type", ""))) {
                out.put(pathOf(e), e);
            }
        }
        return out;
    }

    /** An element's path as the SDLC files it: its package and name -- a function's name its signature's
     *  ({@code TradeFn__Relation_1_}), as the grammar names it. */
    private static String pathOf(Json.Obj element) {
        String name = element.getString("name");
        return element.has("package") && !element.getString("package").isEmpty()
                ? element.getString("package") + "::" + name : name;
    }

    /**
     * The model's text with the changes made: each element a change replaces cut out where the grammar found it, and
     * each change's file after. Text, not the model printed whole: the model printer prints upstream's elements, and a
     * host's own (the tab's {@code DuckDB { }}, which names no path) need not be one.
     */
    private static String withChanges(String model, List<Change> changes) {
        Set<String> replaced = new HashSet<>();
        for (Change c : changes) {
            if ("MODIFY".equals(c.type())) {
                replaced.add(c.path());
            }
        }
        List<int[]> cuts = new ArrayList<>();
        int[] lineStarts = lineStarts(model);
        for (Json.Node n : Json.parseObject(PmcdParser.parseDocument(model, true)).getArr("elements").items()) {
            Json.Obj e = (Json.Obj) n;
            if (!"sectionIndex".equals(e.getStringOr("_type", "")) && replaced.contains(pathOf(e))) {
                Json.Obj at = e.getObj("sourceInformation");
                // the grammar's columns count from 1, the end's inclusive
                cuts.add(new int[] {lineStarts[at.getInt("startLine") - 1] + at.getInt("startColumn") - 1,
                        lineStarts[at.getInt("endLine") - 1] + at.getInt("endColumn")});
            }
        }
        cuts.sort((x, y) -> Integer.compare(y[0], x[0]));
        StringBuilder out = new StringBuilder(model);
        for (int[] cut : cuts) {
            out.delete(cut[0], cut[1]);
        }
        for (Change c : changes) {
            out.append(c.pureCode().startsWith("###") ? "\n" : "\n###Pure\n").append(c.pureCode());
        }
        return out.toString();
    }

    /** Where each line of {@code text} starts. */
    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** {@code element} made, or in place of the model's of its path; nothing when it is null or the same. */
    private static void add(List<Change> changes, Map<String, Json.Obj> elements,
            @com.legend.base.Nullable Json.Obj element) {
        if (element == null) {
            return;
        }
        String path = pathOf(element);
        Json.Obj existing = elements.get(path);
        if (element.equals(existing)) {
            return;
        }
        changes.add(new Change(existing == null ? "CREATE" : "MODIFY", path, file(element)));
    }

    /** The one element a text holds, as protocol. */
    private static Json.Obj element(String text) {
        for (Json.Node n : Json.parseObject(PmcdParser.parseDocument(text, false)).getArr("elements").items()) {
            Json.Obj e = (Json.Obj) n;
            if (!"sectionIndex".equals(e.getStringOr("_type", ""))) {
                return e;
            }
        }
        throw new IllegalStateException("no element in: " + text);
    }

    /**
     * One element as its file holds it: its section's header, then the element, as the model printer prints it -- and
     * read back as itself, or refused: the printer writes a name as upstream's does, unquoted when it is letters and
     * digits, and a few such names Pure reads as words of its own ({@code let}, {@code true}), so a file holding one
     * would not read back.
     */
    private static String file(Json.Obj element) {
        String printed = printed(element);
        String again;
        try {
            again = printed(element(printed));
        } catch (LegendCompileException e) {
            throw refused("the file " + pathOf(element) + " would not read back (" + e.getMessage() + "): a name in it "
                    + "Pure reads as a word of its own -- rename that column first");
        }
        if (!again.equals(printed)) {
            throw new IllegalStateException("the file " + pathOf(element) + " reads back otherwise:\n" + printed
                    + "\n-- read back --\n" + again);
        }
        return printed;
    }

    private static String printed(Json.Obj element) {
        return ModelComposer.model(new Json.Obj(new LinkedHashMap<>(Map.of("_type", Json.str("data"), "elements",
                new Json.Arr(List.of(element))))));
    }

    /** {@code element} as the element {@code name} of {@code pkg}, {@code fields} put in it. */
    private static Json.Obj renamed(Json.Obj element, String pkg, String name, Map<String, Json.Node> fields) {
        LinkedHashMap<String, Json.Node> out = new LinkedHashMap<>(element.fields());
        out.putAll(fields);
        out.put("package", Json.str(pkg));
        out.put("name", Json.str(name));
        return new Json.Obj(out);
    }

    /** {@code o}'s object at {@code key}, empty when it has none. */
    private static Json.Obj objOf(Json.Obj o, String key) {
        Json.Obj v = o.getObjOr(key, null);
        return v == null ? Json.obj() : v;
    }

    /** {@code o}'s array at {@code key}, empty when it has none. */
    private static Json.Arr arrOf(Json.Obj o, String key) {
        Json.Arr v = o.getArrOr(key, null);
        return v == null ? Json.arr() : v;
    }

    private static Json.Obj with(Json.Obj o, String key, Json.Node value) {
        LinkedHashMap<String, Json.Node> out = new LinkedHashMap<>(o.fields());
        out.put(key, value);
        return new Json.Obj(out);
    }

    /** A table's columns' names, in order, as the database names them: a quoted name ({@code "Trade Note"}, its quotes
     *  kept in the protocol, as upstream's are) without its quotes. */
    private static List<String> columnNames(Json.Obj table) {
        List<String> names = new ArrayList<>();
        for (Json.Node c : table.getArr("columns").items()) {
            String name = ((Json.Obj) c).getString("name");
            names.add(name.length() > 1 && name.startsWith("\"") && name.endsWith("\"")
                    ? name.substring(1, name.length() - 1) : name);
        }
        return names;
    }

    private static @com.legend.base.Nullable Json.Obj tableOf(Json.Obj database, String schema, String table) {
        for (Json.Node s : arrOf(database, "schemas").items()) {
            Json.Obj so = (Json.Obj) s;
            if (schema.equals(so.getString("name"))) {
                for (Json.Node t : arrOf(so, "tables").items()) {
                    if (table.equals(((Json.Obj) t).getString("name"))) {
                        return (Json.Obj) t;
                    }
                }
            }
        }
        return null;
    }

    private static Json.Obj withTable(Json.Obj database, String schema, Json.Obj table) {
        List<Json.Node> schemas = new ArrayList<>();
        boolean placed = false;
        for (Json.Node s : arrOf(database, "schemas").items()) {
            Json.Obj so = (Json.Obj) s;
            if (schema.equals(so.getString("name"))) {
                List<Json.Node> tables = new ArrayList<>(arrOf(so, "tables").items());
                tables.add(table);
                so = with(so, "tables", new Json.Arr(tables));
                placed = true;
            }
            schemas.add(so);
        }
        if (!placed) {
            schemas.add(Json.of(Map.of("name", schema, "tables", List.of(table), "tabularFunctions", List.of(),
                    "views", List.of())));
        }
        return with(database, "schemas", new Json.Arr(schemas));
    }

    // ---------------------------------------------------------------------
    // Names
    // ---------------------------------------------------------------------

    /**
     * Upstream's {@code meta::pure::functions::string::makeCamelCase} (legend-engine,
     * core/pure/corefunctions/stringExtension.pure): the parts between {@code _} and spaces, each after the first --
     * or every one, {@code upperFirst} -- led by its first letter upper-cased, the rest lower-cased when it is all upper
     * case and kept as it is when not ({@code TRADE_ID} is {@code tradeId}, {@code customerId} stays).
     */
    static String camelCase(String input, boolean upperFirst) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (String part : input.split("[_ ]")) {
            if (part.isEmpty()) {
                continue;
            }
            String first = part.substring(0, 1);
            String rest = part.substring(1);
            String restCased = rest.equals(rest.toUpperCase(Locale.ROOT)) ? rest.toLowerCase(Locale.ROOT) : rest;
            out.append(upperFirst || index > 0 ? first.toUpperCase(Locale.ROOT) : first.toLowerCase(Locale.ROOT))
                    .append(restCased);
            index += 1;
        }
        return out.toString();
    }

    /** {@code name} as Pure holds a name, made total where upstream's rule is not: each character a name cannot hold
     *  made {@code _}, led by {@code _} when it would start with a digit, {@code _} when nothing is left, followed by
     *  {@code _} when it is a word Pure reads as a literal ({@code true_}). */
    static String identifier(String name) {
        String held = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (held.isEmpty()) {
            return "_";
        }
        if (LITERAL_WORDS.contains(held)) {
            return held + "_";
        }
        return Character.isDigit(held.charAt(0)) ? "_" + held : held;
    }

    /** A name Pure holds unquoted: letters, digits and {@code _}, not led by a digit, and no literal's word. */
    private static boolean isIdentifier(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && !LITERAL_WORDS.contains(name);
    }

    private static String packagePath(String pkg) {
        for (String part : pkg.split("::", -1)) {
            if (!isIdentifier(part)) {
                throw refused("a package is Pure names joined by :: ('" + pkg + "')");
            }
        }
        return pkg;
    }

    /** A name as a Pure quoted name: {@code 'Trade ID'}, its quote and backslash escaped. */
    private static String quoted(String name) {
        return literal(name);
    }

    private static String quotedIfNeeded(String name) {
        return isIdentifier(name) ? name : quoted(name);
    }

    /** A Pure string literal. */
    private static String literal(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "'";
    }

    private static LegendCompileException refused(String message) {
        return new ModelException(LegendCompileException.Phase.MODEL, message);
    }
}
