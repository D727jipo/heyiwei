package com.dsh.calc;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式词法分析 + 递归下降求值。语法与桌面版 calc.exe 完全一致:
 *
 * <pre>
 * expr  := term (('+'|'-') term)*
 * term  := unary (('*'|'/') unary)*
 * unary := ('+'|'-') unary | power
 * power := atom ('^' unary)?          右结合, 指数可为负数
 * atom  := 数字 | '(' expr ')' | '√' atom | 'sqrt' ( '(' expr ')' | atom )
 *        | 'pow' '(' expr ',' expr ')' | 'pi' | 'e'
 * </pre>
 */
public final class Calc {

    private static final String PI_DIGITS =
            "3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803482534211706798214808651328230664709384460955058223172535940812848111745028410270193852110555964462294895493038196";
    private static final String E_DIGITS =
            "2.71828182845904523536028747135266249775724709369995957496696762772407663035354759457138217852516642742746639193200305992181741359662904357290033429526059563073813232862794349076323382988075319525101901";

    // ============================ 词法 ============================

    private enum Tok {NUM, IDENT, PLUS, MINUS, STAR, SLASH, CARET, LPAREN, RPAREN, COMMA, SQRT, END}

    private static final class Token {
        Tok k = Tok.END;
        String text = "";
        BigDec num;
    }

