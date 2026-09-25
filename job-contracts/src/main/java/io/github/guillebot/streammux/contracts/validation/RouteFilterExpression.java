package io.github.guillebot.streammux.contracts.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.StringNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates {@code ROUTE_APP} filter expressions against a JSON payload.
 *
 * <p>Supports boolean composition ({@code &&}, {@code ||}, {@code !}, parentheses),
 * field comparisons ({@code ==}, {@code !=}), membership ({@code in}, {@code not in}),
 * and regular-expression matching ({@code =~}, {@code !~}). Regex operators use
 * {@link java.util.regex.Matcher#find()} semantics (unanchored), matching the
 * {@code regex} operator behaviour of the {@code ALARMS_TO_ZTR} filter engine.
 * Returns {@link ParseResult#parsed()} when the expression is valid filter syntax;
 * callers may fall back to legacy substring matching when {@link ParseResult#parsed()} is false.
 */
public final class RouteFilterExpression {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RouteFilterExpression() {}

    public static ParseResult tryEvaluate(JsonNode payload, String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return ParseResult.notParsed(false);
        }
        try {
            Parser parser = new Parser(filterExpression);
            Node root = parser.parseExpression();
            parser.expectEnd();
            return ParseResult.parsed(root.evaluate(payload));
        } catch (ParseException ex) {
            return ParseResult.notParsed(false);
        }
    }

    /**
     * Parses {@code filterExpression} without evaluating it against a payload, throwing
     * {@link IllegalArgumentException} when the expression is not valid filter syntax.
     * A {@code null} or blank expression is considered invalid here — callers that want
     * to permit blanks must check that separately.
     */
    public static void validateSyntax(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            throw new IllegalArgumentException("filterExpression must not be blank");
        }
        try {
            Parser parser = new Parser(filterExpression);
            Node ignored = parser.parseExpression();
            parser.expectEnd();
        } catch (ParseException ex) {
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    public record ParseResult(boolean parsed, boolean matches) {
        static ParseResult parsed(boolean matches) {
            return new ParseResult(true, matches);
        }

        static ParseResult notParsed(boolean matches) {
            return new ParseResult(false, matches);
        }
    }

    private interface Node {
        boolean evaluate(JsonNode payload);
    }

    private record AndNode(Node left, Node right) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            return left.evaluate(payload) && right.evaluate(payload);
        }
    }

    private record OrNode(Node left, Node right) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            return left.evaluate(payload) || right.evaluate(payload);
        }
    }

    private record NotNode(Node inner) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            return !inner.evaluate(payload);
        }
    }

    private record CompareNode(String path, CompareOperator operator, JsonNode expectedValue) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            JsonNode actualValue = JsonPayloadPath.resolve(payload, path);
            if (actualValue.isMissingNode()) {
                return false;
            }
            boolean equals = actualValue.equals(expectedValue);
            return switch (operator) {
                case EQUALS -> equals;
                case NOT_EQUALS -> !equals;
            };
        }
    }

    private record InNode(String path, List<JsonNode> expectedValues, boolean negated) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            JsonNode actualValue = JsonPayloadPath.resolve(payload, path);
            if (actualValue.isMissingNode()) {
                return false;
            }
            boolean contained = expectedValues.stream().anyMatch(actualValue::equals);
            return negated ? !contained : contained;
        }
    }

    private record RegexNode(String path, Pattern pattern, boolean negated) implements Node {
        @Override
        public boolean evaluate(JsonNode payload) {
            JsonNode actualValue = JsonPayloadPath.resolve(payload, path);
            if (actualValue.isMissingNode() || !actualValue.isValueNode()) {
                return false;
            }
            boolean found = pattern.matcher(actualValue.asText()).find();
            return negated ? !found : found;
        }
    }

    private enum CompareOperator {
        EQUALS,
        NOT_EQUALS
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Node parseExpression() {
            return parseOr();
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (true) {
                skipWhitespace();
                if (!consume("||")) {
                    return left;
                }
                left = new OrNode(left, parseAnd());
            }
        }

        private Node parseAnd() {
            Node left = parseUnary();
            while (true) {
                skipWhitespace();
                if (!consume("&&")) {
                    return left;
                }
                left = new AndNode(left, parseUnary());
            }
        }

        private Node parseUnary() {
            skipWhitespace();
            if (consume("!")) {
                return new NotNode(parseUnary());
            }
            if (consume("(")) {
                Node inner = parseExpression();
                skipWhitespace();
                if (!consume(")")) {
                    throw parseError("expected ')'");
                }
                return inner;
            }
            return parseComparison();
        }

        private Node parseComparison() {
            String path = readPath();
            skipWhitespace();
            if (consume("not in")) {
                return new InNode(path, readJsonArrayValues(), true);
            }
            if (consume("in")) {
                return new InNode(path, readJsonArrayValues(), false);
            }
            if (consume("=~")) {
                return new RegexNode(path, readPattern(), false);
            }
            if (consume("!~")) {
                return new RegexNode(path, readPattern(), true);
            }
            if (consume("==")) {
                return new CompareNode(path, CompareOperator.EQUALS, readValue());
            }
            if (consume("!=")) {
                return new CompareNode(path, CompareOperator.NOT_EQUALS, readValue());
            }
            throw parseError("expected comparison operator after path '" + path + "'");
        }

        private Pattern readPattern() {
            JsonNode value = readValue();
            String regex = value.asText();
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException ex) {
                throw parseError("invalid regex '" + regex + "'");
            }
        }

        private String readPath() {
            skipWhitespace();
            int start = index;
            if (peek() == '/') {
                index++;
                while (index < input.length() && !Character.isWhitespace(input.charAt(index))) {
                    index++;
                }
            } else {
                while (index < input.length() && isPathCharacter(input.charAt(index))) {
                    index++;
                }
            }
            if (start == index) {
                throw parseError("expected field path");
            }
            return input.substring(start, index);
        }

        private JsonNode readValue() {
            skipWhitespace();
            ParsedJsonValue parsed = readJsonValueAt(index);
            if (parsed == null) {
                throw parseError("expected value");
            }
            index = parsed.endIndex();
            return parsed.node();
        }

        private List<JsonNode> readJsonArrayValues() {
            skipWhitespace();
            ParsedJsonValue parsed = readJsonValueAt(index);
            if (parsed == null || !parsed.node().isArray()) {
                throw parseError("expected JSON array after 'in'");
            }
            index = parsed.endIndex();
            List<JsonNode> values = new ArrayList<>();
            parsed.node().forEach(values::add);
            return values;
        }

        private ParsedJsonValue readJsonValueAt(int start) {
            if (start >= input.length()) {
                return null;
            }

            for (int end = start + 1; end <= input.length(); end++) {
                String slice = input.substring(start, end);
                try {
                    JsonNode node = OBJECT_MAPPER.readTree(slice);
                    if (end < input.length() && !isValueBoundary(input.charAt(end))) {
                        continue;
                    }
                    return new ParsedJsonValue(node, end);
                } catch (Exception ignored) {
                    // try a longer slice
                }
            }

            int end = start;
            while (end < input.length() && !isValueBoundary(input.charAt(end))) {
                end++;
            }
            if (end == start) {
                return null;
            }
            String token = input.substring(start, end).trim();
            return new ParsedJsonValue(StringNode.valueOf(unquote(token)), end);
        }

        private boolean isValueBoundary(char character) {
            return Character.isWhitespace(character)
                || character == ')'
                || character == ']'
                || character == '&'
                || character == '|';
        }

        private record ParsedJsonValue(JsonNode node, int endIndex) {}

        private void expectEnd() {
            skipWhitespace();
            if (index < input.length()) {
                throw parseError("unexpected trailing input at position " + index);
            }
        }

        private void skipWhitespace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean consume(String token) {
            skipWhitespace();
            if (input.startsWith(token, index)) {
                if (token.length() > 0 && Character.isLetter(token.charAt(token.length() - 1))
                    && index + token.length() < input.length()
                    && isPathCharacter(input.charAt(index + token.length()))) {
                    return false;
                }
                index += token.length();
                return true;
            }
            return false;
        }

        private char peek() {
            skipWhitespace();
            return index < input.length() ? input.charAt(index) : '\0';
        }

        private ParseException parseError(String message) {
            return new ParseException(message + " at position " + index);
        }
    }

    private static boolean isPathCharacter(char character) {
        return Character.isLetterOrDigit(character)
            || character == '_'
            || character == '.'
            || character == '['
            || character == ']'
            || character == '-'
            || character == '$';
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static final class ParseException extends RuntimeException {
        private ParseException(String message) {
            super(message);
        }
    }
}
