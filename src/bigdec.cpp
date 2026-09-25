// bigdec.cpp -- 任意精度十进制数(大数)库实现
#include "bigdec.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>

namespace calc {

// ============================ 实时进度上报 ============================

static ProgressFn g_progress;

void setProgress(ProgressFn fn) { g_progress = std::move(fn); }

// 触发进度的最小规模(limb 数)。600 limb ≈ 5400 位十进制数字, 以下的小运算不值得进度提示
static const size_t PROGRESS_MIN_LIMBS = 600;

static inline void notify(const std::string& msg, bool force = false) {
    if (g_progress) g_progress(msg, force);
}

// 当前正在进行的步骤描述(如 "次方 第 19/20 步: "), 供更深层的乘法进度消息带上上下文
static std::string g_stepCtx;

struct StepCtxGuard {
    explicit StepCtxGuard(const std::string& c) { g_stepCtx = c; }
    ~StepCtxGuard() { g_stepCtx.clear(); }
};

static std::string briefNumber(const BigDec& x) {  // 进度提示里的简短数值描述
    if (x.mant.isZero()) return "0";
    size_t d = bigDigitCount(x.mant);
    if (x.e10 >= 0 && d + (size_t)x.e10 <= 24) {  // 小整数直接原样显示
        std::string s = bigToString(x.mant);
        if (x.e10 > 0) s.append((size_t)x.e10, '0');
        return x.neg ? "-" + s : s;
    }
    std::string s = bigToString(x.mant);
    if (s.size() > 14) s = s.substr(0, 14) + "...";
    if (x.neg) s = "-" + s;
    if (x.e10 != 0) s += "e" + std::to_string(x.e10);
    return s;
}

// ============================ 基础 vec 工具 ============================

static void trimVec(std::vector<uint32_t>& v) {
    while (!v.empty() && v.back() == 0) v.pop_back();
}

static std::vector<uint32_t> addVecRaw(const std::vector<uint32_t>& a,
                                       const std::vector<uint32_t>& b) {
    std::vector<uint32_t> r(std::max(a.size(), b.size()) + 1, 0);
    uint32_t carry = 0;
    for (size_t i = 0; i < r.size(); ++i) {
        uint32_t s = carry;
        if (i < a.size()) s += a[i];
        if (i < b.size()) s += b[i];
        // s <= (1e9-1)+(1e9-1)+1 < 2^32, 安全
        if (s >= BIG_BASE) { s -= BIG_BASE; carry = 1; } else carry = 0;
        r[i] = s;
    }
    trimVec(r);
    return r;
}

static std::vector<uint32_t> subVecRaw(const std::vector<uint32_t>& a,
                                       const std::vector<uint32_t>& b) {  // 要求 a>=b
    std::vector<uint32_t> r(a.size(), 0);
    int64_t borrow = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        int64_t s = (int64_t)a[i] - (i < b.size() ? (int64_t)b[i] : 0) - borrow;
        if (s < 0) { s += BIG_BASE; borrow = 1; } else borrow = 0;
        r[i] = (uint32_t)s;
    }
    trimVec(r);
    return r;
}

// ============================ BigInt: 打印/比较 ============================

std::string bigToString(const BigInt& x) {
    if (x.d.empty()) return "0";
    std::string s;
    s.reserve(x.d.size() * (size_t)BIG_BASE_DIGITS + 1);  // 避免反复扩容
    s = std::to_string(x.d.back());
    size_t total = x.d.size();
    size_t stride = (total >= PROGRESS_MIN_LIMBS) ? std::max<size_t>(1, total / 20) : 0;
    for (size_t i = x.d.size() - 1; i-- > 0;) {
        std::string t = std::to_string(x.d[i]);
        s.append((size_t)BIG_BASE_DIGITS - t.size(), '0');
        s += t;
        if (stride && (total - i) % stride == 0) {
            size_t done = total - i;
            notify("输出结果: 已转换 " + std::to_string(done * 100 / total) + "% (" +
                   std::to_string(s.size()) + " 位)");
        }
    }
    return s;
}

BigInt bigFromString(const std::string& digits) {
    BigInt r;
    size_t n = digits.size();
    size_t i = n;
    while (i > 0) {
        size_t start = (i >= (size_t)BIG_BASE_DIGITS) ? i - BIG_BASE_DIGITS : 0;
        uint32_t v = 0;
        for (size_t k = start; k < i; ++k) v = v * 10u + (uint32_t)(digits[k] - '0');
        r.d.push_back(v);
        i = start;
    }
    r.trim();
    return r;
}

BigInt bigFromU64(unsigned long long v) {
    BigInt r;
    while (v) {
        r.d.push_back((uint32_t)(v % BIG_BASE));
        v /= BIG_BASE;
    }
    return r;
}

size_t bigDigitCount(const BigInt& x) {
    if (x.d.empty()) return 1;
    size_t n = (x.d.size() - 1) * (size_t)BIG_BASE_DIGITS;
    uint32_t t = x.d.back();
    while (t) { ++n; t /= 10; }
    return n;
}

int bigCmp(const BigInt& a, const BigInt& b) {
    if (a.d.size() != b.d.size()) return a.d.size() < b.d.size() ? -1 : 1;
    for (size_t i = a.d.size(); i-- > 0;) {
        if (a.d[i] != b.d[i]) return a.d[i] < b.d[i] ? -1 : 1;
    }
    return 0;
}

