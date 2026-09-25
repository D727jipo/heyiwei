package com.dsh.calc;

import java.math.BigInteger;

/**
 * 任意精度十进制数: value = (-1)^neg * mant * 10^e10
 *
 * <p>与桌面版 C++ 实现(calc.exe)语义一致:
 * <ul>
 *   <li>除法会判断除数是否为 0, 抛出 {@link CalcException}("除数不能等于0")</li>
 *   <li>整数次方用二进制快速幂, 结果精确、不限大小(只受 maxDigits 安全上限约束)</li>
 *   <li>除不尽/开不尽时按 precision 位有效数字四舍五入, 并标记为近似值</li>
 *   <li>长时间计算通过 {@link #setProgress} 回调实时上报"算到哪了"</li>
 * </ul>
 *
 * <p>整数部分直接使用 Android/Java 平台自带的 {@link BigInteger}, 只在其上实现十进制层。
 */
public final class BigDec {

    public static final BigInteger TEN = BigInteger.TEN;
    private static final BigInteger E18 = new BigInteger("1000000000000000000");
    private static final BigInteger TWO = BigInteger.valueOf(2);

    public boolean neg;
    public BigInteger mant;   // >= 0
    public long e10;
    public boolean approx;    // 是否已被舍入(近似值)
    public int reliable = -1; // 近似值的可靠有效位数(-1 = 未知, 按 precision 计)

    public BigDec() {
        mant = BigInteger.ZERO;
    }

    public BigDec(boolean neg, BigInteger mant, long e10) {
        this.mant = mant;
        this.e10 = e10;
        this.neg = neg;
        if (mant.signum() == 0) {
            this.neg = false;
            this.e10 = 0;
        }
    }

    public static BigDec of(long v) {
        boolean n = v < 0;
        return new BigDec(n, BigInteger.valueOf(Math.abs(v)), 0);
    }

    public boolean isZero() {
        return mant.signum() == 0;
    }

    public BigDec copy() {
        BigDec r = new BigDec(neg, mant, e10);
        r.approx = approx;
        r.reliable = reliable;
        return r;
    }

    // ======================= 实时进度上报 =======================

    /** 进度回调。percent < 0 表示无法给出百分比 */
    public interface Progress {
        void onProgress(String msg, int percent);
    }

    private static Progress gProgress;
    private static long gLastProgressMs;

    public static void setProgress(Progress p) {
        gProgress = p;
    }

    /** 上报进度(内部按 100ms 节流, 避免刷屏; 百分比类消息不受节流限制) */
    static void notifyProgress(String msg, int percent) {
        Progress p = gProgress;
        if (p == null) return;
        long now = System.currentTimeMillis();
        if (percent < 0 && now - gLastProgressMs < 120) return;
        gLastProgressMs = now;
        p.onProgress(msg, percent);
    }

    // ======================= 规范化 / 位数 =======================

    /** 十进制位数(估计值, 误差 ±1); 用于规模检查, 避免 toString 的巨大开销 */
    public static int estDigits(BigInteger m) {
        if (m.signum() == 0) return 1;
        return (int) (m.bitLength() * 0.30102999566398119521) + 1;
    }

    /** 十进制位数(精确) */
    public static int digits(BigInteger m) {
        return m.signum() == 0 ? 1 : m.toString().length();
    }

    /** 去掉尾数末尾的零(有限步: 最多 200*18 个零, 超出也不必再处理, 不影响正确性) */
    public void strip() {
        if (mant.signum() == 0) {
            neg = false;
            e10 = 0;
            return;
        }
        if (mant.mod(TEN).signum() != 0) return;
        int chunks = 0;
        while (chunks < 200) {
            BigInteger[] qr = mant.divideAndRemainder(E18);
            if (qr[1].signum() != 0) break;
            mant = qr[0];
            e10 += 18;
            ++chunks;
        }
        int k = 0;
        while (k < 17) {
            BigInteger[] qr = mant.divideAndRemainder(TEN);
            if (qr[1].signum() != 0) break;
            mant = qr[0];
            ++k;
        }
        e10 += k;
        if (mant.signum() == 0) {
            neg = false;
            e10 = 0;
        }
    }

