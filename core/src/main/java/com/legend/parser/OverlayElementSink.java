// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.parser;

import com.legend.protocol.Protocol;

/**
 * THE ONLY legitimate producer of an opaque element: {@code Protocol.POpaqueElement}, which the compiler's door
 * ({@code ModelFromProtocol}) carries as {@code com.legend.model.OpaqueElementDefinition} (the parser's output is
 * records only, docs/PROTOCOL_PROGRAM_2026_10_05.md leg 8).
 *
 * <p>The opaque carrier exists for one situation: a section owned by an
 * OVERLAY grammar (GRAMMAR_EXTENSIBILITY.md step 3), whose payload core
 * genuinely cannot open because the grammar lives in someone else's jar.
 * Compiling it is the extension's job.
 *
 * <p>It is NOT a hiding place for built-in sections legend-lite has simply
 * not modelled yet. Used that way it converts a loud, accurate parse failure
 * into a silent hole: an element carried opaquely is invisible to
 * {@code findConnection}, to the resolver, and to every compiler phase, so a
 * file "parses" while half of it is unreachable. That mistake was made once —
 * ###Data — and {@code ArchitectureTest.opaqueCarrierIsLockedToTheOverlaySeam}
 * now pins the construction sites so it cannot be made quietly again.
 *
 * <p>Extracting this into its own class is what makes the rule enforceable:
 * the ArchUnit gate can name a single class, rather than trusting a comment
 * inside the section dispatcher.
 */
final class OverlayElementSink implements com.legend.spi.ElementSink {

    private final String sectionName;
    private final java.util.List<Protocol.Element> out;

    OverlayElementSink(String sectionName, java.util.List<Protocol.Element> out) {
        this.sectionName = sectionName;
        this.out = out;
    }

    @Override
    public void accept(String qualifiedName, String protocolJson) {
        // the overlay grammar hands over JSON it owns, no position: the element is carried whole, never opened
        out.add(new Protocol.POpaqueElement(qualifiedName, sectionName, protocolJson, null));
    }
}