// ============================ BigInt: 加减 ============================

BigInt bigAdd(const BigInt& a, const BigInt& b) {
    BigInt r;
    r.d = addVecRaw(a.d, b.d);
    return r;
}

BigInt bigSub(const BigInt& a, const BigInt& b) {
    BigInt r;
    r.d = subVecRaw(a.d, b.d);
    return r;
}

// ============================ BigInt: 乘法(学校算法 + Karatsuba) ============================

static const size_t KARATSUBA_THRESHOLD = 40;

static std::vector<uint32_t> mulSchoolVec(const std::vector<uint32_t>& a,
                                          const std::vector<uint32_t>& b) {
    if (a.empty() || b.empty()) return {};
    std::vector<uint32_t> r(a.size() + b.size(), 0);
    for (size_t i = 0; i < a.size(); ++i) {
        uint64_t carry = 0;
        uint64_t ai = a[i];
        for (size_t j = 0; j < b.size(); ++j) {
            uint64_t cur = (uint64_t)r[i + j] + ai * (uint64_t)b[j] + carry;
            r[i + j] = (uint32_t)(cur % BIG_BASE);
            carry = cur / BIG_BASE;
        }
        r[i + b.size()] = (uint32_t)carry;
    }
    trimVec(r);
    return r;
}

static void addShifted(std::vector<uint32_t>& r, const std::vector<uint32_t>& v, size_t off) {
    if (v.empty()) return;
    if (r.size() < off + v.size()) r.resize(off + v.size(), 0);
    uint64_t carry = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        uint64_t cur = (uint64_t)r[off + i] + v[i] + carry;
        r[off + i] = (uint32_t)(cur % BIG_BASE);
        carry = cur / BIG_BASE;
    }
    size_t pos = off + v.size();
    while (carry) {
        if (pos == r.size()) r.push_back(0);
        uint64_t cur = (uint64_t)r[pos] + carry;
        r[pos] = (uint32_t)(cur % BIG_BASE);
        carry = cur / BIG_BASE;
        ++pos;
    }
}

static void subShifted(std::vector<uint32_t>& r, const std::vector<uint32_t>& v, size_t off) {
    int64_t borrow = 0;
    for (size_t i = 0; i < v.size(); ++i) {
        int64_t cur = (int64_t)r[off + i] - (int64_t)v[i] - borrow;
        if (cur < 0) { cur += BIG_BASE; borrow = 1; } else borrow = 0;
        r[off + i] = (uint32_t)cur;
    }
    size_t pos = off + v.size();
    while (borrow) {
        int64_t cur = (int64_t)r[pos] - borrow;
        if (cur < 0) { cur += BIG_BASE; borrow = 1; } else borrow = 0;
        r[pos] = (uint32_t)cur;
        ++pos;
    }
}

static std::vector<uint32_t> mulVec(const std::vector<uint32_t>& a,
                                    const std::vector<uint32_t>& b, int depth = 0) {
    if (a.empty() || b.empty()) return {};
    if (std::min(a.size(), b.size()) < KARATSUBA_THRESHOLD) return mulSchoolVec(a, b);
    size_t k = std::max(a.size(), b.size()) / 2;
    auto split = [&](const std::vector<uint32_t>& x, std::vector<uint32_t>& lo,
                     std::vector<uint32_t>& hi) {
        size_t m = std::min(k, x.size());
        lo.assign(x.begin(), x.begin() + (ptrdiff_t)m);
        hi.assign(x.begin() + (ptrdiff_t)m, x.end());
    };
    std::vector<uint32_t> a0, a1, b0, b1;
    split(a, a0, a1);
    split(b, b0, b1);
    // 顶层乘法拆成 3 个子乘法, 每个完成时上报一次进度。
    // 次方循环里只对超大乘法上报(否则刷屏); 独立的大乘法则从较低门槛开始报。
    bool report = (depth == 0) &&
                  ((a.size() + b.size()) >= (g_stepCtx.empty() ? 20000 : 60000));
    if (report)
        notify(g_stepCtx + "大数乘法: " + std::to_string(a.size() * 9) + " 位 × " +
               std::to_string(b.size() * 9) + " 位 (Karatsuba 3 个子任务)");
    std::vector<uint32_t> z0 = mulVec(a0, b0, depth + 1);
    if (report) notify(g_stepCtx + "大数乘法: 子任务 1/3 完成 (低位部分)");
    std::vector<uint32_t> z2 = mulVec(a1, b1, depth + 1);
    if (report) notify(g_stepCtx + "大数乘法: 子任务 2/3 完成 (高位部分)");
    std::vector<uint32_t> sa = addVecRaw(a0, a1);
    std::vector<uint32_t> sb = addVecRaw(b0, b1);
    std::vector<uint32_t> z1 = mulVec(sa, sb, depth + 1);
    if (report) notify(g_stepCtx + "大数乘法: 子任务 3/3 完成 (中间部分)");
    subShifted(z1, z0, 0);
    subShifted(z1, z2, 0);
    std::vector<uint32_t> r = z0;
    addShifted(r, z1, k);
    addShifted(r, z2, 2 * k);
    trimVec(r);
    return r;
}

BigInt bigMul(const BigInt& a, const BigInt& b) {
    BigInt r;
    r.d = mulVec(a.d, b.d);
    return r;
}

