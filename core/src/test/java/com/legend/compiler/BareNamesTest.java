// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler;

import com.legend.builtin.Pure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one bare-name rule (untangle 4b.2): three tiers, each a declaration. */
class BareNamesTest {

    /** The catalog natives a bare name may denote, every tier's: what the product once flattened for its own
     *  reads ({@code BareNames.catalog}, gone with the ledger's PARK-5, L7 2026-10-10); the tests keep the view. */
    static java.util.List<com.legend.model.NativeFunctionDefinition> catalog(String name) {
        java.util.List<com.legend.model.NativeFunctionDefinition> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var tier : BareNames.catalogTiered(name)) {
            if (seen.add(tier.getKey().fqn())) {
                out.addAll(tier.getValue());
            }
        }
        return out;
    }

    /** Tier 1: an engine handler name resolves to the FQN the platform declares
     *  for it, with no import in sight. */
    @Test
    void anEngineHandlerNameIsServedByTheEngineSurface() {
        List<String> fqns = BareNames.fqns("from");
        assertEquals("meta::pure::mapping::from", fqns.get(0));
        assertTrue(catalog("from").stream()
                .anyMatch(n -> n.qualifiedName().equals("meta::pure::mapping::from")));
    }

    /** Tier 2: a name in several core-group packages carries every one; the
     *  catalog's overloads at each join the candidate set. */
    @Test
    void aCoreGroupNameCarriesEveryPackage() {
        List<String> fqns = BareNames.fqns("map");
        assertTrue(fqns.contains("meta::pure::functions::collection::map"), String.valueOf(fqns));
        assertTrue(fqns.contains("meta::pure::functions::relation::map"), String.valueOf(fqns));
        assertTrue(catalog("map").size() >= 2);
    }

    /** The lite partition: a form's lite-internal declaration is never reachable
     *  from a bare spelling, while the product surface is. */
    @Test
    void theLitePartitionHolds() {
        assertTrue(BareNames.fqns("joinSlot").stream().noneMatch(f -> f.startsWith(Pure.Lite.PKG)),
                String.valueOf(BareNames.fqns("joinSlot")));
        assertTrue(!catalog("joinWithPrefix").isEmpty());
    }

    /** A name the platform declares nowhere is served by no tier: the typer
     *  says "unknown function", never a near miss. */
    @Test
    void anUndeclaredNameHasNoCatalogCandidates() {
        assertEquals(List.of(), catalog("noSuchFunctionAnywhere"));
    }

    /** The catalog refuses a bare name as a declaration lookup. */
    @Test
    void theCatalogIsFqnKeyed() {
        assertThrows(IllegalArgumentException.class, () -> Pure.nativeFunctionsAt("map"));
    }

    /** Tier 3 names only the form's declarations spelled like the call: the
     *  select form owns newTDSRelationAccessor too, and a call spelled select
     *  must not tie with it. */
    @Test
    void aFormsOwnedDeclarationsJoinOnlyUnderTheirOwnName() {
        List<String> fqns = BareNames.fqns("select");
        assertTrue(fqns.contains("meta::pure::functions::relation::select"), String.valueOf(fqns));
        assertTrue(fqns.stream().noneMatch(f -> f.endsWith("newTDSRelationAccessor")), String.valueOf(fqns));
    }

    /** L7 step 4 (2026-10-10): the rule's record at an arity names a tier FQN a native OR a prelude function declares
     *  there; the typer finds a prelude function at the FQN as it finds a native. */
    @Test
    void theRuleNamesPreludeFunctionsAtTheirArity() {
        assertEquals(List.of("meta::relational::functions::database::tableReference"),
                BareNames.referents("tableReference", 3), "a native at the arity");
        assertEquals(List.of(), BareNames.referents("tableReference", 2), "no declaration at the arity");
        int preludeHits = 0;
        for (var el : com.legend.builtin.Prelude.elements()) {
            if (el instanceof com.legend.model.Function f && !Pure.nativeFunctionsAt(f.qualifiedName()).isEmpty()) {
                continue;   // a native too: not the prelude branch
            }
            if (el instanceof com.legend.model.Function f) {
                String simple = f.qualifiedName().substring(f.qualifiedName().lastIndexOf("::") + 2);
                if (BareNames.fqns(simple).contains(f.qualifiedName())) {
                    assertTrue(BareNames.referents(simple, f.parameters().size()).contains(f.qualifiedName()),
                            f.qualifiedName());
                    preludeHits++;
                }
            }
        }
        assertTrue(preludeHits > 0, "the prelude declares functions the rule reaches by a bare spelling");
    }
}
