// scifunc.cpp -- 科学计算函数扩展实现
#include "scifunc.h"

#include <cmath>
#include <cstdio>
#include <limits>

namespace calc {

namespace {

constexpr long double PI_L = 3.141592653589793238462643383279502884L;

// 把 BigDec 转成 long double 做近似计算。超大/超小值会安全地饱和到 inf/0。
long double decToLongDouble(const BigDec& x) {
    if (x.mant.isZero()) return 0.0L;
    size_t n = x.mant.d.size();
    size_t take = std::min<size_t>(3, n);
    long double t = 0;
    for (size_t i = 0; i < take; ++i)
        t = t * (long double)BIG_BASE + (long double)x.mant.d[n - 1 - i];
    long double ex = (long double)(n - take) * (long double)BIG_BASE_DIGITS + (long double)x.e10;
    if (ex > 6000.0L) return x.neg ? -std::numeric_limits<long double>::infinity()
                                   :  std::numeric_limits<long double>::infinity();
    if (ex < -6000.0L) return 0.0L;
    long double v = t * std::pow(10.0L, ex);
    return x.neg ? -v : v;
}

// 把 long double 结果转回 BigDec。由于来源是浮点, 结果天然最多约 15~17 位可靠数字。
BigDec fromLongDouble(long double v) {
    if (!std::isfinite((double)v)) throw CalcError("结果超出可表示范围或无定义");
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.17Lg", v);
    BigDec r = decFromString(std::string(buf));
    r.approx = true;
    r.reliable = 15;
    return r;
}

BigDec roundApprox(long double v, const Limits& L) {
    BigDec r = fromLongDouble(v);
    size_t prec = L.precision < 15 ? L.precision : 15;
    return decRoundSig(r, prec);
}

bool toU64NonNegative(const BigDec& x, unsigned long long& out) {
    if (x.neg) return false;
    if (x.mant.isZero()) { out = 0; return true; }
    if (x.e10 < 0) return false;
    std::string s = bigToString(x.mant);
    if (s.size() + (size_t)x.e10 > 20) return false;
    s.append((size_t)x.e10, '0');
    if (s.size() > 20) return false;
    if (s.size() == 20 && s.compare("18446744073709551615") > 0) return false;
    out = std::strtoull(s.c_str(), nullptr, 10);
    return true;
}

// 递归二分求 [a,b] 的乘积 (a*(a+1)*...*b), 比朴素循环快很多。
BigInt factRange(unsigned long long a, unsigned long long b) {
    if (a > b) return bigFromU64(1);
    if (a == b) return bigFromU64(a);
    unsigned long long m = a + (b - a) / 2;
    return bigMul(factRange(a, m), factRange(m + 1, b));
}

}  // namespace

// ============================ 精确函数 ============================

BigDec decAbs(const BigDec& x) {
    if (x.mant.isZero() || !x.neg) return x;
    BigDec r = x;
    r.neg = false;
    r.approx = false;
    r.reliable = -1;
    return r;
}

BigDec decFloor(const BigDec& x) {
    if (x.mant.isZero()) return decZero();
    if (x.e10 >= 0) {
        BigDec r = x;
        r.approx = false;
        r.reliable = -1;
        return r;
    }
    size_t k = (size_t)(-x.e10);
    BigInt p = bigPow10(k);
    BigInt q, rem;
    bigDivMod(x.mant, p, q, rem);
    if (x.neg && !rem.isZero()) q = bigAdd(q, bigFromU64(1));
    BigDec r;
    r.mant = q;
    r.e10 = 0;
    r.neg = x.neg;
    r.approx = false;
    r.reliable = -1;
    decStrip(r);
    return r;
}

BigDec decCeil(const BigDec& x) {
    if (x.mant.isZero()) return decZero();
    if (x.e10 >= 0) {
        BigDec r = x;
        r.approx = false;
        r.reliable = -1;
        return r;
    }
    size_t k = (size_t)(-x.e10);
    BigInt p = bigPow10(k);
    BigInt q, rem;
    bigDivMod(x.mant, p, q, rem);
    if (!x.neg && !rem.isZero()) q = bigAdd(q, bigFromU64(1));
    BigDec r;
    r.mant = q;
    r.e10 = 0;
    r.neg = x.neg;
    r.approx = false;
    r.reliable = -1;
    decStrip(r);
    return r;
}

BigDec decRound(const BigDec& x) {
    if (x.mant.isZero()) return decZero();
    if (x.e10 >= 0) {
        BigDec r = x;
        r.approx = false;
        r.reliable = -1;
        return r;
    }
    size_t k = (size_t)(-x.e10);
    BigInt p = bigPow10(k);
    BigInt q, rem;
    bigDivMod(x.mant, p, q, rem);
    BigInt twice = bigMulSmall(rem, 2);
    if (bigCmp(twice, p) >= 0) q = bigAdd(q, bigFromU64(1));
    BigDec r;
    r.mant = q;
    r.e10 = 0;
    r.neg = x.neg;
    r.approx = false;
    r.reliable = -1;
    decStrip(r);
    return r;
}

BigDec decFact(const BigDec& x, const Limits& L) {
    if (x.neg) throw CalcError("阶乘仅对非负整数有定义");
    unsigned long long n = 0;
    if (!toU64NonNegative(x, n))
        throw CalcError("阶乘参数过大或不是整数");

    // 用 Stirling 公式估算位数, 避免真的去算一个天文数字。
    if (n > 1) {
        long double est = (long double)n * std::log10((long double)n / std::exp(1.0L))
                        + 0.5L * std::log10(2.0L * PI_L * (long double)n);
        if (!std::isfinite((double)est) || est > 1.0e12L)
            throw CalcError("阶乘结果位数过多, 无法计算");
        BigDec estDec;
        estDec.mant = bigFromU64(1);
        estDec.e10 = (long long)est;
        decCheckSize(estDec, L, "阶乘");
    }

    BigDec r;
    if (n <= 1) {
        r = decFromU64(1);
    } else {
        r.mant = factRange(2, n);
        r.e10 = 0;
    }
    r.approx = false;
    r.reliable = -1;
    decCheckSize(r, L, "阶乘");
    return r;
}

// ============================ 角度转换 ============================

BigDec degToRad(const BigDec& x, const Limits& L) {
    return roundApprox(decToLongDouble(x) * PI_L / 180.0L, L);
}

BigDec radToDeg(const BigDec& x, const Limits& L) {
    return roundApprox(decToLongDouble(x) * 180.0L / PI_L, L);
}

// ============================ 三角函数 (弧度制) ============================

BigDec decSin(const BigDec& x, const Limits& L) {
    return roundApprox(std::sin(decToLongDouble(x)), L);
}

BigDec decCos(const BigDec& x, const Limits& L) {
    return roundApprox(std::cos(decToLongDouble(x)), L);
}

BigDec decTan(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    long double c = std::cos(v);
    if (std::fabsl(c) < 1e-18L)
        throw CalcError("tan 在该点无定义 (cos(x)=0)");
    long double r = std::tan(v);
    if (!std::isfinite((double)r))
        throw CalcError("tan 结果超出可表示范围或无定义");
    return roundApprox(r, L);
}

BigDec decAsin(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    if (std::fabsl(v) > 1.0L + 1e-14L)
        throw CalcError("asin 定义域为 [-1, 1]");
    if (v > 1.0L) v = 1.0L;
    if (v < -1.0L) v = -1.0L;
    return roundApprox(std::asin(v), L);
}

BigDec decAcos(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    if (std::fabsl(v) > 1.0L + 1e-14L)
        throw CalcError("acos 定义域为 [-1, 1]");
    if (v > 1.0L) v = 1.0L;
    if (v < -1.0L) v = -1.0L;
    return roundApprox(std::acos(v), L);
}

BigDec decAtan(const BigDec& x, const Limits& L) {
    return roundApprox(std::atan(decToLongDouble(x)), L);
}

// ============================ 双曲函数 ============================

BigDec decSinh(const BigDec& x, const Limits& L) {
    return roundApprox(std::sinh(decToLongDouble(x)), L);
}

BigDec decCosh(const BigDec& x, const Limits& L) {
    return roundApprox(std::cosh(decToLongDouble(x)), L);
}

BigDec decTanh(const BigDec& x, const Limits& L) {
    return roundApprox(std::tanh(decToLongDouble(x)), L);
}

// ============================ 指数与对数 ============================

BigDec decExp(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    long double r = std::exp(v);
    if (!std::isfinite((double)r))
        throw CalcError("exp 结果超出可表示范围");
    return roundApprox(r, L);
}

BigDec decLog(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    if (v <= 0.0L) throw CalcError("对数的真数必须大于 0");
    return roundApprox(std::log(v), L);
}

BigDec decLog10(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    if (v <= 0.0L) throw CalcError("对数的真数必须大于 0");
    return roundApprox(std::log10(v), L);
}

BigDec decLog2(const BigDec& x, const Limits& L) {
    long double v = decToLongDouble(x);
    if (v <= 0.0L) throw CalcError("对数的真数必须大于 0");
    return roundApprox(std::log(v) / std::log(2.0L), L);
}

}  // namespace calc
