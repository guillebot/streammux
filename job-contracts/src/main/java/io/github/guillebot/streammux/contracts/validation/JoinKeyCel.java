package io.github.guillebot.streammux.contracts.validation;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationException;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;

import java.util.Map;

/**
 * Compiles a CEL expression used to normalize a join key. The expression is evaluated
 * with a single string variable {@code key} (the extracted JSON field).
 *
 * <p>Example for hyphenated account numbers {@code 7707-938199-1} → {@code 770793819901}
 * (4-digit + 6-digit + last segment padded to 2 digits):
 *
 * <pre>{@code
 * size(key.split("-")) == 3
 *   ? key.split("-")[0] + key.split("-")[1]
 *     + (size(key.split("-")[2]) >= 2 ? key.split("-")[2] : "0" + key.split("-")[2])
 *   : key
 * }</pre>
 */
public final class JoinKeyCel {
    /**
     * Hyphenated billing account: first segment (4) + second (6) + last padded to 2 digits.
     * {@code 7707-938199-1} → {@code 770793819901}.
     */
    public static final String HYPHENATED_ACCOUNT_CEL =
        "size(key.split(\"-\")) == 3 ? key.split(\"-\")[0] + key.split(\"-\")[1] + (size(key.split(\"-\")[2]) >= 2 ? key.split(\"-\")[2] : \"0\" + key.split(\"-\")[2]) : key";

    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
        .addVar("key", SimpleType.STRING)
        .addLibraries(CelExtensions.strings())
        .build();
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder()
        .addLibraries(CelExtensions.strings())
        .build();

    private JoinKeyCel() {}

    public static void validateSyntax(String expression) {
        compile(expression);
    }

    public static Compiled compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("joinKeyCel is required");
        }
        CelAbstractSyntaxTree ast;
        try {
            var compiled = COMPILER.compile(expression.trim());
            if (compiled.hasError()) {
                throw new IllegalArgumentException("joinKeyCel is not valid CEL: " + compiled.getIssueString());
            }
            ast = compiled.getAst();
        } catch (CelValidationException ex) {
            throw new IllegalArgumentException("joinKeyCel is not valid CEL: " + ex.getMessage(), ex);
        }
        CelRuntime.Program program;
        try {
            program = RUNTIME.createProgram(ast);
        } catch (CelEvaluationException ex) {
            throw new IllegalArgumentException("joinKeyCel could not be programmed: " + ex.getMessage(), ex);
        }
        return key -> eval(program, key);
    }

    private static String eval(CelRuntime.Program program, String key) {
        if (key == null) {
            return null;
        }
        Object result;
        try {
            result = program.eval(Map.of("key", key));
        } catch (CelEvaluationException ex) {
            return null;
        }
        if (result == null) {
            return null;
        }
        String value = String.valueOf(result);
        return value.isBlank() ? null : value;
    }

    @FunctionalInterface
    public interface Compiled {
        String apply(String key);
    }
}
