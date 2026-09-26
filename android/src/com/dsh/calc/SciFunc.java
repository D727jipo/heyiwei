package com.dsh.calc;

import java.math.BigInteger;
import java.util.Locale;

/**
 * 科学计算函数扩展(与桌面版 scifunc.cpp 语义一致)。
 * 三角/对数/指数函数走 double 近似, 结果可靠位数约 15 位;
 * abs/floor/ceil/round/fact 走精确大数路径。
 */
public final class SciFunc {

    private SciFunc() {}

    // ======================= 精确函数 =======================

    public static BigDec abs(BigDec x) {
        if (x.isZero() || !x.neg) return x;
        BigDec r = x.copy();
        r.neg = false;
        r.approx = false;
        r.reliable = -1;
        return r;
    }

    public static BigDec floor(BigDec x) {
        if (x.isZero()) return BigDec.of(0);
        if (x.e10 >= 0) return copyClean(x);
        BigInteger p = BigDec.TEN.pow((int) (-x.e10));
        BigInteger[] qr = x.mant.divideAndRemainder(p);
        BigInteger q = qr[0];
        if (x.neg && qr[1].signum() != 0) q = q.add(BigInteger.ONE);
        return makeInt(x.neg, q);
    }

    public static BigDec ceil(BigDec x) {
        if (x.isZero()) return BigDec.of(0);
        if (x.e10 >= 0) return copyClean(x);
        BigInteger p = BigDec.TEN.pow((int) (-x.e10));
        BigInteger[] qr = x.mant.divideAndRemainder(p);
        BigInteger q = qr[0];
        if (!x.neg && qr[1].signum() != 0) q = q.add(BigInteger.ONE);
        return makeInt(x.neg, q);
    }

    public static BigDec round(BigDec x) {
        if (x.isZero()) return BigDec.of(0);
        if (x.e10 >= 0) return copyClean(x);
        BigInteger p = BigDec.TEN.pow((int) (-x.e10));
        BigInteger[] qr = x.mant.divideAndRemainder(p);
        BigInteger q = qr[0];
        if (qr[1].shiftLeft(1).compareTo(p) >= 0) q = q.add(BigInteger.ONE);
        return makeInt(x.neg, q);
    }

    public static BigDec fact(BigDec x, Limits L) {
        if (x.neg) throw new CalcException("阶乘仅对非负整数有定义");
        Long n = toNonNegativeLong(x);
        if (n == null) throw new CalcException("阶乘参数过大或不是整数");
        if (n > 1) {
            // 和桌面版一致的位数预检, 避免真的去算一个天文数字
            double est = n * Math.log10(n / Math.E) + 0.5 * Math.log10(2.0 * Math.PI * n);
            if (!Double.isFinite(est) || est > 1.0e12)
                throw new CalcException("阶乘结果位数过多, 无法计算");
            BigDec estDec = new BigDec(false, BigInteger.ONE, (long) est);
            BigDec.checkSize(estDec, L, "阶乘");
        }
        BigInteger r = BigInteger.ONE;
        for (long i = 2; i <= n; ++i) r = r.multiply(BigInteger.valueOf(i));
        BigDec out = new BigDec(false, r, 0);
        out.approx = false;
        out.reliable = -1;
        BigDec.checkSize(out, L, "阶乘");
        return out;
    }

    // ======================= 角度转换 =======================

    public static BigDec degToRad(BigDec x, Limits L) {
        return approx(BigDec.toDouble(x) * Math.PI / 180.0, L);
    }

    public static BigDec radToDeg(BigDec x, Limits L) {
        return approx(BigDec.toDouble(x) * 180.0 / Math.PI, L);
    }

    // ======================= 三角函数(弧度制) =======================

    public static BigDec sin(BigDec x, Limits L) { return approx(Math.sin(BigDec.toDouble(x)), L); }
    public static BigDec cos(BigDec x, Limits L) { return approx(Math.cos(BigDec.toDouble(x)), L); }

    public static BigDec tan(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        double c = Math.cos(v);
        if (Math.abs(c) < 1e-18) throw new CalcException("tan 在该点无定义 (cos(x)=0)");
        double r = Math.tan(v);
        if (Double.isNaN(r) || Double.isInfinite(r))
            throw new CalcException("tan 结果超出可表示范围或无定义");
        return approx(r, L);
    }

    public static BigDec asin(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        if (Math.abs(v) > 1.0 + 1e-14) throw new CalcException("asin 定义域为 [-1, 1]");
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        return approx(Math.asin(v), L);
    }

    public static BigDec acos(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        if (Math.abs(v) > 1.0 + 1e-14) throw new CalcException("acos 定义域为 [-1, 1]");
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        return approx(Math.acos(v), L);
    }

    public static BigDec atan(BigDec x, Limits L) { return approx(Math.atan(BigDec.toDouble(x)), L); }

    // ======================= 双曲函数 =======================

    public static BigDec sinh(BigDec x, Limits L) { return approx(Math.sinh(BigDec.toDouble(x)), L); }
    public static BigDec cosh(BigDec x, Limits L) { return approx(Math.cosh(BigDec.toDouble(x)), L); }
    public static BigDec tanh(BigDec x, Limits L) { return approx(Math.tanh(BigDec.toDouble(x)), L); }

    // ======================= 指数与对数 =======================

    public static BigDec exp(BigDec x, Limits L) {
        double r = Math.exp(BigDec.toDouble(x));
        if (Double.isNaN(r) || Double.isInfinite(r))
            throw new CalcException("exp 结果超出可表示范围");
        return approx(r, L);
    }

    public static BigDec log(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        if (v <= 0) throw new CalcException("对数的真数必须大于 0");
        return approx(Math.log(v), L);
    }

    public static BigDec log10(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        if (v <= 0) throw new CalcException("对数的真数必须大于 0");
        return approx(Math.log10(v), L);
    }

    public static BigDec log2(BigDec x, Limits L) {
        double v = BigDec.toDouble(x);
        if (v <= 0) throw new CalcException("对数的真数必须大于 0");
        return approx(Math.log(v) / Math.log(2.0), L);
    }

    // ======================= 内部工具 =======================

    private static BigDec approx(double v, Limits L) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            throw new CalcException("结果超出可表示范围或无定义");
        BigDec r = BigDec.fromDecimalString(String.format(Locale.US, "%.15g", v));
        r.approx = true;
        r.reliable = 15;
        return BigDec.roundSig(r, Math.min(L.precision, 15));
    }

    private static BigDec copyClean(BigDec x) {
        BigDec r = x.copy();
        r.approx = false;
        r.reliable = -1;
        return r;
    }

    private static BigDec makeInt(boolean neg, BigInteger mant) {
        BigDec r = new BigDec(neg, mant, 0);
        r.approx = false;
        r.reliable = -1;
        r.strip();
        return r;
    }

    private static Long toNonNegativeLong(BigDec x) {
        if (x.isZero()) return 0L;
        if (x.neg || x.e10 < 0) return null;
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
}
