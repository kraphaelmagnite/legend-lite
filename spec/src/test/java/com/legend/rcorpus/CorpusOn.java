// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.rcorpus;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The relational corpus on another database (2026-10-12, docs/CLOUD_BACKENDS_PCT_2026_10_11.md §20): the host pass
 * on a lane's own sessions, every test or a listed subset (a covering one, tools/census/primitive_cover.py):
 * {@code bazel run //spec:corpus_on_<lane> -- <lane> [<tests file, absolute>]}. It prints the pass's log and exits
 * with JUnit's code; a lane with a fail roster is held to it (MinimalCorpusTest.holdToLaneRoster).
 */
public final class CorpusOn {

    private CorpusOn() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("corpus_on <lane> [<tests file>]");
        }
        System.setProperty("rcorpus.backend", args[0]);
        System.setProperty("legend.judge.mode", "host");
        System.setProperty("legend.exec.engineScanOrder", "true");
        if (args.length == 2) {
            System.setProperty("rcorpus.tests", args[1]);
        }
        Path dir = Files.createTempDirectory("corpus-on");
        Path verdict = dir.resolve("verdict.txt");
        Path log = dir.resolve("pass.log");
        int code = com.legend.tools.junit.JUnitAction.run(new String[] {verdict.toString(), log.toString(), "--",
                "--select-class=com.legend.rcorpus.MinimalCorpusTest", "--fail-if-no-tests"});
        System.out.write(Files.readAllBytes(log));
        System.out.println("[corpus_on] " + String.join(" ", args) + ": JUnit exit " + code + " (log " + log + ")");
        System.exit(code);
    }
}