// ============================ BigInt: 乘/除小数 ============================

BigInt bigMulSmall(const BigInt& a, uint32_t m) {
    BigInt r;
    if (a.d.empty() || m == 0) return r;
    r.d.resize(a.d.size());
    uint64_t carry = 0;
    for (size_t i = 0; i < a.d.size(); ++i) {
        uint64_t cur = (uint64_t)a.d[i] * m + carry;
        r.d[i] = (uint32_t)(cur % BIG_BASE);
        carry = cur / BIG_BASE;
    }
    while (carry) {
        r.d.push_back((uint32_t)(carry % BIG_BASE));
        carry /= BIG_BASE;
    }
    r.trim();
    return r;
}

BigInt bigDivSmall(const BigInt& a, uint32_t v, uint32_t* rem) {
    if (v == 0) throw CalcError("除数不能等于0");
    BigInt q;
    q.d.resize(a.d.size());
    uint64_t r = 0;
    size_t total = a.d.size();
    size_t stride = (total >= PROGRESS_MIN_LIMBS) ? std::max<size_t>(1, total / 20) : 0;
    for (size_t i = total; i-- > 0;) {
        uint64_t cur = r * BIG_BASE + a.d[i];
        q.d[i] = (uint32_t)(cur / v);
        r = cur % v;
        if (stride && (total - i) % stride == 0)
            notify("除法: 已完成 " + std::to_string((total - i) * 100 / total) + "%");
    }
    q.trim();
    if (rem) *rem = (uint32_t)r;
    return q;
}

// ============================ BigInt: 除法(Knuth 算法 D) ============================

void bigDivMod(const BigInt& U, const BigInt& V, BigInt& Q, BigInt& R) {
    if (V.d.empty()) throw CalcError("除数不能等于0");
    if (U.d.empty()) { Q = BigInt(); R = BigInt(); return; }
    if (bigCmp(U, V) < 0) { Q = BigInt(); R = U; return; }
    if (V.d.size() == 1) {
        uint32_t rem = 0;
        Q = bigDivSmall(U, V.d[0], &rem);
        R = bigFromU64(rem);
        return;
    }

    size_t n = V.d.size();
    size_t m = U.d.size() - n;

    // 归一化: 使 V 的最高 limb >= BASE/2, 保证商位估计误差 <= 2
    uint32_t d = (uint32_t)((uint64_t)BIG_BASE / ((uint64_t)V.d[n - 1] + 1));
    BigInt un = bigMulSmall(U, d);
    un.d.resize(U.d.size() + 1, 0);
    BigInt vn = bigMulSmall(V, d);
    if (vn.d.size() != n) throw CalcError("内部错误: 除法归一化失败");

    Q.d.assign(m + 1, 0);
    size_t stride = (m >= PROGRESS_MIN_LIMBS) ? std::max<size_t>(1, m / 20) : 0;
    for (size_t jj = m + 1; jj-- > 0;) {
        size_t j = jj;
        if (stride && (m - j) % stride == 0)
            notify("长除法: 已完成 " + std::to_string((m - j) * 100 / m) + "%");
        uint64_t numer = (uint64_t)un.d[j + n] * BIG_BASE + un.d[j + n - 1];
        uint64_t qhat = numer / vn.d[n - 1];
        uint64_t rhat = numer % vn.d[n - 1];
        while (qhat >= BIG_BASE ||
               qhat * (uint64_t)vn.d[n - 2] > rhat * BIG_BASE + un.d[j + n - 2]) {
            --qhat;
            rhat += vn.d[n - 1];
            if (rhat >= BIG_BASE) break;
        }
        // 乘减: un[j..j+n] -= qhat * vn
        int64_t borrow = 0;
        uint64_t carry = 0;
        for (size_t i = 0; i < n; ++i) {
            uint64_t p = qhat * (uint64_t)vn.d[i] + carry;
            carry = p / BIG_BASE;
            int64_t t = (int64_t)un.d[i + j] - (int64_t)(p % BIG_BASE) - borrow;
            if (t < 0) { t += BIG_BASE; borrow = 1; } else borrow = 0;
            un.d[i + j] = (uint32_t)t;
        }
        int64_t t = (int64_t)un.d[j + n] - (int64_t)carry - borrow;
        if (t < 0) { t += BIG_BASE; borrow = 1; } else borrow = 0;
        un.d[j + n] = (uint32_t)t;

        if (borrow) {  // 估计的商大 1, 加回一次
            --qhat;
            uint64_t c = 0;
            for (size_t i = 0; i < n; ++i) {
                uint64_t s = (uint64_t)un.d[i + j] + (uint64_t)vn.d[i] + c;
                un.d[i + j] = (uint32_t)(s % BIG_BASE);
                c = s / BIG_BASE;
            }
            uint64_t top = (uint64_t)un.d[j + n] + c;
            un.d[j + n] = (uint32_t)(top % BIG_BASE);
        }
        Q.d[j] = (uint32_t)qhat;
    }
    Q.trim();

    un.d.resize(n);
    trimVec(un.d);
    uint32_t rem = 0;
    R = bigDivSmall(un, d, &rem);
}

// ============================ BigInt: 10^k / sqrt / log ============================

