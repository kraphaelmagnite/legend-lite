// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.NameResolver;
import com.legend.compiler.ResolvedNames;
import com.legend.platform.CoreFn;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.ColSpec;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;
import com.legend.protocol.spec.Variable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L7 "resolve once", step 3 (2026-10-10; the design note's §2, §11, §12): a call built after the resolver is the
 * same call the parser and the resolver would have made of the same spelling, record and spelling alike.
 */
class CallsTest {

    private static AppliedFunction parsed(String name, List<ValueSpecification> args) {
        return (AppliedFunction) NameResolver.resolveQuery(new AppliedFunction(name, args));
    }

    private static final Variable X = new Variable("x");
    private static final Variable F = new Variable("f");

    @Test
    @DisplayName("a built platform call equals the same call parsed: one known name qualifies, several record")
    void aBuiltCallIsTheParsedCall() {
        AppliedFunction one = Calls.platform("getAll", List.of(X));
        assertEquals(parsed("getAll", List.of(X)), one);
        assertTrue(one.function().contains("::"), "one known name qualifies the spelling: " + one.function());
        assertTrue(one.referents().isEmpty());
        AppliedFunction several = Calls.platform("map", List.of(X, F));
        assertEquals(parsed("map", List.of(X, F)), several);
        assertEquals("map", several.function(), "several candidates keep the bare spelling");
        assertTrue(several.referents().size() > 1, String.valueOf(several.referents()));
        AppliedFunction none = Calls.platform("from", List.of(X, F, new Variable("r")));
        assertEquals(parsed("from", List.of(X, F, new Variable("r"))), none);
        assertEquals(List.of("meta::pure::mapping::from"), none.referents(), "nothing known: the rule's names ride");
    }

    @Test
    @DisplayName("a form, an exact name and a carrier read as the design says")
    void formExactCarrier() {
        AppliedFunction asc = Calls.form(CoreFn.ASC, List.of(new ColSpec("c")));
        assertEquals(parsed(CoreFn.ASC.parseName(), List.of(new ColSpec("c"))), asc);
        AppliedFunction exact = Calls.exact("meta::legend::lite::trustOne", List.of(X));
        assertEquals(List.of("meta::legend::lite::trustOne"), ResolvedNames.referents(exact));
        assertTrue(exact.referents().isEmpty(), "a full name is its own referent; nothing recorded");
        AppliedFunction carrier = Calls.carrier(AppliedFunction.NEW, List.of(X));
        assertTrue(carrier.referents().isEmpty());
        assertEquals(AppliedFunction.NEW, carrier.function());
    }

    @Test
    @DisplayName("a rebuilt call keeps everything at the same arity and is re-answered at another")
    void likeKeepsOrReanswers() {
        AppliedFunction original = parsed("map", List.of(X, F)).asGrouped();
        AppliedFunction same = Calls.like(original, List.of(F, X));
        assertEquals(original.referents(), same.referents());
        assertTrue(same.grouped(), "the written forms ride");
        AppliedFunction other = Calls.like(original, List.of(X));
        assertEquals("map", other.function());
        assertTrue(other.referents().containsAll(original.referents()), "the original's names stay");
        assertTrue(other.referents().containsAll(NameResolver.builtCallNames("map", 1).referents()),
                "joined by the resolver's answer at the new arity");
        assertTrue(other.grouped());
        // a one-name record re-answered at another arity: from(q, r) -> from(q, m, r)
        AppliedFunction two = parsed("from", List.of(X, F));
        AppliedFunction three = Calls.like(two, List.of(X, F, new Variable("r")));
        assertEquals(parsed("from", List.of(X, F, new Variable("r"))).referents(), three.referents());
        assertEquals(original, Calls.like(original, original.parameters()), "an identity rebuild equals the original");
    }

    @Test
    @DisplayName("an operator run is the parser's shape with the record")
    void infixRunIsTheParsersShape() {
        AppliedFunction run = Calls.infixRun("plus", List.of(X, F));
        AppliedFunction parsedRun = (AppliedFunction) NameResolver.resolveQuery(AppliedFunction.infixRun("plus", List.of(X, F)));
        assertEquals(parsedRun, run);
        assertTrue(run.infix());
        assertTrue(run.parameters().get(0) instanceof PureCollection);
    }

    @Test
    @DisplayName("renamed keeps the original's position and forms with the new name's record; among and infixRunAmong carry a row's names")
    void renamedAndAmong() {
        AppliedFunction legacy = parsed("asc", List.of(new Variable("c"))).asGrouped();
        AppliedFunction modern = Calls.renamed(legacy, CoreFn.ASC.parseName(), List.of(new ColSpec("c")));
        assertEquals(parsed(CoreFn.ASC.parseName(), List.of(new ColSpec("c"))).function(), modern.function());
        assertEquals(NameResolver.builtCallNames(CoreFn.ASC.parseName(), 1).referents(), modern.referents());
        assertTrue(modern.grouped(), "the written forms ride");
        AppliedFunction among = Calls.among("concat", List.of("a::b::concat"), List.of(X, F));
        assertEquals(List.of("a::b::concat"), among.referents());
        assertEquals("concat", among.function());
        AppliedFunction run = Calls.infixRunAmong("plus", List.of("a::b::plus"), List.of(X, F));
        assertEquals(List.of("a::b::plus"), run.referents());
        assertTrue(run.infix());
        assertTrue(run.parameters().get(0) instanceof PureCollection);
        assertTrue(Calls.among("dyna", List.of(), List.of(X)).referents().isEmpty(), "an empty row passes through bare");
    }
}
