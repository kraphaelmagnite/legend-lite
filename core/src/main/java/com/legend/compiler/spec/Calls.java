// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.compiler.spec;

import com.legend.compiler.NameResolver;
import com.legend.platform.CoreFn;
import com.legend.protocol.spec.AppliedFunction;
import com.legend.protocol.spec.PureCollection;
import com.legend.protocol.spec.ValueSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * THE ONE PLACE A CALL IS BUILT AFTER THE RESOLVER (L7 "resolve once", step 3; the ledger's PARK-5;
 * {@code docs/build-inventory/program/L7_RESOLVE_ONCE_DESIGN_2026_10_10.md} §2, §11). A parsed call gets its record
 * ({@link AppliedFunction#referents}: every full name it can mean) from the name resolver; a call the typer, a
 * desugar, the normalizer or validation builds is born with its record here, so no reader after the resolver
 * works a name out from a spelling. The architecture test keeps every other constructor call out of those
 * packages; the parser and the protocol readers build calls before resolution and are not under the rule.
 */
public final class Calls {

    private Calls() {
    }

    /** A platform function by its bare name: the record is the bare-name rule's names at this arity, the list the
     *  resolver records for the same call parsed ({@link BareNames#referents}). Empty when the rule names nothing
     *  (a form with no signature at this arity): the typer then reports it as it reports a parsed name nothing
     *  declares. A spelling that may be a full name (a dynafunction's lite shim) gets no record and reads as
     *  itself, as every full name does. */
    public static AppliedFunction platform(String name, List<ValueSpecification> args) {
        NameResolver.CallNames named = NameResolver.builtCallNames(name, args.size());
        return new AppliedFunction(named.function(), args, named.referents());
    }

    /** A language form spelled by its parse name ({@code asc}, {@code rename}): the same record a bare call of that
     *  name gets; the site says which form it means. */
    public static AppliedFunction form(CoreFn form, List<ValueSpecification> args) {
        return platform(form.parseName(), args);
    }

    /** A function by its full name (the caller's contract: the spelling holds {@code ::}): the name is the one
     *  referent ({@code ResolvedNames.referents} reads it so); the lite desugars' own functions ({@code Pure.Lite})
     *  and every exact spelling come through here. */
    public static AppliedFunction exact(String fqn, List<ValueSpecification> args) {
        return new AppliedFunction(fqn, args);
    }

    /** A parser carrier ({@link AppliedFunction#NEW} and its kin): a spelling the language has no function for,
     *  read by its own checker; its record is empty by definition. */
    public static AppliedFunction carrier(String spelling, List<ValueSpecification> args) {
        return new AppliedFunction(spelling, args);
    }

    /** An operator run the compiler synthesizes ({@code a + b (+ ...)}): the engine's n-ary carrier with the
     *  {@code infix} marker, as the parser spells it ({@link AppliedFunction#infixRun}), with the record a parsed
     *  run gets (the operator's one-collection overload). */
    public static AppliedFunction infixRun(String op, List<ValueSpecification> operands) {
        NameResolver.CallNames named = NameResolver.builtCallNames(op, 1);
        return new AppliedFunction(named.function(), List.of(new PureCollection(operands)), named.referents(),
                null, false, false, true);
    }

    /** A call rewritten under ANOTHER SPELLING with the original's position and written forms kept (a legacy
     *  {@code asc('COL')} as upstream's modern {@code ascending(~COL)}: {@code SortChecker}): the record is the
     *  rule's for the new name at the new arity; the original's names belong to its old spelling and are dropped. */
    public static AppliedFunction renamed(AppliedFunction original, String name, List<ValueSpecification> args) {
        NameResolver.CallNames named = NameResolver.builtCallNames(name, args.size());
        return new AppliedFunction(named.function(), args, named.referents(), original.pos(),
                original.propertyCall(), original.grouped(), original.infix(), original.island(), original.fControl(),
                original.ownerClass());
    }

    /** A call whose names a REGISTRY ROW gives (the engine's dynafunction table names the Pure declarations a
     *  dynafunction is: {@code RelOpTranslator}): recorded as the resolver records an import-resolved call. An
     *  EMPTY row is deliberate: a dynafunction with no declarations of its own passes through bare, and the
     *  normalizer's second pass resolves it in the user's namespace ({@code ModelNormalizer.resolveSynthesized}),
     *  the one built call that resolves there. */
    public static AppliedFunction among(String name, List<String> referents, List<ValueSpecification> args) {
        return new AppliedFunction(name, args, referents);
    }

    /** {@link #among} as an operator run ({@link #infixRun}). */
    public static AppliedFunction infixRunAmong(String op, List<String> referents, List<ValueSpecification> operands) {
        return new AppliedFunction(op, List.of(new PureCollection(operands)), referents, null, false, false, true);
    }

    /** A call rebuilt with other arguments and everything else of the original kept: its record when the arity is
     *  the original's; the original's names joined by the rule's at the new arity otherwise (a record is at an
     *  arity; the design note's §10). */
    public static AppliedFunction like(AppliedFunction original, List<ValueSpecification> args) {
        if (args.size() == original.parameters().size()) {
            return original.withParameters(args);
        }
        // another arity: the resolver's answer for the spelling at that arity; the original's program candidates
        // (an ambiguous record) stay, joined by the new answer's names (a full name answers itself: no change)
        NameResolver.CallNames named = NameResolver.builtCallNames(original.function(), args.size());
        String fn = original.referents().isEmpty() ? named.function() : original.function();
        List<String> record = original.referents();
        List<String> joined = null;
        for (String fqn : named.referents()) {
            if (!record.contains(fqn) && (joined == null || !joined.contains(fqn))) {
                if (joined == null) {
                    joined = new ArrayList<>(record);
                }
                joined.add(fqn);
            }
        }
        if (joined != null) {
            record = joined;
        }
        return new AppliedFunction(fn, args, record, original.pos(), original.propertyCall(),
                original.grouped(), original.infix(), original.island(), original.fControl(), original.ownerClass());
    }
}
