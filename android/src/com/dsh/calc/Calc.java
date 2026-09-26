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

    // pi / e 不再用硬编码常量(那样 precision 调大也没用), 改为 BigDec.pi()/BigDec.e() 按需计算

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

        BigDec parseFuncArg() {
            if (accept(Tok.LPAREN)) {
                BigDec a = parseExpr();
                expect(Tok.RPAREN, ")");
                return a;
            }
            return parseAtom();
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
                    v = BigDec.sqrt(parseFuncArg(), L);
                } else if ("pow".equals(name)) {
                    expect(Tok.LPAREN, "(");
                    BigDec a = parseExpr();
                    expect(Tok.COMMA, ",");
                    BigDec b = parseExpr();
                    expect(Tok.RPAREN, ")");
                    v = BigDec.pow(a, b, L);
                } else if ("pi".equals(name)) {
                    // 按当前有效位数实时算(位数上限 20000), 不再受硬编码常量长度限制
                    v = BigDec.roundSig(BigDec.pi(L.precision), L.precision);
                } else if ("e".equals(name)) {
                    v = BigDec.roundSig(BigDec.e(L.precision), L.precision);
                } else if ("abs".equals(name)) {
                    v = SciFunc.abs(parseFuncArg());
                } else if ("floor".equals(name)) {
                    v = SciFunc.floor(parseFuncArg());
                } else if ("ceil".equals(name)) {
                    v = SciFunc.ceil(parseFuncArg());
                } else if ("round".equals(name)) {
                    v = SciFunc.round(parseFuncArg());
                } else if ("fact".equals(name)) {
                    v = SciFunc.fact(parseFuncArg(), L);
                } else if ("rad".equals(name)) {
                    v = SciFunc.degToRad(parseFuncArg(), L);
                } else if ("deg".equals(name)) {
                    v = SciFunc.radToDeg(parseFuncArg(), L);
                } else if ("sin".equals(name)) {
                    v = SciFunc.sin(parseFuncArg(), L);
                } else if ("cos".equals(name)) {
                    v = SciFunc.cos(parseFuncArg(), L);
                } else if ("tan".equals(name)) {
                    v = SciFunc.tan(parseFuncArg(), L);
                } else if ("asin".equals(name)) {
                    v = SciFunc.asin(parseFuncArg(), L);
                } else if ("acos".equals(name)) {
                    v = SciFunc.acos(parseFuncArg(), L);
                } else if ("atan".equals(name)) {
                    v = SciFunc.atan(parseFuncArg(), L);
                } else if ("sinh".equals(name)) {
                    v = SciFunc.sinh(parseFuncArg(), L);
                } else if ("cosh".equals(name)) {
                    v = SciFunc.cosh(parseFuncArg(), L);
                } else if ("tanh".equals(name)) {
                    v = SciFunc.tanh(parseFuncArg(), L);
                } else if ("exp".equals(name)) {
                    v = SciFunc.exp(parseFuncArg(), L);
                } else if ("log".equals(name) || "ln".equals(name)) {
                    v = SciFunc.log(parseFuncArg(), L);
                } else if ("log10".equals(name)) {
                    v = SciFunc.log10(parseFuncArg(), L);
                } else if ("log2".equals(name)) {
                    v = SciFunc.log2(parseFuncArg(), L);
                } else {
                    throw new CalcException("未知的名称: " + name +
                        " (可用: sqrt, pow, pi, e, abs, floor, ceil, round, fact," +
                        " sin, cos, tan, asin, acos, atan, sinh, cosh, tanh," +
                        " exp, log, ln, log10, log2, rad, deg)");
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