BigInt bigPow10(size_t k) {
    BigInt r;
    size_t limbs = k / (size_t)BIG_BASE_DIGITS;
    size_t rest = k % (size_t)BIG_BASE_DIGITS;
    uint32_t p = 1;
    for (size_t i = 0; i < rest; ++i) p *= 10u;
    r.d.assign(limbs, 0);
    r.d.push_back(p);
    r.trim();
    return r;
}

BigInt bigIsqrt(const BigInt& n) {
    if (n.d.empty()) return BigInt();
    if (n.d.size() <= 2) {  // 值 < 1e18, 直接用浮点起步再修正
        unsigned long long v = 0;
        for (size_t i = n.d.size(); i-- > 0;) v = v * BIG_BASE + n.d[i];
        unsigned long long r = (unsigned long long)std::sqrt((long double)v);
        while (r > 0 && r * r > v) --r;
        while ((r + 1) * (r + 1) <= v) ++r;
        return bigFromU64(r);
    }
    size_t digits = bigDigitCount(n);
    BigInt x = bigPow10((digits + 1) / 2);  // x0 > sqrt(n), 牛顿迭代单调下降
    bool report = digits >= PROGRESS_MIN_LIMBS * (size_t)BIG_BASE_DIGITS;
    int iter = 0;
    for (;;) {
        BigInt q, r;
        bigDivMod(n, x, q, r);
        BigInt y = bigDivSmall(bigAdd(x, q), 2, nullptr);
        ++iter;
        if (report)
            notify("开平方: 第 " + std::to_string(iter) + " 次牛顿迭代, 当前根约 " +
                   std::to_string(bigDigitCount(x)) + " 位 (目标 " + std::to_string(digits / 2) + " 位)");
        if (bigCmp(y, x) >= 0) break;
        x = y;
    }
    return x;
}

long double bigLog10(const BigInt& x) {
    if (x.d.empty()) return -std::numeric_limits<long double>::infinity();
    size_t n = x.d.size();
    size_t take = std::min<size_t>(3, n);
    long double t = 0;
    for (size_t i = 0; i < take; ++i) t = t * (long double)BIG_BASE + (long double)x.d[n - 1 - i];
    return std::log10(t) + (long double)(n - take) * (long double)BIG_BASE_DIGITS;
}

// ============================ BigDec 基础 ============================

BigDec decZero() { return BigDec(); }

BigDec decFromU64(unsigned long long v) {
    BigDec r;
    r.mant = bigFromU64(v);
    return r;
}

bool decIsZero(const BigDec& x) { return x.mant.isZero(); }

void decStrip(BigDec& x) {
    if (x.mant.isZero()) { x.neg = false; x.e10 = 0; return; }
    // 统计尾数末尾十进制零的个数: 整 limb 记 9 个, 最后一个非零 limb 内逐位统计
    std::vector<uint32_t>& d = x.mant.d;
    size_t zeros = 0;
    size_t i = 0;
    while (i < d.size() && d[i] == 0) { zeros += (size_t)BIG_BASE_DIGITS; ++i; }
    if (i < d.size()) {
        uint32_t t = d[i];
        while (t % 10u == 0) { ++zeros; t /= 10u; }
    }
    if (zeros == 0) return;
    size_t wholeLimbs = zeros / (size_t)BIG_BASE_DIGITS;
    size_t rest = zeros % (size_t)BIG_BASE_DIGITS;
    if (wholeLimbs) d.erase(d.begin(), d.begin() + (ptrdiff_t)wholeLimbs);
    if (rest) {
        uint32_t p = 1;
        for (size_t k = 0; k < rest; ++k) p *= 10u;
        uint32_t rem = 0;
        x.mant = bigDivSmall(x.mant, p, &rem);
    }
    x.e10 += (long long)zeros;
    x.mant.trim();
    if (x.mant.isZero()) { x.neg = false; x.e10 = 0; }
}

static BigDec truncSig(const BigDec& x, size_t T) {
    size_t D = bigDigitCount(x.mant);
    if (D <= T || x.mant.isZero()) return x;
    size_t k = D - T;
    BigInt p = bigPow10(k);
    BigInt q, r;
    bigDivMod(x.mant, p, q, r);
    if (bigCmp(bigMulSmall(r, 2), p) >= 0) q = bigAdd(q, bigFromU64(1));
    BigDec out = x;
    out.mant = q;
    out.e10 += (long long)k;
    out.approx = true;
    decStrip(out);
    return out;
}

BigDec decRoundSig(const BigDec& x, size_t prec) {
    if (prec < 1) prec = 1;
    BigDec out;
    if (x.mant.isZero()) { out.approx = x.approx; out.reliable = x.reliable; return out; }
    size_t D = bigDigitCount(x.mant);
    if (D <= prec) {
        out = x;
        if (out.reliable < 0 || (size_t)out.reliable > prec) out.reliable = (int)prec;
        return out;
    }
    out = truncSig(x, prec);
    if (out.reliable < 0 || (size_t)out.reliable > prec) out.reliable = (int)prec;
    return out;
}