    static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<Token>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                ++i;
                continue;
            }
            // 数字: 123 / 1.5 / .5 / 1.5e10
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                int j = i;
                boolean dot = false;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    if (s.charAt(j) == '.') {
                        if (dot) break;
                        dot = true;
                    }
                    ++j;
                }
                if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) ++k;
                    if (k < n && Character.isDigit(s.charAt(k))) {
                        while (k < n && Character.isDigit(s.charAt(k))) ++k;
                        j = k;
                    }
                }
                Token t = new Token();
                t.k = Tok.NUM;
                t.text = s.substring(i, j);
                t.num = BigDec.fromDecimalString(t.text);
                out.add(t);
                i = j;
                continue;
            }
            // 标识符
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) ++j;
                Token t = new Token();
                t.k = Tok.IDENT;
                t.text = s.substring(i, j).toLowerCase(java.util.Locale.US);
                // π 是 Unicode 字母, 会走到这里(而不是下面按符号处理的分支),
                // 键盘上的 π 按钮插入的就是这个字符, 统一归一化成 pi
                if ("\u03C0".equals(t.text)) t.text = "pi";
                out.add(t);
                i = j;
                continue;
            }
            switch (c) {
                case '+': out.add(simple(Tok.PLUS, "+")); ++i; continue;
                case '-': out.add(simple(Tok.MINUS, "-")); ++i; continue;
                case '/': out.add(simple(Tok.SLASH, "/")); ++i; continue;
                case '\\': out.add(simple(Tok.SLASH, "\\")); ++i; continue;
                case '^': out.add(simple(Tok.CARET, "^")); ++i; continue;
                case '(': out.add(simple(Tok.LPAREN, "(")); ++i; continue;
                case ')': out.add(simple(Tok.RPAREN, ")")); ++i; continue;
                case ',': out.add(simple(Tok.COMMA, ",")); ++i; continue;
                case '\uFF08': out.add(simple(Tok.LPAREN, "（")); ++i; continue;   // （
                case '\uFF09': out.add(simple(Tok.RPAREN, "）")); ++i; continue;   // ）
                case '\uFF0C': out.add(simple(Tok.COMMA, "，")); ++i; continue;    // ，
                case '\uFF0B': out.add(simple(Tok.PLUS, "＋")); ++i; continue;
                case '\uFF0D': out.add(simple(Tok.MINUS, "－")); ++i; continue;
                case '\u2212': out.add(simple(Tok.MINUS, "−")); ++i; continue;    // −
                case '\u00D7': out.add(simple(Tok.STAR, "×")); ++i; continue;     // ×
                case '\u00F7': out.add(simple(Tok.SLASH, "÷")); ++i; continue;    // ÷
                case '\u221A': out.add(simple(Tok.SQRT, "√")); ++i; continue;     // √
                case '*':
                    if (i + 1 < n && s.charAt(i + 1) == '*') {
                        out.add(simple(Tok.CARET, "**"));
                        i += 2;
                    } else {
                        out.add(simple(Tok.STAR, "*"));
                        ++i;
                    }
                    continue;
                default:
                    break;
            }
            throw new CalcException("无法识别的字符: " + c);
        }
        out.add(simple(Tok.END, ""));
        return out;
    }

    private static Token simple(Tok k, String text) {
        Token t = new Token();
        t.k = k;
        t.text = text;
        return t;
    }

    // ============================ 语法分析与求值 ============================

    private static final class Parser {
        final List<Token> t;
        final Limits L;
        int i = 0;
        int depth = 0;

        Parser(List<Token> toks, Limits lim) {
            t = toks;
            L = lim;
        }

        Token cur() {
            return t.get(i);
        }

        boolean accept(Tok k) {
            if (t.get(i).k == k) {
                ++i;
                return true;
            }
            return false;
        }

        void expect(Tok k, String what) {
            if (!accept(k)) throw new CalcException("语法错误: 期望 \"" + what + "\"");
        }

        void enter() {
            if (++depth > 400) throw new CalcException("表达式嵌套过深(>400 层)");
        }

        BigDec parseExpr() {
            enter();
            BigDec v = parseTerm();
            for (; ; ) {
                if (accept(Tok.PLUS)) {
                    v = BigDec.add(v, parseTerm(), L);
                } else if (accept(Tok.MINUS)) {
                    BigDec rhs = parseTerm();
                    if (!rhs.isZero()) rhs.neg = !rhs.neg;
                    v = BigDec.add(v, rhs, L);
                } else {
                    break;
                }
            }
            --depth;
            return v;
        }

        BigDec parseTerm() {
            BigDec v = parseUnary();
            for (; ; ) {
                if (accept(Tok.STAR)) {
                    v = BigDec.mul(v, parseUnary(), L);
                } else if (accept(Tok.SLASH)) {
                    BigDec d = parseUnary();
                    v = BigDec.div(v, d, L.precision, L);  // 内部含除数为 0 判断
                } else {
                    break;
                }
            }
            return v;
        }

        BigDec parseUnary() {
            if (accept(Tok.PLUS)) return parseUnary();
            if (accept(Tok.MINUS)) {
                BigDec v = parseUnary();
                if (!v.isZero()) v.neg = !v.neg;
                return v;
            }
            return parsePower();
        }

        BigDec parsePower() {
            BigDec b = parseAtom();
            if (accept(Tok.CARET)) {
                BigDec e = parseUnary();
                return BigDec.pow(b, e, L);
            }
            return b;
        }

        BigDec parseAtom() {
            enter();
            BigDec v;
            if (accept(Tok.SQRT)) {
                v = BigDec.sqrt(parseAtom(), L);
            } else if (cur().k == Tok.NUM) {
                v = cur().num;
                ++i;
            } else if (accept(Tok.LPAREN)) {
                v = parseExpr();
                expect(Tok.RPAREN, ")");
            } else if (cur().k == Tok.IDENT) {
                String name = cur().text;
                ++i;
                if ("sqrt".equals(name)) {
                    if (accept(Tok.LPAREN)) {
                        v = parseExpr();
                        expect(Tok.RPAREN, ")");
                    } else {
                        v = parseAtom();
                    }
                    v = BigDec.sqrt(v, L);
                } else if ("pow".equals(name)) {
                    expect(Tok.LPAREN, "(");
                    BigDec a = parseExpr();
                    expect(Tok.COMMA, ",");
                    BigDec b = parseExpr();
                    expect(Tok.RPAREN, ")");
                    v = BigDec.pow(a, b, L);
                } else if ("pi".equals(name)) {
                    v = BigDec.fromDecimalString(PI_DIGITS);
                    v.approx = true;
                    v = BigDec.roundSig(v, L.precision);
                } else if ("e".equals(name)) {
                    v = BigDec.fromDecimalString(E_DIGITS);
                    v.approx = true;
                    v = BigDec.roundSig(v, L.precision);
                } else {
                    throw new CalcException("未知的名称: " + name + " (可用: sqrt, pow, pi, e)");
                }
            } else {
                throw new CalcException("语法错误: 缺少操作数");
            }
            --depth;
            return v;
        }
    }

    /** 求值入口 */
    public static BigDec evaluate(String expr, Limits L) {
        List<Token> toks = tokenize(expr);
        if (toks.size() == 1) throw new CalcException("表达式为空");
        Parser p = new Parser(toks, L);
        BigDec v = p.parseExpr();
        if (p.cur().k != Tok.END) throw new CalcException("语法错误: 表达式末尾有多余内容");
        BigDec.checkSize(v, L, "计算");
        return v;
    }

    /** 与桌面版一致的近似值提示位数 */
    public static int reliableOf(BigDec v, Limits L) {
        return v.reliable > 0 ? v.reliable : L.precision;
    }
}
