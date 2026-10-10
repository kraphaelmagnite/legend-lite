// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.builtin;

import com.legend.base.Nullable;
import com.legend.model.Function;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * THE TWO DECISION POINTS, observable (platform architecture untangle, step 3
 * — a probe, deleted at step 4): the overload set the typer chooses from, and
 * the implementation lowering picks for a resolved function. The compiler and
 * the lowering report each decision here; the provider that compares them with
 * the declaration and implementation tables lives above both (it reads their
 * registries), so it is bound by {@link ServiceLoader}, never imported — the
 * package layering stays acyclic. Nothing is installed unless {@code LL_SHADOW}
 * is set, and every call is then a no-op.
 */
public interface DecisionProbe {

    /** The overload set today's merge returns at {@code fqn}, with the model it was read from. */
    void onOverloads(String fqn, List<Function> today, Object model, Stream<Function> modelFunctions);

    /** What lowering picked for the resolved {@code definition}; {@code today} names the pick. */
    void onPick(@Nullable Function definition, String today);

    /** A language form dispatched on the spelled {@code name}. */
    void onForm(String name, String form);

    /** The candidate declarations the typer considers for a call spelled {@code name}
     *  (a null is a candidate with no source definition — a test convenience). */
    void onCandidates(String name, String source, Stream<@Nullable Function> candidates);

    /** A bare {@code name} served an FQN with declarations through {@code tier}
     *  (ENGINE, CORE, FORM — {@code BareNames.tiered}) at {@code site}
     *  ({@code resolver}: the prelude merge onto the node; {@code merge}: the
     *  overload merge point for a name that reached the typer bare). */
    void onBareTier(String name, String fqn, String tier, String site);

    /** Step 3 probe push (2026-09-27, `program-audit-2026-09-27.md` §E): one row per
     *  resolver decision the reference's rule does not have — {@code tier} is
     *  {@code own-package} (a hit through the element's own package) or
     *  {@code core-first-match} (a name declared in several core-group packages,
     *  the first taken); {@code position} is {@code call} or {@code type};
     *  {@code detail} lists the competing FQNs or says {@code alone}. */
    void onResolverTier(String position, String name, String fqn, String tier, String detail);

    /** The typer's deferred-argument loop accepted candidate {@code accepted} at
     *  {@code index} (> 0) after {@code failed} — the reference's silent-survival
     *  path's upper bound (revision 2 §3.2, FEP:258). */
    void onRetryAccept(String name, String failed, String accepted, int index);

    /** A qualified property or row accessor resolved among {@code n} lifted
     *  overloads at {@code site} — the unconditional-accept count (KR B2); the
     *  site carries {@code :dot} or {@code :call} for the spelling that reached
     *  it (the reference routes a qualified property from the dot spelling only). */
    void onLifted(String site, String name, String fqn, int n);

    /** A collection literal of {@code n} elements whose bound-sum multiplicity
     *  ({@code lo..hi}, hi null = unbounded) differs from the reference's {@code [n]}. */
    void onLiteralMult(int n, int lo, @Nullable Integer hi);

    /** A call that reached the typer with no candidate at all and failed there;
     *  {@code propertyCall} is the dot spelling; {@code site} names the throw. */
    void onUnknownFunction(String name, boolean propertyCall, String site);


    /** The installed probe, or null. The binding (META-INF/services) is a TEST-LANE
     *  resource (//core:shadow_binding on the suites' libraries), never the product
     *  jar's: a planner build carries this interface and the Shadow class, never
     *  the binding, so its loader is empty. */
    @Nullable DecisionProbe INSTALLED = installed();

    private static @Nullable DecisionProbe installed() {
        if (System.getenv("LL_SHADOW") == null) {
            return null;
        }
        java.util.Iterator<DecisionProbe> provided = ServiceLoader.load(DecisionProbe.class).iterator();
        if (!provided.hasNext()) {
            throw new IllegalStateException("LL_SHADOW set, no DecisionProbe bound (//core:shadow_binding)");
        }
        return provided.next();
    }

    static void overloads(String fqn, List<Function> today, Object model, Stream<Function> modelFunctions) {
        if (INSTALLED != null) {
            INSTALLED.onOverloads(fqn, today, model, modelFunctions);
        }
    }

    static void pick(@Nullable Function definition, String today) {
        if (INSTALLED != null) {
            INSTALLED.onPick(definition, today);
        }
    }

    static void form(String name, String form) {
        if (INSTALLED != null) {
            INSTALLED.onForm(name, form);
        }
    }

    /** {@code source}: {@code node} when the resolver left the candidates on the
     *  call, {@code bare} when the typer's bare-name rule supplied them. */
    static void bareTier(String name, String fqn, String tier, String site) {
        if (INSTALLED != null) {
            INSTALLED.onBareTier(name, fqn, tier, site);
        }
    }

    static void candidates(String name, String source, Stream<@Nullable Function> candidates) {
        if (INSTALLED != null) {
            INSTALLED.onCandidates(name, source, candidates);
        }
    }

    // ---- the step 3 probe push (2026-09-27); every wrapper is a one-line call site ----

    static void resolverTier(String position, String name, String fqn, String tier, String detail) {
        if (INSTALLED != null) {
            INSTALLED.onResolverTier(position, name, fqn, tier, detail);
        }
    }

    static void retryAccept(String name, String failed, String accepted, int index) {
        if (INSTALLED != null) {
            INSTALLED.onRetryAccept(name, failed, accepted, index);
        }
    }

    static void lifted(String site, String name, String fqn, int n) {
        if (INSTALLED != null) {
            INSTALLED.onLifted(site, name, fqn, n);
        }
    }

    static void literalMult(int n, int lo, @Nullable Integer hi) {
        if (INSTALLED != null) {
            INSTALLED.onLiteralMult(n, lo, hi);
        }
    }

    static void unknownFunction(String name, boolean propertyCall, String site) {
        if (INSTALLED != null) {
            INSTALLED.onUnknownFunction(name, propertyCall, site);
        }
    }

}