int decCmpAbs(const BigDec& a, const BigDec& b) {
    if (a.mant.isZero() && b.mant.isZero()) return 0;
    if (a.mant.isZero()) return -1;
    if (b.mant.isZero()) return 1;
    int c = bigCmp(a.mant, b.mant);
    if (c == 0) {
        if (a.e10 == b.e10) return 0;
        return a.e10 < b.e10 ? -1 : 1;
    }
    // 比较数量级: digits(mant)+e10 即小数点左右位数
    long long da = (long long)bigDigitCount(a.mant) + a.e10;
    long long db = (long long)bigDigitCount(b.mant) + b.e10;
    if (da != db) return da < db ? -1 : 1;
    // 同量级, 对齐尾数比较
    long long e = std::min(a.e10, b.e10);
    size_t sa = (size_t)(a.e10 - e), sb = (size_t)(b.e10 - e);
    BigInt ma = sa ? bigMul(a.mant, bigPow10(sa)) : a.mant;
    BigInt mb = sb ? bigMul(b.mant, bigPow10(sb)) : b.mant;
    return bigCmp(ma, mb);
}

int decCmp(const BigDec& a, const BigDec& b) {
    if (a.neg != b.neg) return a.neg ? -1 : 1;
    int c = decCmpAbs(a, b);
    return a.neg ? -c : c;
}

size_t decEstDigits(const BigDec& x) {
    if (x.mant.isZero()) return 1;
    size_t d = bigDigitCount(x.mant);
    if (x.e10 > 0) {
        unsigned long long e = (unsigned long long)x.e10;
        const size_t maxv = std::numeric_limits<size_t>::max();
        if (e > (unsigned long long)(maxv - d)) return maxv;
        d += (size_t)e;
    }
    return d;
}

// ============================ BigDec 解析 ============================

BigDec decFromString(const std::string& s) {
    size_t i = 0;
    bool neg = false;
    if (i < s.size() && (s[i] == '+' || s[i] == '-')) { neg = (s[i] == '-'); ++i; }
    std::string digits;
    long long fracCount = 0;
    bool seenDot = false;
    while (i < s.size() && (isdigit((unsigned char)s[i]) || s[i] == '.')) {
        if (s[i] == '.') {
            if (seenDot) break;
            seenDot = true;
            ++i;
            continue;
        }
        digits += s[i];
        if (seenDot) ++fracCount;
        ++i;
    }
    long long expo = 0;
    if (i < s.size() && (s[i] == 'e' || s[i] == 'E')) {
        ++i;
        bool eneg = false;
        if (i < s.size() && (s[i] == '+' || s[i] == '-')) { eneg = (s[i] == '-'); ++i; }
        if (i >= s.size() || !isdigit((unsigned char)s[i]))
            throw CalcError("数字格式错误: " + s);
        long long e = 0;
        while (i < s.size() && isdigit((unsigned char)s[i])) {
            if (e < 4000000000000000000LL) e = e * 10 + (s[i] - '0');
            ++i;
        }
        expo = eneg ? -e : e;
    }
    if (i != s.size() || digits.empty()) throw CalcError("数字格式错误: " + s);
    BigDec r;
    r.mant = bigFromString(digits);
    r.neg = neg;
    r.e10 = expo - fracCount;
    decStrip(r);
    return r;
}

// ============================ 资源上限 ============================

static size_t hardLimit(const Limits& L) {
    if (L.force) return 100000000u;  // force 下的硬上限: 1 亿位(约 100MB 文本)
    return L.maxDigits;
}

static std::string fmtSci(long double v) {
    if (!std::isfinite((double)v)) return "极大";
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.3Le", v);
    return std::string(buf);
}

static void throwTooBig(long double est, const Limits& L, const std::string& what) {
    if (!L.force) {
        throw CalcError(what + "结果约 " + fmtSci(est) + " 位数字, 超过安全上限 " +
                        std::to_string(L.maxDigits) + " 位 (可用 --force 或 force on 解除)");
    }
    throw CalcError(what + "结果约 " + fmtSci(est) + " 位数字, 超过硬上限 100000000 位, 无法输出");
}

void decCheckSize(const BigDec& x, const Limits& L, const std::string& what) {
    if (x.mant.isZero()) return;
    size_t est = decEstDigits(x);
    if (est <= hardLimit(L)) return;
    if (!L.force) {
        throw CalcError(what + "结果约 " + std::to_string(est) + " 位数字, 超过安全上限 " +
                        std::to_string(L.maxDigits) + " 位 (可用 --force 或 force on 解除)");
    }
    throw CalcError(what + "结果约 " + std::to_string(est) +
                    " 位数字, 超过硬上限 100000000 位, 无法输出");
}

// ============================ BigDec 四则运算 ============================

static BigInt scaledMant(const BigDec& x, long long targetE) {
    long long sh = x.e10 - targetE;
    if (sh <= 0) return x.mant;
    return bigMul(x.mant, bigPow10((size_t)sh));
}

// 结果可靠位数的保守估计: 取两个操作数中较小者(-1 表示未知/精确)
static int relMin(const BigDec& a, const BigDec& b) {
    const int BIG = std::numeric_limits<int>::max();
    int x = a.reliable > 0 ? a.reliable : BIG;
    int y = b.reliable > 0 ? b.reliable : BIG;
    int m = std::min(x, y);
    return m == BIG ? -1 : m;
}

