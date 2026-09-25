// bigdec.h -- 任意精度十进制数(大数)库
// 用于命令行计算器: 支持 + - * / 、开平方 sqrt 、任意大小的次方 ^
//
// 表示方式:
//   BigInt  -- 无符号大整数, 以 10^9 为基(little-endian limb 数组), 无前导零
//   BigDec  -- 带符号十进制浮点: value = (-1)^neg * mant * 10^e10
//              mant 已去除末尾零(规范化), 因此 e10 >= 0 <=> 该值为整数
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace calc {

constexpr uint32_t BIG_BASE = 1000000000u;  // 10^9
constexpr int BIG_BASE_DIGITS = 9;

// 长耗时计算的实时进度回调。msg = 进度描述, force = true 表示必须显示(不受节流限制)
using ProgressFn = std::function<void(const std::string& msg, bool force)>;
void setProgress(ProgressFn fn);

// 计算器抛出的所有可预期错误(除数为 0、负数开方、超出安全上限等)
class CalcError : public std::runtime_error {
public:
    explicit CalcError(const std::string& m) : std::runtime_error(m) {}
};

class BigInt {
public:
    std::vector<uint32_t> d;  // little-endian, base 1e9, 空 == 0
    BigInt() = default;
    bool isZero() const { return d.empty(); }
    void trim() {
        while (!d.empty() && d.back() == 0) d.pop_back();
    }
};

BigInt bigFromU64(unsigned long long v);
BigInt bigFromString(const std::string& digits);
std::string bigToString(const BigInt& x);
size_t bigDigitCount(const BigInt& x);
int bigCmp(const BigInt& a, const BigInt& b);
BigInt bigAdd(const BigInt& a, const BigInt& b);
BigInt bigSub(const BigInt& a, const BigInt& b);  // 要求 a >= b
BigInt bigMul(const BigInt& a, const BigInt& b);
void bigDivMod(const BigInt& u, const BigInt& v, BigInt& q, BigInt& r);
BigInt bigMulSmall(const BigInt& a, uint32_t m);
BigInt bigDivSmall(const BigInt& a, uint32_t v, uint32_t* rem);
BigInt bigPow10(size_t k);          // 10^k
BigInt bigIsqrt(const BigInt& n);   // floor(sqrt(n))
long double bigLog10(const BigInt& x);

// 资源/精度限制(不是数学上的限制, 只是防止误操作把内存吃光)
struct Limits {
    size_t precision = 50;         // 近似结果保留的有效数字位数
    size_t maxDigits = 1000000;    // 软上限: 结果位数超过它则拒绝(force 可解除)
    bool force = false;            // force = 解除软上限(仍有 1e8 位的硬上限)
};

struct BigDec {
    bool neg = false;      // 符号(零时恒为 false)
    BigInt mant;           // 尾数(无符号, 已去末尾零)
    long long e10 = 0;     // 指数: value = mant * 10^e10
    bool approx = false;   // 是否已被舍入(近似值)
    int reliable = -1;     // 近似值的可靠有效位数(-1 = 未知, 按 precision 计)

    bool isZero() const { return mant.isZero(); }
    bool isInteger() const { return mant.isZero() || e10 >= 0; }
};

BigDec decZero();
BigDec decFromU64(unsigned long long v);
BigDec decFromString(const std::string& s);  // 支持 "12", "-3.5", "1.25e-8"
void decStrip(BigDec& x);                    // 去掉尾数末尾零, 规范化
BigDec decRoundSig(const BigDec& x, size_t prec);
int decCmpAbs(const BigDec& a, const BigDec& b);
int decCmp(const BigDec& a, const BigDec& b);
size_t decEstDigits(const BigDec& x);  // 按普通十进制书写时的位数估计
bool decIsZero(const BigDec& x);

BigDec decAdd(const BigDec& a, const BigDec& b, const Limits& L);
BigDec decMul(const BigDec& a, const BigDec& b, const Limits& L);
BigDec decDiv(const BigDec& a, const BigDec& b, size_t prec, const Limits& L);
BigDec decPow(const BigDec& base, const BigDec& exp, const Limits& L);
BigDec decSqrt(const BigDec& a, const Limits& L);

std::string decToString(const BigDec& x);
void decCheckSize(const BigDec& x, const Limits& L, const std::string& what);

}  // namespace calc
