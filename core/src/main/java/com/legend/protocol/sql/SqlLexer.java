// Copyright 2026 Legend Contributors
// SPDX-License-Identifier: Apache-2.0

package com.legend.protocol.sql;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres SQL text to tokens, following the lexical rules of the Postgres manual (§4.1, "Lexical Structure"):
 * unquoted identifiers and key words (case kept as written; what a name MEANS is the reader's decision),
 * double-quoted identifiers with {@code ""} for a quote, {@code '...'} string constants with {@code ''} for a quote,
 * dollar-quoted strings ({@code $$...$$}, {@code $tag$...$tag$}), numeric constants (Postgres 16's: {@code 0x}, {@code 0o}
 * and {@code 0b} integers, {@code _} between digits), positional parameters ({@code $1}), operators by Postgres's own
 * rule (below), and both comment forms ({@code --} to end of line, nested {@code /* ... * /}).
 *
 * <p>Forms Postgres accepts that this reader does not yet are refused here by name, never read as something else:
 * escape strings ({@code E'...'}), bit and hex strings ({@code B'...'}, {@code X'...'}) and Unicode escapes
 * ({@code U&'...'}).
 */
public final class SqlLexer {

    /** What a token is. Key words are {@link #IDENT}s: Postgres decides key-word-ness by position, and so does the
     *  parser. */
    public enum Kind { IDENT, QUOTED_IDENT, STRING, INTEGER, NUMERIC, PARAM, OP, LPAREN, RPAREN, LBRACKET, RBRACKET,
        COMMA, DOT, SEMICOLON, EOF }

    /** A token: its kind, its text (an identifier's or string's value with quoting removed; an operator's spelling),
     *  and where it starts. */
    public record Token(Kind kind, String text, SqlAst.Pos pos) {

        /** True iff this is the unquoted key word {@code word} (key words match without regard to case). */
        public boolean is(String word) {
            return kind == Kind.IDENT && text.equalsIgnoreCase(word);
        }

        /** True iff this is the operator {@code op}. */
        public boolean isOp(String op) {
            return kind == Kind.OP && text.equals(op);
        }
    }

    /** The characters an operator is made of (Postgres's {@code op_chars}, scan.l). */
    private static final String OPERATOR_CHARS = "~!@#^&|`?+-*/%<>=";

    /** The characters that let a multi-character operator end in {@code +} or {@code -} (scan.l): without one,
     *  {@code =-} is {@code =} then {@code -}, as in {@code x=-1}. */
    private static final String NON_SQL_OPERATOR_CHARS = "~!@#^&|`?%";

    private final String text;
    private int at;
    private int line = 1;
    private int column = 1;

    private SqlLexer(String text) {
        this.text = text;
    }

    /** Every token of {@code text}, ending with one {@link Kind#EOF}. */
    public static List<Token> tokenize(String text) {
        return new SqlLexer(text).all();
    }

    private List<Token> all() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipSpaceAndComments();
            SqlAst.Pos pos = new SqlAst.Pos(line, column);
            if (at >= text.length()) {
                out.add(new Token(Kind.EOF, "", pos));
                return out;
            }
            out.add(next(pos));
        }
    }

    private Token next(SqlAst.Pos pos) {
        char c = text.charAt(at);
        if (c == '\'') {
            return new Token(Kind.STRING, quoted('\'', pos, "string constant"), pos);
        }
        if (c == '"') {
            String name = quoted('"', pos, "quoted identifier");
            if (name.isEmpty()) {
                throw new SqlSyntaxException("zero-length delimited identifier", pos);
            }
            return new Token(Kind.QUOTED_IDENT, name, pos);
        }
        if (c == '$') {
            return dollar(pos);
        }
        if (isDigit(c) || (c == '.' && at + 1 < text.length() && isDigit(text.charAt(at + 1)))) {
            return number(pos);
        }
        if (isIdentStart(c)) {
            return identifier(pos);
        }
        switch (c) {
            case '(' -> {
                advance(1);
                return new Token(Kind.LPAREN, "(", pos);
            }
            case ')' -> {
                advance(1);
                return new Token(Kind.RPAREN, ")", pos);
            }
            case '[' -> {
                advance(1);
                return new Token(Kind.LBRACKET, "[", pos);
            }
            case ']' -> {
                advance(1);
                return new Token(Kind.RBRACKET, "]", pos);
            }
            case ',' -> {
                advance(1);
                return new Token(Kind.COMMA, ",", pos);
            }
            case '.' -> {
                advance(1);
                return new Token(Kind.DOT, ".", pos);
            }
            case ';' -> {
                advance(1);
                return new Token(Kind.SEMICOLON, ";", pos);
            }
            case ':' -> {
                if (at + 1 < text.length() && text.charAt(at + 1) == ':') {
                    advance(2);
                    return new Token(Kind.OP, "::", pos);
                }
                // an array slice's colon (a[1:2]); no operator level reads one, so anywhere else it is a syntax error
                advance(1);
                return new Token(Kind.OP, ":", pos);
            }
            default -> {
                if (OPERATOR_CHARS.indexOf(c) >= 0) {
                    return operator(pos);
                }
                throw new SqlSyntaxException("syntax error at or near \"" + c + "\"", pos);
            }
        }
    }

    /** An operator, by Postgres's rule (scan.l, {@code {operator}}): the longest run of operator characters, cut where
     *  a comment starts inside it, then shorn of a trailing {@code +} or {@code -} unless the run holds a character no
     *  SQL operator has. Which operators mean something is the parser's question. */
    private Token operator(SqlAst.Pos pos) {
        int end = at;
        while (end < text.length() && OPERATOR_CHARS.indexOf(text.charAt(end)) >= 0) {
            end++;
        }
        String run = text.substring(at, end);
        int comment = firstIndex(run.indexOf("/*"), run.indexOf("--"));
        int length = comment > 0 ? comment : run.length();
        char last = run.charAt(length - 1);
        if (length > 1 && (last == '+' || last == '-')) {
            boolean nonSql = false;
            for (int i = 0; i < length - 1; i++) {
                nonSql |= NON_SQL_OPERATOR_CHARS.indexOf(run.charAt(i)) >= 0;
            }
            if (!nonSql) {
                do {
                    length--;
                } while (length > 1 && (run.charAt(length - 1) == '+' || run.charAt(length - 1) == '-'));
            }
        }
        advance(length);
        return new Token(Kind.OP, run.substring(0, length), pos);
    }

    /** The smaller of two {@code indexOf} results, or -1 when both are. */
    private static int firstIndex(int a, int b) {
        if (a < 0) {
            return b;
        }
        return b < 0 ? a : Math.min(a, b);
    }

    /** A quoted run ({@code '...'} or {@code "..."}), a doubled quote standing for one. */
    private String quoted(char quote, SqlAst.Pos pos, String what) {
        StringBuilder value = new StringBuilder();
        advance(1);
        while (true) {
            if (at >= text.length()) {
                throw new SqlSyntaxException("unterminated " + what, pos);
            }
            char c = text.charAt(at);
            if (c == quote) {
                if (at + 1 < text.length() && text.charAt(at + 1) == quote) {
                    value.append(quote);
                    advance(2);
                    continue;
                }
                advance(1);
                return value.toString();
            }
            value.append(c);
            advance(1);
        }
    }

    /** {@code $1} (a positional parameter) or a dollar-quoted string ({@code $$...$$}, {@code $tag$...$tag$}). */
    private Token dollar(SqlAst.Pos pos) {
        int start = at;
        if (at + 1 < text.length() && isDigit(text.charAt(at + 1))) {
            advance(1);
            int digits = at;
            while (at < text.length() && isDigit(text.charAt(at))) {
                advance(1);
            }
            return new Token(Kind.PARAM, text.substring(digits, at), pos);
        }
        int tagEnd = at + 1;
        while (tagEnd < text.length() && isIdentPart(text.charAt(tagEnd)) && text.charAt(tagEnd) != '$') {
            tagEnd++;
        }
        if (tagEnd >= text.length() || text.charAt(tagEnd) != '$') {
            throw new SqlSyntaxException("syntax error at or near \"$\"", pos);
        }
        String tag = text.substring(start, tagEnd + 1);
        int close = text.indexOf(tag, tagEnd + 1);
        if (close < 0) {
            throw new SqlSyntaxException("unterminated dollar-quoted string", pos);
        }
        String value = text.substring(tagEnd + 1, close);
        advance(close + tag.length() - at);
        return new Token(Kind.STRING, value, pos);
    }

    /** A numeric constant by Postgres 16's rules (scan.l; §4.1.2.6): {@code digits}, {@code digits.[digits]},
     *  {@code .digits}, each with an optional exponent, and the integers {@code 0x1F}, {@code 0o17}, {@code 0b101};
     *  one {@code _} may stand between two digits. A constant with a point or an exponent is numeric, one without an
     *  integer. The token's text is the value in plain decimal, its underscores gone. A letter or underscore right
     *  after a constant is an error, as there ({@code 123abc}, {@code 1_}). */
    private Token number(SqlAst.Pos pos) {
        int start = at;
        char c = text.charAt(at);
        char prefix = c == '0' && at + 1 < text.length() ? Character.toLowerCase(text.charAt(at + 1)) : ' ';
        int radix = prefix == 'x' ? 16 : prefix == 'o' ? 8 : prefix == 'b' ? 2 : 10;
        if (radix != 10) {
            advance(2);
            String digits = digits(radix, true);
            if (digits.isEmpty()) {
                String base = radix == 16 ? "hexadecimal" : radix == 8 ? "octal" : "binary";
                throw new SqlSyntaxException("invalid " + base + " integer at or near \"" + text.substring(start, at)
                        + "\"", pos);
            }
            if (at < text.length() && isIdentPart(text.charAt(at))) {
                // 0o19: scan.l reads 0 and then the identifier o19, so it is junk, not 0o1 then 9
                throw new SqlSyntaxException("trailing junk after numeric literal at or near \""
                        + text.substring(start, at + 1) + "\"", pos);
            }
            return new Token(Kind.INTEGER, new BigInteger(digits, radix).toString(), pos);
        }
        StringBuilder value = new StringBuilder(digits(10, false));
        boolean numeric = false;
        if (at < text.length() && text.charAt(at) == '.' && !(at + 1 < text.length() && text.charAt(at + 1) == '.')) {
            numeric = true;
            advance(1);
            value.append('.').append(digits(10, false));
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            int next = at + 1;
            if (next < text.length() && (text.charAt(next) == '+' || text.charAt(next) == '-')) {
                next++;
            }
            if (next < text.length() && isDigit(text.charAt(next))) {
                numeric = true;
                value.append('e').append(text, at + 1, next);
                advance(next - at);
                value.append(digits(10, false));
            }
        }
        junk(start, pos);
        return new Token(numeric ? Kind.NUMERIC : Kind.INTEGER, value.toString(), pos);
    }

    /** A run of digits in {@code radix}, one {@code _} allowed between two of them (and, after a radix prefix,
     *  before the first); the digits without their underscores. */
    private String digits(int radix, boolean afterPrefix) {
        StringBuilder digits = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at);
            boolean underscore = c == '_' && (afterPrefix || !digits.isEmpty()) && at + 1 < text.length()
                    && Character.digit(text.charAt(at + 1), radix) >= 0 && text.charAt(at + 1) < 0x80;
            if (underscore) {
                advance(1);
                c = text.charAt(at);
            } else if (Character.digit(c, radix) < 0 || c >= 0x80) {
                break;
            }
            digits.append(c);
            advance(1);
        }
        return digits.toString();
    }

    /** Postgres's "trailing junk" rule: a constant may not run into an identifier. The message quotes the constant
     *  and the identifier, as Postgres does ({@code "123abc"}; {@code "1e+"} for an exponent with no digits). */
    private void junk(int start, SqlAst.Pos pos) {
        if (at < text.length() && isIdentStart(text.charAt(at))) {
            int end = at;
            while (end < text.length() && isIdentPart(text.charAt(end))) {
                end++;
            }
            if (end == at + 1 && (text.charAt(at) == 'e' || text.charAt(at) == 'E') && end < text.length()
                    && (text.charAt(end) == '+' || text.charAt(end) == '-')) {
                end++;
            }
            throw new SqlSyntaxException("trailing junk after numeric literal at or near \""
                    + text.substring(start, end) + "\"", pos);
        }
    }

    private Token identifier(SqlAst.Pos pos) {
        int start = at;
        while (at < text.length() && isIdentPart(text.charAt(at))) {
            advance(1);
        }
        String word = text.substring(start, at);
        if (at < text.length() && text.charAt(at) == '\'') {
            String prefix = word.toUpperCase(java.util.Locale.ROOT);
            String form = switch (prefix) {
                case "E" -> "escape string constants (E'...')";
                case "B" -> "bit-string constants (B'...')";
                case "X" -> "hexadecimal bit-string constants (X'...')";
                case "N" -> "national character constants (N'...')";
                default -> null;
            };
            if (form != null) {
                throw SqlSyntaxException.unsupported(form + " are not supported yet", pos);
            }
        }
        if (at + 1 < text.length() && text.charAt(at) == '&' && text.charAt(at + 1) == '\''
                && word.equalsIgnoreCase("U")) {
            throw SqlSyntaxException.unsupported("Unicode escape string constants (U&'...') are not supported yet",
                    pos);
        }
        return new Token(Kind.IDENT, word, pos);
    }

    private void skipSpaceAndComments() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                advance(1);
            } else if (text.startsWith("--", at)) {
                while (at < text.length() && text.charAt(at) != '\n') {
                    advance(1);
                }
            } else if (text.startsWith("/*", at)) {
                SqlAst.Pos pos = new SqlAst.Pos(line, column);
                int depth = 0;
                do {
                    if (at >= text.length()) {
                        throw new SqlSyntaxException("unterminated /* comment", pos);
                    }
                    if (text.startsWith("/*", at)) {
                        depth++;
                        advance(2);
                    } else if (text.startsWith("*/", at)) {
                        depth--;
                        advance(2);
                    } else {
                        advance(1);
                    }
                } while (depth > 0);
            } else {
                return;
            }
        }
    }

    private void advance(int n) {
        for (int i = 0; i < n; i++) {
            if (text.charAt(at) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            at++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Postgres: a letter, an underscore, or any character past ASCII begins an identifier. */
    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
    }

    /** Postgres: after the first character, digits and dollar signs too. */
    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c) || c == '$';
    }
}