    /** 是否为整数 */
    public boolean isInteger() {
        if (mant.signum() == 0) return true;
        if (e10 >= 0) return true;
        long k = -e10;
        if (k > 18) return false;
        return mant.mod(TEN.pow((int) k)).signum() == 0;
    }

    /** 显示用: 按普通十进制书写时的字符数(用于安全上限与 UI 提示) */
    public int estPlainChars() {
        if (mant.signum() == 0) return 1;
        int d = estDigits(mant);
        if (e10 > 0) {
            long t = (long) d + e10;
            d = t > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) t;
        }
        return d;
    }

    // ======================= 常量 pi / e (按需计算) =======================

    private static int gPiDigits = -1;
    private static BigDec gPi;
    private static int gEDigits = -1;
    private static BigDec gE;

    /**
     * 圆周率, 给足 digits 位有效数字。
     *
     * <p>用 Brent–Salamin(AGM)迭代, 每轮有效位数翻倍, 所以位数再大也只需要
     * log2(位数) 轮, 不必依赖硬编码的长常量(那样 precision 调大也没用)。
     * 结果缓存在内存里, 同一精度不会重复算。
     */
    public static BigDec pi(int digits) {
        if (digits < 1) digits = 1;
        if (gPi != null && gPiDigits >= digits) return gPi.copy();

        int k = digits + 20;                        // 多算 20 位保护位
        BigInteger S = BigInteger.TEN.pow(k);       // 定点标度: 1 -> S
        BigInteger a = S;                           // a0 = 1
        BigInteger b = isqrt(S.multiply(S).divide(TWO));  // b0 = 1/sqrt(2)
        BigInteger t = S.divide(BigInteger.valueOf(4));   // t0 = 1/4
        BigInteger p = BigInteger.ONE;              // p0 = 1
        int iters = (int) Math.ceil(Math.log((double) k * 3.3219280948873626) / Math.log(2.0)) + 2;
        for (int i = 0; i < iters; ++i) {
            BigInteger an = a.add(b).shiftRight(1);          // an = (a+b)/2
            BigInteger d = a.subtract(an);
            t = t.subtract(p.multiply(d).multiply(d).divide(S));  // t -= p*(a-an)^2
            b = isqrt(a.multiply(b));                        // b = sqrt(a*b)
            a = an;
            p = p.shiftLeft(1);                              // p *= 2
        }
        BigInteger piScaled = a.add(b).pow(2).divide(t.shiftLeft(2));  // (a+b)^2 / (4t)

        BigDec r = new BigDec(false, piScaled, -k);
        r.approx = true;
        gPi = r;
        gPiDigits = digits;
        return r.copy();
    }

    /** 自然常数 e = Σ 1/n!, 给足 digits 位有效数字 */
    public static BigDec e(int digits) {
        if (digits < 1) digits = 1;
        if (gE != null && gEDigits >= digits) return gE.copy();

        int k = digits + 30;                        // 每步截断会累积误差, 保护位给多一点
        BigInteger S = BigInteger.TEN.pow(k);
        BigInteger term = S;                        // 0! 项
        BigInteger sum = S;
        int n = 1;
        while (term.signum() != 0 && n < 10000000) {
            term = term.divide(BigInteger.valueOf(n));
            sum = sum.add(term);
            ++n;
        }
        BigDec r = new BigDec(false, sum, -k);
        r.approx = true;
        gE = r;
        gEDigits = digits;
        return r.copy();
    }

    // ======================= 舍入 =======================

    /** 保留 prec 位有效数字(四舍五入) */
    public static BigDec roundSig(BigDec x, int prec) {
        if (prec < 1) prec = 1;
        BigDec out;
        if (x.mant.signum() == 0) {
            out = x.copy();
            return out;
        }
        int D = digits(x.mant);
        if (D <= prec) {
            out = x.copy();
            if (out.reliable < 0 || out.reliable > prec) out.reliable = prec;
            return out;
        }
        out = truncSig(x, prec);
        if (out.reliable < 0 || out.reliable > prec) out.reliable = prec;
        return out;
    }

    /** 截断到 T 位有效数字(末位四舍五入) */
    private static BigDec truncSig(BigDec x, int T) {
        int D = digits(x.mant);
        if (D <= T || x.mant.signum() == 0) return x.copy();
        int k = D - T;
        BigInteger p = TEN.pow(k);
        BigInteger[] qr = x.mant.divideAndRemainder(p);
        BigInteger q = qr[0];
        if (qr[1].multiply(TWO).compareTo(p) >= 0) q = q.add(BigInteger.ONE);
        BigDec out = new BigDec(x.neg, q, x.e10 + k);
        out.approx = true;
        out.reliable = x.reliable;
        out.strip();
        return out;
    }

    /** 结果可靠位数的保守估计 */
    private static int relMin(BigDec a, BigDec b) {
        int x = a.reliable > 0 ? a.reliable : Integer.MAX_VALUE;
        int y = b.reliable > 0 ? b.reliable : Integer.MAX_VALUE;
        int m = Math.min(x, y);
        return m == Integer.MAX_VALUE ? -1 : m;
    }

    // ======================= 比较 =======================

    public static int cmpAbs(BigDec a, BigDec b) {
        if (a.mant.signum() == 0 && b.mant.signum() == 0) return 0;
        if (a.mant.signum() == 0) return -1;
        if (b.mant.signum() == 0) return 1;
        long pa = (long) digits(a.mant) + a.e10;
        long pb = (long) digits(b.mant) + b.e10;
        if (pa != pb) return pa < pb ? -1 : 1;
        long e = Math.min(a.e10, b.e10);
        BigInteger ma = a.e10 > e ? a.mant.multiply(TEN.pow((int) (a.e10 - e))) : a.mant;
        BigInteger mb = b.e10 > e ? b.mant.multiply(TEN.pow((int) (b.e10 - e))) : b.mant;
        return ma.compareTo(mb);
    }

    public static int cmp(BigDec a, BigDec b) {
        if (a.neg != b.neg) return a.neg ? -1 : 1;
        int c = cmpAbs(a, b);
        return a.neg ? -c : c;
    }

    // ======================= 四则运算 =======================

    public static BigDec add(BigDec a, BigDec b, Limits L) {
        boolean approx = a.approx || b.approx;
        if (a.mant.signum() == 0 && b.mant.signum() == 0) {
            BigDec z = new BigDec();
            z.approx = approx;
            z.reliable = relMin(a, b);
            return z;
        }
        if (a.mant.signum() == 0) return finishSimple(b, a, L);
        if (b.mant.signum() == 0) return finishSimple(a, b, L);

        int estA = estPlainCharsEstimate(a);
        int estB = estPlainCharsEstimate(b);
        int est = Math.max(estA, estB) + 1;
        if (est > L.hardLimit()) throw tooBig(est, L, "加法");

        long e = Math.min(a.e10, b.e10);
        BigInteger ma = a.e10 > e ? a.mant.multiply(TEN.pow((int) (a.e10 - e))) : a.mant;
        BigInteger mb = b.e10 > e ? b.mant.multiply(TEN.pow((int) (b.e10 - e))) : b.mant;

        BigDec r;
        if (a.neg == b.neg) {
            r = new BigDec(a.neg, ma.add(mb), e);
        } else {
            int c = ma.compareTo(mb);
            if (c == 0) {
                BigDec z = new BigDec();
                z.approx = approx;
                z.reliable = relMin(a, b);
                return z;
            }
            r = c > 0 ? new BigDec(a.neg, ma.subtract(mb), e) : new BigDec(b.neg, mb.subtract(ma), e);
        }
        r.strip();
        r.approx = approx;
        r.reliable = relMin(a, b);
        return r.approx ? roundSig(r, L.precision) : r;
    }

    private static BigDec finishSimple(BigDec val, BigDec other, Limits L) {
        BigDec r = val.copy();
        r.approx = r.approx || other.approx;
        r.reliable = relMin(val, other);
        return r.approx ? roundSig(r, L.precision) : r;
    }

    private static int estPlainCharsEstimate(BigDec x) {
        if (x.mant.signum() == 0) return 1;
        int d = estDigits(x.mant);
        if (x.e10 > 0) {
            long t = (long) d + x.e10;
            d = t > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) t;
        }
        return d;
    }

    public static BigDec mul(BigDec a, BigDec b, Limits L) {
        boolean approx = a.approx || b.approx;
        if (a.mant.signum() == 0 || b.mant.signum() == 0) {
            BigDec z = new BigDec();
            z.approx = approx;
            z.reliable = relMin(a, b);
            return z;
        }
        int est = estDigits(a.mant) + estDigits(b.mant) + 1;
        if (est > L.hardLimit()) throw tooBig(est, L, "乘法");
        if (est > 5000) notifyProgress("乘法: " + estDigits(a.mant) + " 位 × " + estDigits(b.mant) + " 位", -1);

        BigDec r = new BigDec(a.neg ^ b.neg, a.mant.multiply(b.mant), a.e10 + b.e10);
        r.approx = approx;
        r.reliable = relMin(a, b);
        r.strip();
        if (r.approx) r = roundSig(r, L.precision);
        checkSize(r, L, "乘法");
        return r;
    }

    public static BigDec div(BigDec a, BigDec b, int prec, Limits L) {
        if (b.mant.signum() == 0) throw new CalcException("除数不能等于0");
        if (a.mant.signum() == 0) {
            BigDec z = new BigDec();
            z.approx = a.approx || b.approx;
            z.reliable = relMin(a, b);
            return z;
        }
        if (prec < 1) prec = 1;

        BigDec x = a, y = b;
        boolean truncated = false;
        int da = digits(x.mant), db = digits(y.mant);
        int T = prec + 20;

        // 精确长除法代价约 (商位数/9)*(除数位数/9); 只有它确实很慢时才截断输入
        double cost = ((double) prec / 9.0 + 1.0) * ((double) db / 9.0 + 1.0);
        if (cost > 5.0e6 && (da > T || db > T)) {
            if (da > T) {
                x = truncSig(x, T);
                truncated = true;
            }
            if (db > T) {
                y = truncSig(y, T);
                truncated = true;
            }
            da = digits(x.mant);
            db = digits(y.mant);
        }

        int target = prec + 5;
        long shift = (long) target - ((long) da - (long) db);
        if (shift < 0) shift = 0;

        // 先按需要的位数试除; 除不尽则扩大移位重试, 直到余数为 0(有限小数, 精确结果)
        long capShift = db > 5000 ? shift : Math.max(4096L, 4L * (prec + 5));
        BigInteger q, r;
        long shiftU = shift;
        for (; ; ) {
            BigInteger num = x.mant.multiply(TEN.pow((int) shiftU));
            BigInteger[] qr = num.divideAndRemainder(y.mant);
            q = qr[0];
            r = qr[1];
            if (r.signum() == 0 || shiftU >= capShift) break;
            long next = shiftU == 0 ? Math.max(target, 1) : shiftU * 2;
            if (next <= shiftU) next = shiftU + 1;
            if (next > capShift) next = capShift;
            if (next <= shiftU) break;
            shiftU = next;
        }
        shift = shiftU;

        boolean exact = r.signum() == 0 && !truncated && !a.approx && !b.approx;
        BigDec res = new BigDec(a.neg ^ b.neg, q, x.e10 - y.e10 - shift);
        res.approx = !exact;
        res.reliable = relMin(a, b);
        res.strip();
        if (!exact) res = roundSig(res, prec);
        checkSize(res, L, "除法");
        return res;
    }

    // ======================= 次方 =======================

    private static boolean expIsOdd(BigDec e) {
        if (e.mant.signum() == 0) return false;
        if (e.e10 > 0) return false;
        return e.mant.mod(BigInteger.TEN).testBit(0);
    }

    /** 转成 long(仅当能精确放入时), 否则返回 null */
    private static Long toLongExact(BigDec x) {
        if (x.mant.signum() == 0) return 0L;
        if (x.e10 < 0) return null;
        String s = x.mant.toString();
        if (s.length() + x.e10 > 19) return null;
        StringBuilder sb = new StringBuilder(s);
        for (long i = 0; i < x.e10; ++i) sb.append('0');
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigIntResult powExact(BigInteger base, long n, double expectedDigits, boolean report) {
        BigInteger r = BigInteger.ONE;
        BigInteger b = base;
        int total = 64 - Long.numberOfLeadingZeros(n);
        int step = 0;
        long m = n;
        while (m != 0) {
            if ((m & 1L) != 0) r = r.multiply(b);
            m >>= 1;
            ++step;
            if (m != 0) {
                int d = estDigits(b);
                int pct = 0;
                if (report) {
                    double frac = expectedDigits > 0 ? (double) d / (double) expectedDigits : 0;
                    if (frac > 1) frac = 1;
                    pct = (int) (Math.pow(frac, 1.585) * 100.0 + 0.5);
                    if (pct > 100) pct = 100;
                    if (pct < 0) pct = 0;
                    notifyProgress("次方: 第 " + step + "/" + total + " 步开始(平方 " + d
                            + " 位 -> 约 " + (d * 2) + " 位), 约完成 " + pct + "%", pct);
                }
                b = b.multiply(b);
            }
        }
        return new BigIntResult(r, total);
    }

    private static final class BigIntResult {
        final BigInteger value;
        final int steps;
        BigIntResult(BigInteger v, int s) {
            value = v;
            steps = s;
        }
    }

    public static BigDec pow(BigDec base, BigDec exp, Limits L) {
        if (!exp.isZero() && !exp.isInteger()) return powNonInteger(base, exp, L);
        if (exp.isZero()) return BigDec.of(1);

        if (base.mant.signum() == 0) {
            if (!exp.neg) {
                BigDec z = new BigDec();
                z.approx = base.approx || exp.approx;
                return z;
            }
            throw new CalcException("除数不能等于0 (0 的负数次幂相当于除以 0)");
        }
        // |base| == 1: 指数可以任意大
        if (base.mant.equals(BigInteger.ONE) && base.e10 == 0) {
            BigDec r = BigDec.of(1);
            if (base.neg && expIsOdd(exp)) r.neg = true;
            r.approx = base.approx || exp.approx;
            return r;
        }

        // |base| 是 10 的幂: 结果不占内存, 只检查位数
        if (base.mant.equals(BigInteger.ONE)) {
            Long n0 = toLongExact(exp);
            double absn0 = Math.abs(toDouble(exp));
            double estDigits0 = 1.0 + (double) base.e10 * absn0;
            if (estDigits0 > L.hardLimit()) throw tooBig((int) Math.min(estDigits0, Integer.MAX_VALUE), L, "次方");
            if (n0 == null) throw new CalcException("指数过大: 结果约 " + sci(estDigits0) + " 位数字, 无法表示");
            BigDec r = new BigDec(base.neg && expIsOdd(exp), BigInteger.ONE, base.e10 * n0);
            r.approx = base.approx || exp.approx;
            r.reliable = relMin(base, exp);
            checkSize(r, L, "次方");
            return r;
        }

        Long nSigned = toLongExact(exp);
        double logm = log10(base.mant);
        double absn = Math.abs(toDouble(exp));
        double estDigits = absn * logm + 1.0;
        if (nSigned == null) {
            throw new CalcException("指数过大: 次方结果约 " + sci(estDigits)
                    + " 位数字, 无法计算 (指数本身已超出 64 位整数范围)");
        }
        if (estDigits > L.hardLimit())
            throw tooBig((int) Math.min(estDigits, Integer.MAX_VALUE), L, "次方");
        long n = Math.abs(nSigned);
        boolean report = estDigits > 5000;
        if (report) notifyProgress("次方: 开始计算 " + brief(base) + "^" + brief(exp)
                + ", 预计结果约 " + sci(estDigits) + " 位数字", 0);

        BigInteger mantPow = powExact(base.mant, n, estDigits, report).value;
        long newE = base.e10 * n;
        BigDec p = new BigDec(base.neg && (n % 2 == 1), mantPow, newE);
        p.approx = base.approx || exp.approx;
        p.reliable = relMin(base, exp);
        p.strip();
        checkSize(p, L, "次方");
        if (report)
            notifyProgress("次方: 计算完成, 结果 " + estPlainCharsEstimate(p) + " 位", 100);

        if (!exp.neg) return p.approx ? roundSig(p, L.precision) : p;
        return div(BigDec.of(1), p, L.precision, L);
    }

    private static BigDec powNonInteger(BigDec base, BigDec exp, Limits L) {
        if (base.mant.signum() == 0) {
            if (!exp.neg) {
                BigDec z = new BigDec();
                z.approx = base.approx || exp.approx;
                return z;
            }
            throw new CalcException("除数不能等于0 (0 的负数次幂相当于除以 0)");
        }
        if (base.neg)
            throw new CalcException("负数的非整数次方在实数范围内无定义 (例如 (-8)^0.5), 请改用整数指数");
        double b = toDouble(base), e = toDouble(exp);
        if (Double.isNaN(b) || Double.isInfinite(b) || Double.isNaN(e) || Double.isInfinite(e))
            throw new CalcException("底数或指数超出可近似范围");
        double r = Math.pow(b, e);
        if (Double.isNaN(r) || Double.isInfinite(r))
            throw new CalcException("次方结果超出可表示范围 (非整数指数走浮点近似)");
        BigDec out = fromDecimalString(String.format(java.util.Locale.US, "%.15g", r));
        out.approx = true;
        return roundSig(out, Math.min(L.precision, 15));  // 浮点只有 ~15 位可靠
    }

    // ======================= 开平方 =======================

    /** floor(sqrt(n)) */
    public static BigInteger isqrt(BigInteger n) {
        if (n.signum() == 0) return BigInteger.ZERO;
        int digits = n.toString().length();
        BigInteger x = TEN.pow((digits + 1) / 2);  // x0 > sqrt(n), 牛顿迭代单调下降
        for (; ; ) {
            BigInteger y = x.add(n.divide(x)).shiftRight(1);
            if (y.compareTo(x) >= 0) return x;
            x = y;
        }
    }

    public static BigDec sqrt(BigDec a, Limits L) {
        if (a.neg) throw new CalcException("负数不能开平方 (√ 的被开方数必须 >= 0)");
        if (a.mant.signum() == 0) {
            BigDec z = new BigDec();
            z.approx = a.approx;
            return z;
        }
        int prec = L.precision;
        int EXACT_LIMIT = 20000;

        BigDec x = a.copy();
        boolean truncated = false;
        if (digits(x.mant) > 5000) notifyProgress("开平方: 被开方数 " + digits(x.mant) + " 位", -1);
        if (digits(x.mant) > EXACT_LIMIT && digits(x.mant) > 2 * prec + 40) {
            x = truncSig(x, 2 * prec + 40);
            truncated = true;
        }

        // value = M * 10^(2q + s)
        long e = x.e10;
        long q = e >= 0 ? e / 2 : -((-e + 1) / 2);
        long s = e - 2 * q;
        BigInteger M = s != 0 ? x.mant.multiply(BigInteger.TEN) : x.mant;

        int dM = digits(M);
        long k = (long) prec + 2 - ((long) (dM + 1) / 2);
        if (k < 0) k = 0;

        BigInteger scaled = M.multiply(TEN.pow((int) (2 * k)));
        BigInteger root = isqrt(scaled);
        boolean exact = !truncated && !a.approx && root.multiply(root).equals(scaled);
        BigDec r = new BigDec(false, root, q - k);
        r.approx = !exact;
        r.reliable = a.reliable;
        r.strip();
        if (r.approx) r = roundSig(r, prec);
        checkSize(r, L, "开平方");
        return r;
    }

    // ======================= 解析 / 输出 =======================

    public static BigDec fromDecimalString(String s) {
        int i = 0;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            ++i;
        }
        StringBuilder digits = new StringBuilder();
        long fracCount = 0;
        boolean seenDot = false;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
            char c = s.charAt(i);
            if (c == '.') {
                if (seenDot) break;
                seenDot = true;
                ++i;
                continue;
            }
            digits.append(c);
            if (seenDot) ++fracCount;
            ++i;
        }
        long expo = 0;
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            ++i;
            boolean eneg = false;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                eneg = s.charAt(i) == '-';
                ++i;
            }
            if (i >= s.length() || !Character.isDigit(s.charAt(i)))
                throw new CalcException("数字格式错误: " + s);
            long v = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                if (v < 400000000000000000L) v = v * 10 + (s.charAt(i) - '0');
                ++i;
            }
            expo = eneg ? -v : v;
        }
        if (i != s.length() || digits.length() == 0) throw new CalcException("数字格式错误: " + s);
        BigDec r = new BigDec(neg, new BigInteger(digits.toString()), expo - fracCount);
        r.strip();
        return r;
    }

    /** 按与桌面版一致的规则格式化 */
    @Override
    public String toString() {
        if (mant.signum() == 0) return "0";
        String ds = mant.toString();
        long ee = e10;
        int z = 0;
        while (ds.length() - z > 1 && ds.charAt(ds.length() - 1 - z) == '0') ++z;
        if (z > 0) {
            ds = ds.substring(0, ds.length() - z);
            ee += z;
        }
        long D = ds.length();
        long P = D + ee;
        String sign = neg ? "-" : "";

        boolean sci;
        if (ee >= 0) {
            sci = approx && (D + ee > 40);  // 近似值不必用成千上万个 0 补齐整数位
        } else {
            sci = (P > 21 || P < -6);
        }
        if (sci) {
            long sciExp = P - 1;
            String out = ds.substring(0, 1);
            if (ds.length() > 1) out += "." + ds.substring(1);
            out += "e" + (sciExp >= 0 ? "+" : "-") + Math.abs(sciExp);
            return sign + out;
        }
        if (ee >= 0) {
            StringBuilder sb = new StringBuilder(ds);
            for (long i = 0; i < ee; ++i) sb.append('0');
            return sign + sb;
        }
        if (P > 0) return sign + ds.substring(0, (int) P) + "." + ds.substring((int) P);
        StringBuilder sb = new StringBuilder(sign).append("0.");
        for (long i = 0; i < -P; ++i) sb.append('0');
        sb.append(ds);
        return sb.toString();
    }

    // ======================= 工具 =======================

    static double log10(BigInteger m) {
        if (m.signum() == 0) return Double.NEGATIVE_INFINITY;
        String s = m.toString();
        int take = Math.min(17, s.length());
        double lead = Double.parseDouble(s.substring(0, take));
        return Math.log10(lead) + (s.length() - take);
    }

    static double toDouble(BigDec x) {
        if (x.mant.signum() == 0) return 0;
        String s = x.mant.toString();
        int take = Math.min(17, s.length());
        double lead = Double.parseDouble(s.substring(0, take));
        int exp = s.length() - take;
        double v = lead * Math.pow(10, (double) exp + (double) x.e10);
        return x.neg ? -v : v;
    }

    private static String brief(BigDec x) {
        if (x.mant.signum() == 0) return "0";
        String s = x.mant.toString();
        if (x.e10 >= 0 && s.length() + x.e10 <= 24) {
            StringBuilder sb = new StringBuilder(s);
            for (long i = 0; i < x.e10; ++i) sb.append('0');
            return x.neg ? "-" + sb : sb.toString();
        }
        if (s.length() > 14) s = s.substring(0, 14) + "...";
        if (x.neg) s = "-" + s;
        if (x.e10 != 0) s += "e" + x.e10;
        return s;
    }

    static String sci(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "极大";
        return String.format(java.util.Locale.US, "%.3e", v);
    }

    private static CalcException tooBig(int est, Limits L, String what) {
        if (!L.force) {
            return new CalcException(what + "结果约 " + sci(est) + " 位数字, 超过安全上限 "
                    + L.maxDigits + " 位 (可用 --force 或 force on 解除)");
        }
        return new CalcException(what + "结果约 " + sci(est)
                + " 位数字, 超过硬上限 100000000 位, 无法输出");
    }

    public static void checkSize(BigDec x, Limits L, String what) {
        if (x.mant.signum() == 0) return;
        int est = x.estPlainChars();
        if (est <= L.hardLimit()) return;
        if (!L.force) {
            throw new CalcException(what + "结果约 " + est + " 位数字, 超过安全上限 " + L.maxDigits
                    + " 位 (可用 --force 或 force on 解除)");
        }
        throw new CalcException(what + "结果约 " + est
                + " 位数字, 超过硬上限 100000000 位, 无法输出");
    }
}
