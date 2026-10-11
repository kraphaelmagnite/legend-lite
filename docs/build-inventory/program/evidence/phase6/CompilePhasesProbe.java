package com.legend;

import com.legend.model.ParsedModel;
import java.nio.file.Files;
import java.nio.file.Path;

/** A MEASUREMENT (homework, never committed): the compile of a model text split into its phases, as the compiler
 *  runs them: parse (ElementParser.parse) and build (Compiler.buildModel: resolve, normalize, element compile), each
 *  timed alone; the build's inner split comes from a JFR recording of the same run (jdk.ExecutionSample frames in
 *  NameResolver.resolveAlongside, ModelNormalizer.normalize, PureModelContext.from).
 *  java -cp <core test classpath> com.legend.CompilePhasesProbe <model.pure> <iterations> */
public final class CompilePhasesProbe {
    public static void main(String[] args) throws Exception {
        String text = Files.readString(Path.of(args[0]));
        int n = Integer.parseInt(args[1]);
        Compiler.compileModel(text);   // the boot layer and the JIT, once, outside the clock
        for (int i = 0; i < n; i++) {
            String variant = text + "\n// run " + i + "\n";   // a line apart: no model held already
            long t0 = System.nanoTime();
            ParsedModel parsed = com.legend.parser.ElementParser.parse(variant, com.legend.parser.Dialect.LEGEND_LITE);
            long t1 = System.nanoTime();
            Object ctx = Compiler.buildModel(parsed);
            long t2 = System.nanoTime();
            System.out.printf("run %d: parse %.1f ms, build (resolve+normalize+element compile) %.1f ms, elements %d%n",
                    i, (t1 - t0) / 1e6, (t2 - t1) / 1e6, parsed.elements().size());
            if (ctx == null) throw new IllegalStateException();
        }
    }
}
