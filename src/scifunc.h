// scifunc.h -- 科学计算函数扩展
// 所有三角/对数/指数函数都基于 long double 标准库实现, 结果标记为近似值,
// 可靠位数约 15~17 位(受浮点限制)。abs/floor/ceil/round/fact 走精确大数路径。
#pragma once

#include "bigdec.h"

namespace calc {

BigDec decAbs(const BigDec& x);
BigDec decFloor(const BigDec& x);
BigDec decCeil(const BigDec& x);
BigDec decRound(const BigDec& x);
BigDec decFact(const BigDec& x, const Limits& L);

BigDec degToRad(const BigDec& x, const Limits& L);
BigDec radToDeg(const BigDec& x, const Limits& L);

BigDec decSin(const BigDec& x, const Limits& L);
BigDec decCos(const BigDec& x, const Limits& L);
BigDec decTan(const BigDec& x, const Limits& L);
BigDec decAsin(const BigDec& x, const Limits& L);
BigDec decAcos(const BigDec& x, const Limits& L);
BigDec decAtan(const BigDec& x, const Limits& L);

BigDec decSinh(const BigDec& x, const Limits& L);
BigDec decCosh(const BigDec& x, const Limits& L);
BigDec decTanh(const BigDec& x, const Limits& L);

BigDec decExp(const BigDec& x, const Limits& L);
BigDec decLog(const BigDec& x, const Limits& L);    // 自然对数 ln
BigDec decLog10(const BigDec& x, const Limits& L);
BigDec decLog2(const BigDec& x, const Limits& L);

}  // namespace calc
