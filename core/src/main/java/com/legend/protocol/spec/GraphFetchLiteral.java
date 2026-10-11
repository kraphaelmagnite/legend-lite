package com.legend.protocol.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A graph-fetch tree {@code #{Root {a, k(%2024-01-01) {b}, ->subType(@S) {c}}}#}: the root
 * class, its property nodes, and its subtype entries -- the ONE representation text, protocol
 * JSON and the compiler share. The parser builds it from text, {@code ProtocolReader} from the
 * wire; {@code GraphFetchChecker} validates it against the model into the typed tree.
 *
 * <p>A node's call arguments are ordinary expressions (as the grammar parses them); they are the
 * literal's {@link ValueSpecification#children() children}, in tree order, so every generic walk
 * (name resolution, substitution, renaming, folding) reaches them. The graph-position wire
 * spellings of those arguments (ProbeWireShapes "gft pct date param", "gft enum param") are
 * the emitter's and the reader's business, not the tree's.
 *
 * <p>Equality ignores source positions of the root, nodes and subtype entries (as the
 * desugared form it replaced did); arguments compare as themselves.
 */
public record GraphFetchLiteral(
        String className,
        List<Node> subTrees,
        List<SubTypeNode> subTypeTrees,
        @com.legend.base.Nullable com.legend.protocol.SourceInfo pos) implements ValueSpecification {

    public GraphFetchLiteral {
        Objects.requireNonNull(className, "className");
        subTrees = List.copyOf(subTrees);
        subTypeTrees = List.copyOf(subTypeTrees);
    }

    /** A {@code ->subType(@X) { ... }} ENTRY -- the level's subTypeTrees on the wire;
     *  {@code pos} is the class-name span WITHOUT the {@code @}. */
    public record SubTypeNode(String subTypeClass,
                              @com.legend.base.Nullable com.legend.protocol.SourceInfo pos,
                              List<Node> subTrees) {
        public SubTypeNode {
            Objects.requireNonNull(subTypeClass, "subTypeClass");
            subTrees = List.copyOf(subTrees);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SubTypeNode other
                    && subTypeClass.equals(other.subTypeClass()) && subTrees.equals(other.subTrees());
        }

        @Override
        public int hashCode() {
            return Objects.hash(subTypeClass, subTrees);
        }
    }

    /**
     * One property node: name, its token span, call arguments (none for {@code prop()} as for
     * {@code prop}: the wire cannot tell them apart, and nothing may; leg 8 step 2), optional alias
     * ({@code 'nick' : prop}), optional subtype view ({@code prop->subType(@X)}, whose children read
     * X's properties), nested nodes. Subtype ENTRIES ({@code ->subType(@X) {...}}) exist only at the
     * root, as the engine's grammar has it.
     */
    public record Node(String property,
                       @com.legend.base.Nullable com.legend.protocol.SourceInfo pos,
                       List<ValueSpecification> parameters,
                       @com.legend.base.Nullable String alias,
                       @com.legend.base.Nullable String subType,
                       List<Node> subTrees) {
        public Node {
            Objects.requireNonNull(property, "property");
            parameters = List.copyOf(parameters);
            subTrees = List.copyOf(subTrees);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Node other
                    && property.equals(other.property()) && parameters.equals(other.parameters())
                    && Objects.equals(alias, other.alias())
                    && Objects.equals(subType, other.subType()) && subTrees.equals(other.subTrees());
        }

        @Override
        public int hashCode() {
            return Objects.hash(property, parameters, alias, subType, subTrees);
        }
    }

    /** Every node's call arguments, in tree order (depth first; the root's properties before its subtype entries). */
    public List<ValueSpecification> arguments() {
        List<ValueSpecification> out = new ArrayList<>();
        collect(subTrees, out);
        for (SubTypeNode st : subTypeTrees) {
            collect(st.subTrees(), out);
        }
        return out;
    }

    /** This tree with its {@link #arguments()} replaced, same count and order. */
    public GraphFetchLiteral withArguments(List<ValueSpecification> args) {
        int[] next = {0};
        List<Node> nodes = replace(subTrees, args, next);
        List<SubTypeNode> subTypes = new ArrayList<>(subTypeTrees.size());
        for (SubTypeNode st : subTypeTrees) {
            subTypes.add(new SubTypeNode(st.subTypeClass(), st.pos(), replace(st.subTrees(), args, next)));
        }
        if (next[0] != args.size()) {
            throw new IllegalArgumentException("a graph-fetch tree has " + next[0]
                    + " argument(s), given " + args.size());
        }
        return new GraphFetchLiteral(className, nodes, subTypes, pos);
    }

    /** This tree with every class name -- the root's, each subtype entry's and view's -- through {@code rename}. */
    public GraphFetchLiteral withClassNames(java.util.function.UnaryOperator<String> rename) {
        List<SubTypeNode> subTypes = new ArrayList<>(subTypeTrees.size());
        for (SubTypeNode st : subTypeTrees) {
            subTypes.add(new SubTypeNode(rename.apply(st.subTypeClass()), st.pos(), renamed(st.subTrees(), rename)));
        }
        return new GraphFetchLiteral(rename.apply(className), renamed(subTrees, rename), subTypes, pos);
    }

    private static List<Node> renamed(List<Node> nodes, java.util.function.UnaryOperator<String> rename) {
        List<Node> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            out.add(new Node(n.property(), n.pos(), n.parameters(), n.alias(),
                    n.subType() == null ? null : rename.apply(n.subType()), renamed(n.subTrees(), rename)));
        }
        return out;
    }

    private static void collect(List<Node> nodes, List<ValueSpecification> out) {
        for (Node n : nodes) {
            out.addAll(n.parameters());
            collect(n.subTrees(), out);
        }
    }

    private static List<Node> replace(List<Node> nodes, List<ValueSpecification> args, int[] next) {
        List<Node> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) {
            List<ValueSpecification> params = args.subList(next[0], next[0] + n.parameters().size());
            next[0] += n.parameters().size();
            out.add(new Node(n.property(), n.pos(), params, n.alias(), n.subType(),
                    replace(n.subTrees(), args, next)));
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GraphFetchLiteral other
                && className.equals(other.className())
                && subTrees.equals(other.subTrees())
                && subTypeTrees.equals(other.subTypeTrees());
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, subTrees, subTypeTrees);
    }
}