BigDec decAdd(const BigDec& a, const BigDec& b, const Limits& L) {
    if (a.mant.isZero() && b.mant.isZero()) {
        BigDec z;
        z.approx = a.approx || b.approx;
        z.reliable = relMin(a, b);
        return z;
    }
    if (a.mant.isZero()) {
        BigDec r = b;
        r.approx = r.approx || a.approx;
        r.reliable = relMin(a, b);
        return r.approx ? decRoundSig(r, L.precision) : r;
    }
    if (b.mant.isZero()) {
        BigDec r = a;
        r.approx = r.approx || b.approx;
        r.reliable = relMin(a, b);
        return r.approx ? decRoundSig(r, L.precision) : r;
    }

    // 预检查: 对齐后的位数不会超过上限, 避免巨量内存分配
    size_t estA = decEstDigits(a), estB = decEstDigits(b);
    size_t est = std::max(estA, estB) + 1;
    if (est > hardLimit(L)) throwTooBig((long double)est, L, "加法");

    long long e = std::min(a.e10, b.e10);
    BigInt ma = scaledMant(a, e);
    BigInt mb = scaledMant(b, e);

    BigDec r;
    bool approx = a.approx || b.approx;
    if (a.neg == b.neg) {
        r.mant = bigAdd(ma, mb);
        r.neg = a.neg;
        r.e10 = e;
    } else {
        int c = bigCmp(ma, mb);
        if (c == 0) {
            BigDec z;
            z.approx = approx;
            z.reliable = relMin(a, b);
            return z;
        }
        if (c > 0) { r.mant = bigSub(ma, mb); r.neg = a.neg; }
        else       { r.mant = bigSub(mb, ma); r.neg = b.neg; }
        r.e10 = e;
    }
    decStrip(r);
    r.approx = approx;
    r.reliable = relMin(a, b);
    if (r.approx) r = decRoundSig(r, L.precision);
    return r;
}

BigDec decMul(const BigDec& a, const BigDec& b, const Limits& L) {
    if (a.mant.isZero() || b.mant.isZero()) {
        BigDec z;
        z.approx = a.approx || b.approx;
        z.reliable = relMin(a, b);
        return z;
    }
    size_t est = bigDigitCount(a.mant) + bigDigitCount(b.mant) + 1;
    if (est > hardLimit(L)) throwTooBig((long double)est, L, "乘法");
    if (est > 5000) notify("乘法: " + std::to_string(bigDigitCount(a.mant)) + " 位 × " +
                               std::to_string(bigDigitCount(b.mant)) + " 位",
                           true);

    BigDec r;
    r.mant = bigMul(a.mant, b.mant);
    r.e10 = a.e10 + b.e10;
    r.neg = a.neg ^ b.neg;
    r.approx = a.approx || b.approx;
    r.reliable = relMin(a, b);
    decStrip(r);
    if (r.approx) r = decRoundSig(r, L.precision);
    decCheckSize(r, L, "乘法");
    return r;
}

BigDec decDiv(const BigDec& a, const BigDec& b, size_t prec, const Limits& L) {
    if (b.mant.isZero()) throw CalcError("除数不能等于0");
    if (a.mant.isZero()) {
        BigDec z;
        z.approx = a.approx || b.approx;
        z.reliable = relMin(a, b);
        return z;
    }
    if (prec < 1) prec = 1;

    BigDec x = a, y = b;
    bool truncated = false;
    size_t da = bigDigitCount(x.mant), db = bigDigitCount(y.mant);
    size_t T = prec + 20;

    // 精确长除法的代价约为 (商位数/9) * (除数位数/9); 只有它确实很慢时才截断输入,
    // 因为截断会让结果变成近似值。这样 a/b 能整除时始终给出精确结果。
    double cost = ((double)prec / 9.0 + 1.0) * ((double)db / 9.0 + 1.0);
    if (cost > 5.0e6 && (da > T || db > T)) {
        if (da > T) { x = truncSig(x, T); truncated = true; }
        if (db > T) { y = truncSig(y, T); truncated = true; }
        da = bigDigitCount(x.mant);
        db = bigDigitCount(y.mant);
    }

    size_t target = prec + 5;  // 多算几位保护位
    long long shift = (long long)target - ((long long)da - (long long)db);
    if (shift < 0) shift = 0;

    // 先按需要的位数试除; 若除不尽则逐步扩大移位重试, 直到余数为 0(有限小数, 精确结果)
    // 或达到上限(此时按 prec 位四舍五入)。移位必须严格递增, 否则会死循环。
    size_t capShift;
    if (db > 5000) capShift = (size_t)shift;  // 除数极大时不重试(代价高且基本不可能是有限小数)
    else capShift = std::max<size_t>(4096, 4 * (prec + 5));
    BigInt q, r;
    size_t shiftU = (size_t)shift;
    for (;;) {
        BigInt num = bigMul(x.mant, bigPow10(shiftU));
        bigDivMod(num, y.mant, q, r);
        if (r.isZero() || shiftU >= capShift) break;
        size_t next = (shiftU == 0) ? std::max<size_t>(target, 1) : shiftU * 2;
        if (next <= shiftU) next = shiftU + 1;
        if (next > capShift) next = capShift;
        if (next <= shiftU) break;
        shiftU = next;
    }
    shift = (long long)shiftU;

    bool exact = r.isZero() && !truncated && !a.approx && !b.approx;
    BigDec res;
    res.mant = q;
    res.e10 = x.e10 - y.e10 - shift;
    res.neg = a.neg ^ b.neg;
    res.approx = !exact;
    res.reliable = relMin(a, b);
    decStrip(res);
    if (!exact) res = decRoundSig(res, prec);
    decCheckSize(res, L, "除法");
    return res;
}

// ============================ 次方 ============================

