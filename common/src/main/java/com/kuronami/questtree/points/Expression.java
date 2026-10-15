package com.kuronami.questtree.points;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Arithmetic expressions in the Pufferfish's Skills format, so imported experience curves and sources keep working
 * ({@code level^1.5 * 100}, {@code min(dropped_xp, 5) * 2}, {@code stone_like & !silk_touch}).
 * <p>
 * Same grammar as Pufferfish's DefaultParser 0.19 (read from the jar): binary operators by precedence
 * {@code |}(1) {@code &}(2) {@code = <>}(3) {@code >= <= > <}(4) {@code + -}(5) {@code * /}(6) {@code ^}(8, right);
 * unary {@code ! + -}; functions abs, acos, asin, atan, cbrt, ceil, clamp, cos, cosh, exp, floor, fract, log, max,
 * min, mix, mod, round, sign, sin, sinh, sqrt, tan, tanh, trunc; constants e, pi, tau. Booleans are 1 and 0.
 */
public final class Expression {

    private final ToDoubleFunction<Map<String, Double>> eval;
    private final String source;

    private Expression(String source, ToDoubleFunction<Map<String, Double>> eval) {
        this.source = source;
        this.eval = eval;
    }

    /** Parses {@code text}; throws IllegalArgumentException with the position on a syntax error. */
    public static Expression parse(String text) {
        Parser p = new Parser(text);
        ToDoubleFunction<Map<String, Double>> e = p.binary(0);
        p.skip();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("Unexpected '" + text.charAt(p.i) + "' at " + p.i + " in " + text);
        }
        return new Expression(text, e);
    }

    /** Evaluates with the given variables; unknown variables count as 0. */
    public double eval(Map<String, Double> vars) {
        return eval.applyAsDouble(vars);
    }

    @Override
    public String toString() {
        return source;
    }

    private static double bool(boolean b) {
        return b ? 1 : 0;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean eat(String tok) {
            skip();
            if (s.startsWith(tok, i)) {
                i += tok.length();
                return true;
            }
            return false;
        }

        /** Precedence climbing; {@code min} is the lowest precedence allowed at this level. */
        ToDoubleFunction<Map<String, Double>> binary(int min) {
            ToDoubleFunction<Map<String, Double>> left = unary();
            while (true) {
                skip();
                Op op = Op.at(s, i);
                if (op == null || op.prec < min) {
                    return left;
                }
                i += op.token.length();
                ToDoubleFunction<Map<String, Double>> right = binary(op.right ? op.prec : op.prec + 1);
                ToDoubleFunction<Map<String, Double>> l = left;
                left = switch (op) {
                    case OR -> v -> bool(l.applyAsDouble(v) != 0 || right.applyAsDouble(v) != 0);
                    case AND -> v -> bool(l.applyAsDouble(v) != 0 && right.applyAsDouble(v) != 0);
                    case EQ -> v -> bool(l.applyAsDouble(v) == right.applyAsDouble(v));
                    case NE -> v -> bool(l.applyAsDouble(v) != right.applyAsDouble(v));
                    case GE -> v -> bool(l.applyAsDouble(v) >= right.applyAsDouble(v));
                    case LE -> v -> bool(l.applyAsDouble(v) <= right.applyAsDouble(v));
                    case GT -> v -> bool(l.applyAsDouble(v) > right.applyAsDouble(v));
                    case LT -> v -> bool(l.applyAsDouble(v) < right.applyAsDouble(v));
                    case ADD -> v -> l.applyAsDouble(v) + right.applyAsDouble(v);
                    case SUB -> v -> l.applyAsDouble(v) - right.applyAsDouble(v);
                    case MUL -> v -> l.applyAsDouble(v) * right.applyAsDouble(v);
                    case DIV -> v -> l.applyAsDouble(v) / right.applyAsDouble(v);
                    case POW -> v -> Math.pow(l.applyAsDouble(v), right.applyAsDouble(v));
                };
            }
        }

        ToDoubleFunction<Map<String, Double>> unary() {
            if (eat("!")) {
                ToDoubleFunction<Map<String, Double>> e = binary(7);
                return v -> bool(e.applyAsDouble(v) == 0);
            }
            if (eat("-")) {
                ToDoubleFunction<Map<String, Double>> e = binary(7);
                return v -> -e.applyAsDouble(v);
            }
            if (eat("+")) {
                return binary(7);
            }
            return primary();
        }

        ToDoubleFunction<Map<String, Double>> primary() {
            skip();
            if (eat("(")) {
                ToDoubleFunction<Map<String, Double>> e = binary(0);
                expect(")");
                return e;
            }
            int start = i;
            if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')
                        && i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '-' || s.charAt(i + 1) == '+')) {
                    i += 2;
                    while (i < s.length() && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                }
                double n = Double.parseDouble(s.substring(start, i));
                return v -> n;
            }
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("Expected a value at " + i + " in " + s);
            }
            String name = s.substring(start, i);
            if (eat("(")) {
                List<ToDoubleFunction<Map<String, Double>>> args = new ArrayList<>();
                if (!eat(")")) {
                    do {
                        args.add(binary(0));
                    } while (eat(","));
                    expect(")");
                }
                return function(name.toLowerCase(Locale.ROOT), args);
            }
            switch (name) {
                case "e" -> {
                    return v -> Math.E;
                }
                case "pi" -> {
                    return v -> Math.PI;
                }
                case "tau" -> {
                    return v -> Math.PI * 2;
                }
                default -> {
                    return v -> v.getOrDefault(name, 0.0);
                }
            }
        }

        void expect(String tok) {
            if (!eat(tok)) {
                throw new IllegalArgumentException("Expected '" + tok + "' at " + i + " in " + s);
            }
        }

        private ToDoubleFunction<Map<String, Double>> function(String name, List<ToDoubleFunction<Map<String, Double>>> a) {
            int n = a.size();
            java.util.function.Function<Integer, ToDoubleFunction<Map<String, Double>>> arg = k -> {
                if (k >= n) {
                    throw new IllegalArgumentException(name + " needs more arguments in " + s);
                }
                return a.get(k);
            };
            return switch (name) {
                case "min" -> v -> a.stream().mapToDouble(f -> f.applyAsDouble(v)).min().orElse(0);
                case "max" -> v -> a.stream().mapToDouble(f -> f.applyAsDouble(v)).max().orElse(0);
                case "clamp" -> {
                    var x = arg.apply(0);
                    var lo = arg.apply(1);
                    var hi = arg.apply(2);
                    yield v -> Math.max(lo.applyAsDouble(v), Math.min(hi.applyAsDouble(v), x.applyAsDouble(v)));
                }
                case "mix" -> {
                    var x = arg.apply(0);
                    var y = arg.apply(1);
                    var t = arg.apply(2);
                    yield v -> {
                        double tt = t.applyAsDouble(v);
                        return x.applyAsDouble(v) * (1 - tt) + y.applyAsDouble(v) * tt;
                    };
                }
                case "mod" -> {
                    var x = arg.apply(0);
                    var y = arg.apply(1);
                    yield v -> {
                        double m = y.applyAsDouble(v);
                        double r = x.applyAsDouble(v) % m;
                        return r < 0 ? r + Math.abs(m) : r;
                    };
                }
                default -> {
                    var x = arg.apply(0);
                    java.util.function.DoubleUnaryOperator f = switch (name) {
                        case "abs" -> Math::abs;
                        case "acos" -> Math::acos;
                        case "asin" -> Math::asin;
                        case "atan" -> Math::atan;
                        case "cbrt" -> Math::cbrt;
                        case "ceil" -> Math::ceil;
                        case "cos" -> Math::cos;
                        case "cosh" -> Math::cosh;
                        case "exp" -> Math::exp;
                        case "floor" -> Math::floor;
                        case "fract" -> d -> d - Math.floor(d);
                        case "log" -> Math::log;
                        case "round" -> d -> (double) Math.round(d);
                        case "sign" -> Math::signum;
                        case "sin" -> Math::sin;
                        case "sinh" -> Math::sinh;
                        case "sqrt" -> Math::sqrt;
                        case "tan" -> Math::tan;
                        case "tanh" -> Math::tanh;
                        case "trunc" -> d -> d < 0 ? Math.ceil(d) : Math.floor(d);
                        default -> throw new IllegalArgumentException("Unknown function " + name + " in " + s);
                    };
                    yield v -> f.applyAsDouble(x.applyAsDouble(v));
                }
            };
        }
    }

    private enum Op {
        OR("|", 1, false), AND("&", 2, false), NE("<>", 3, false), EQ("=", 3, false), GE(">=", 4, false), LE("<=", 4, false),
        GT(">", 4, false), LT("<", 4, false), ADD("+", 5, false), SUB("-", 5, false), MUL("*", 6, false), DIV("/", 6, false),
        POW("^", 8, true);

        final String token;
        final int prec;
        final boolean right;

        Op(String token, int prec, boolean right) {
            this.token = token;
            this.prec = prec;
            this.right = right;
        }

        /** Longest operator at position i (so "<>" and "<=" win over "<"). */
        static Op at(String s, int i) {
            Op best = null;
            for (Op op : values()) {
                if (s.startsWith(op.token, i) && (best == null || op.token.length() > best.token.length())) {
                    best = op;
                }
            }
            return best;
        }
    }
}