static bool expIsOdd(const BigDec& e) {  // e 为整数
    if (e.mant.isZero()) return false;
    if (e.e10 > 0) return false;  // 末尾是 0 -> 偶数
    return (e.mant.d[0] % 10u) % 2u == 1u;
}

static bool decToU64(const BigDec& x, unsigned long long& out) {
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

static long double decToLongDouble(const BigDec& x) {
    if (x.mant.isZero()) return 0.0L;
    size_t n = x.mant.d.size();
    size_t take = std::min<size_t>(3, n);
    long double t = 0;
    for (size_t i = 0; i < take; ++i) t = t * (long double)BIG_BASE + (long double)x.mant.d[n - 1 - i];
    long double ex = (long double)(n - take) * (long double)BIG_BASE_DIGITS + (long double)x.e10;
    long double v;
    if (ex > 6000.0L) v = std::numeric_limits<long double>::infinity();
    else if (ex < -6000.0L) v = 0.0L;
    else v = t * std::pow(10.0L, ex);
    return x.neg ? -v : v;
}

static int bitCount64(unsigned long long v) {
    int c = 0;
    while (v) { ++c; v >>= 1; }
    return c;
}

// 二进制快速幂。report=true 时每一步"开始前"上报进度, 这样耗时的平方过程也有明确说明。
static BigInt bigPowU64(const BigInt& base, unsigned long long n, bool report,
                        long double expectedDigits) {
    BigInt r = bigFromU64(1);
    BigInt b = base;
    int total = bitCount64(n);
    int step = 0;
    while (n) {
        if (n & 1ULL) r = bigMul(r, b);
        n >>= 1;
        ++step;
        if (n) {
            size_t d = bigDigitCount(b);
            int pct = 0;
            if (report) {
                long double frac = (expectedDigits > 0) ? (long double)d / expectedDigits : 0;
                if (frac > 1) frac = 1;
                // Karatsuba 代价约 n^1.585, 用它折算完成度更接近真实手感
                pct = (int)(std::pow((double)frac, 1.585) * 100.0 + 0.5);
                if (pct > 100) pct = 100;
                if (pct < 0) pct = 0;
                // 小步骤一瞬间就过去了; 只对可能耗时的步骤强制显示, 保证一定能看到"算到哪了"
                bool important = (d >= 20000);
                notify("次方: 第 " + std::to_string(step) + "/" + std::to_string(total) +
                       " 步开始(平方 " + std::to_string(d) + " 位 -> 约 " +
                       std::to_string(d * 2) + " 位), 约完成 " + std::to_string(pct) + "%",
                       important);
            }
            StepCtxGuard ctx(report ? ("次方 第 " + std::to_string(step) + "/" +
                                       std::to_string(total) + " 步: ")
                                    : std::string());
            b = bigMul(b, b);
        }
    }
    return r;
}

static BigDec powNonInteger(const BigDec& base, const BigDec& exp, const Limits& L) {
    if (base.mant.isZero()) {
        if (!exp.neg) { BigDec z; z.approx = base.approx || exp.approx; return z; }
        throw CalcError("除数不能等于0 (0 的负数次幂相当于除以 0)");
    }
    if (base.neg)
        throw CalcError("负数的非整数次方在实数范围内无定义 (例如 (-8)^0.5), 请改用整数指数");
    long double b = decToLongDouble(base);
    long double e = decToLongDouble(exp);
    if (!std::isfinite((double)b) || !std::isfinite((double)e))
        throw CalcError("底数或指数超出可近似范围");
    long double r = std::pow(b, e);
    if (!std::isfinite((double)r))
        throw CalcError("次方结果超出可表示范围 (非整数指数走浮点近似, 上限约 1e4932)");
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.17Lg", r);
    BigDec out = decFromString(std::string(buf));
    out.approx = true;
    return decRoundSig(out, std::min<size_t>(L.precision, 15));  // 浮点只有 ~15 位可靠
}

BigDec decPow(const BigDec& base, const BigDec& exp, const Limits& L) {
    // 非整数指数: 走浮点近似
    if (!exp.mant.isZero() && !exp.isInteger()) return powNonInteger(base, exp, L);
    if (exp.mant.isZero()) return decFromU64(1);  // x^0 == 1 (约定 0^0 == 1)

    if (base.mant.isZero()) {
        if (!exp.neg) { BigDec z; z.approx = base.approx || exp.approx; return z; }
        throw CalcError("除数不能等于0 (0 的负数次幂相当于除以 0)");
    }

    // |base| == 1: 指数可任意大
    if (base.mant.d.size() == 1 && base.mant.d[0] == 1 && base.e10 == 0) {
        BigDec r = decFromU64(1);
        if (base.neg && expIsOdd(exp)) r.neg = true;
        r.approx = base.approx || exp.approx;
        return r;
    }

    long double nld = decToLongDouble(exp);
    long double absn = std::fabs(nld);
    long double logm = bigLog10(base.mant);  // log10(|尾数|)

    // 底数尾数为 1 (即 |base| = 10^k): 结果不占内存, 只需检查位数
    if (base.mant.d.size() == 1 && base.mant.d[0] == 1) {
        long double estDigits = 1.0L + (long double)base.e10 * absn;
        size_t hard = hardLimit(L);
        if (estDigits > (long double)hard) throwTooBig(estDigits, L, "次方");
        unsigned long long n = 0;
        if (!decToU64(exp, n))
            throw CalcError("指数过大: 次方结果约 " + fmtSci(estDigits) + " 位数字, 无法表示");
        BigDec r;
        r.mant = bigFromU64(1);
        r.e10 = base.e10 * (long long)n;
        r.neg = base.neg && expIsOdd(exp);
        r.approx = base.approx || exp.approx;
        r.reliable = relMin(base, exp);
        decCheckSize(r, L, "次方");
        return r;
    }

    unsigned long long n = 0;
    if (!decToU64(exp, n)) {
        throw CalcError("指数过大: 次方结果约 " + fmtSci(absn * logm + 1.0L) +
                        " 位数字, 无法计算 (指数本身已超出 64 位整数范围)");
    }
    size_t hard = hardLimit(L);
    long double estDigits = absn * logm + 1.0L;
    if (estDigits > (long double)hard) throwTooBig(estDigits, L, "次方");

    // 精确计算 |base|^n
    bool report = estDigits > 5000.0L;
    if (report)
        notify("次方: 开始计算 " + briefNumber(base) + "^" + briefNumber(exp) + ", 预计结果约 " +
                   fmtSci(estDigits) + " 位数字",
               true);
    BigDec p;
    p.mant = bigPowU64(base.mant, n, report, estDigits);
    if (std::fabs((long double)base.e10 * (long double)n) > 4.0e18L)
        throw CalcError("次方指数溢出 (long long 范围)");
    p.e10 = base.e10 * (long long)n;
    p.neg = base.neg && (n % 2ULL == 1ULL);
    p.approx = base.approx || exp.approx;
    p.reliable = relMin(base, exp);
    decStrip(p);
    decCheckSize(p, L, "次方");
    if (report) notify("次方: 计算完成, 结果 " + std::to_string(decEstDigits(p)) + " 位", true);

    if (!exp.neg) {
        return p.approx ? decRoundSig(p, L.precision) : p;
    }
    // 负指数: 1 / base^|n|
    return decDiv(decFromU64(1), p, L.precision, L);
}

// ============================ 开平方 ============================

BigDec decSqrt(const BigDec& a, const Limits& L) {
    if (a.neg) throw CalcError("负数不能开平方 (√ 的被开方数必须 >= 0)");
    if (a.mant.isZero()) {
        BigDec z;
        z.approx = a.approx;
        return z;
    }
    size_t prec = L.precision;
    const size_t EXACT_LIMIT = 20000;  // 位数 <= 2万时做完整精确开方

    if (bigDigitCount(a.mant) > 5000)
        notify("开平方: 被开方数 " + std::to_string(bigDigitCount(a.mant)) + " 位", true);

    BigDec x = a;
    bool truncated = false;
    if (bigDigitCount(x.mant) > EXACT_LIMIT && bigDigitCount(x.mant) > 2 * prec + 40) {
        x = truncSig(x, 2 * prec + 40);
        truncated = true;
    }

    // value = M * 10^(2q + s), s in {0,1}
    long long e = x.e10;
    long long q = (e >= 0) ? e / 2 : -((-e + 1) / 2);
    long long s = e - 2 * q;
    BigInt M = (s != 0) ? bigMulSmall(x.mant, 10) : x.mant;

    size_t dM = bigDigitCount(M);
    long long k = (long long)prec + 2 - (long long)((dM + 1) / 2);
    if (k < 0) k = 0;

    BigInt scaled = bigMul(M, bigPow10((size_t)(2 * k)));
    BigInt root = bigIsqrt(scaled);
    bool exact = !truncated && !a.approx && (bigCmp(bigMul(root, root), scaled) == 0);

    BigDec r;
    r.mant = root;
    r.e10 = q - k;
    r.neg = false;
    r.approx = !exact;
    r.reliable = a.reliable;
    decStrip(r);
    if (r.approx) r = decRoundSig(r, prec);
    decCheckSize(r, L, "开平方");
    return r;
}

// ============================ 输出 ============================

std::string decToString(const BigDec& x) {
    if (x.mant.isZero()) return "0";
    std::string ds = bigToString(x.mant);
    long long D = (long long)ds.size();
    long long P = D + x.e10;  // value = 0.ds * 10^P
    std::string sign = x.neg ? "-" : "";

    bool sci;
    if (x.e10 >= 0) {
        // 精确的大整数一律完整输出(这正是"次方不限大小"的意义);
        // 近似值不必用成千上万个 0 补齐整数位, 那样既冗长又有误导性。
        sci = x.approx && (D + x.e10 > 40);
    } else {
        sci = (P > 21 || P < -6);  // 小数且指数极端时用科学计数法
    }

    if (sci) {
        long long sciExp = P - 1;
        std::string out = ds.substr(0, 1);
        if (ds.size() > 1) out += "." + ds.substr(1);
        out += "e";
        out += (sciExp >= 0 ? "+" : "-");
        unsigned long long ae = (unsigned long long)(sciExp >= 0 ? sciExp : -sciExp);
        out += std::to_string(ae);
        return sign + out;
    }
    if (x.e10 >= 0) return sign + ds + std::string((size_t)x.e10, '0');
    if (P > 0) return sign + ds.substr(0, (size_t)P) + "." + ds.substr((size_t)P);
    return sign + "0." + std::string((size_t)(-P), '0') + ds;
}

}  // namespace calc
