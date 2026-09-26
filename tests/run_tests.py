#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_tests.py -- 用 Python 的任意精度整数/Decimal 作为标准答案, 交叉验证 calc.exe

用法:  python tests\\run_tests.py
退出码: 0 = 全部通过, 1 = 有失败
"""
import decimal
import os
import random
import subprocess
import sys
from decimal import Decimal, getcontext, ROUND_HALF_UP

HERE = os.path.dirname(os.path.abspath(__file__))
EXE = os.path.abspath(os.path.join(HERE, '..', 'calc.exe'))

getcontext().prec = 400
getcontext().rounding = ROUND_HALF_UP

fails = []
passed = 0
prec_default = 50


def run(args):
    p = subprocess.run([EXE] + args, capture_output=True, text=True,
                       encoding='utf-8', errors='replace')
    return p.returncode, (p.stdout or '').strip(), (p.stderr or '').strip()


def check(desc, expr, expected, extra_args=None, precision=None):
    """期望 stdout 完全等于 expected, 且退出码 0"""
    global passed
    args = []
    if precision is not None:
        args += ['-p', str(precision)]
    if extra_args:
        args += extra_args
    args.append(expr)
    rc, out, err = run(args)
    if rc != 0 or out != expected:
        fails.append(f"[{desc}] {expr!r}\n   期望: rc=0 out={expected!r}\n   实际: rc={rc} out={out!r} err={err!r}")
    else:
        passed += 1


def check_error(desc, expr, needle, extra_args=None):
    """期望退出码 1, stderr 含 needle"""
    global passed
    args = list(extra_args or []) + [expr]
    rc, out, err = run(args)
    if rc == 0 or needle not in err:
        fails.append(f"[{desc}] {expr!r}\n   期望: rc!=0 且 stderr 含 {needle!r}\n   实际: rc={rc} out={out!r} err={err!r}")
    else:
        passed += 1


def check_approx(desc, expr, expected, tol=Decimal('1e-14'), extra_args=None):
    """科学函数近似校验: 输出与期望的相对/绝对误差不超过 tol"""
    global passed
    args = list(extra_args or []) + [expr]
    rc, out, err = run(args)
    if rc != 0:
        fails.append(f"[{desc}] {expr!r}\n   期望 rc=0, 实际 rc={rc} err={err!r}")
        return
    try:
        got = Decimal(out.strip())
        want = Decimal(expected)
        diff = abs(got - want)
        scale = max(abs(want), Decimal(1))
        if diff > tol * scale:
            fails.append(f"[{desc}] {expr!r}\n   期望约 {expected}\n   实际 {out.strip()} (误差 {diff})")
        else:
            passed += 1
    except Exception as e:
        fails.append(f"[{desc}] {expr!r}\n   无法解析输出 {out.strip()!r}: {e}")


def dec_str(d, prec, approx=False):
    """把 Decimal 按 prec 位有效数字四舍五入, 再按 calc 的格式规则输出字符串"""
    with decimal.localcontext() as ctx:
        ctx.prec = prec
        ctx.rounding = ROUND_HALF_UP
        d = +d          # 舍入到 prec 位有效数字
    if d == 0:
        return "0"
    sign = '-' if d < 0 else ''
    t = abs(d).as_tuple()
    digits = ''.join(str(x) for x in t.digits)
    e10 = t.exponent
    # 去掉尾数末尾零(与 calc 的规范化一致)
    while len(digits) > 1 and digits.endswith('0'):
        digits = digits[:-1]
        e10 += 1
    D = len(digits)
    P = D + e10
    if e10 >= 0:
        # 精确大整数完整输出; 近似值过长时用科学计数法
        if approx and D + e10 > 40:
            sci = P - 1
            return sign + digits[0] + ('.' + digits[1:] if D > 1 else '') + \
                   'e' + ('+' if sci >= 0 else '-') + str(abs(sci))
        return sign + digits + '0' * e10
    if P > 21 or P < -6:
        sci = P - 1
        out = digits[0] + ('.' + digits[1:] if D > 1 else '')
        return sign + out + 'e' + ('+' if sci >= 0 else '-') + str(abs(sci))
    if P > 0:
        return sign + digits[:P] + '.' + digits[P:]
    return sign + '0.' + '0' * (-P) + digits


print(f"被测程序: {EXE}")
if not os.path.exists(EXE):
    print("找不到 calc.exe, 请先运行 build.bat")
    sys.exit(1)

# ---------------------------------------------------------------- 基本运算
check('加减', '1+2*3', '7')
check('括号', '(1+2)*3/4', '2.25')
check('小数', '0.1+0.2', '0.3')
check('负号', '-5+3', '-2')
check('嵌套括号', '((((1+2))))*((3))', '9')
check('连减', '10-3-2', '5')
check('除不尽', '1/8', '0.125')
check('科学输入', '1.5e10+1', '15000000001')
check('全角括号/乘除号', '（2×3）÷4', '1.5')
check('一元负号优先级', '-2^2', '-4')
check('幂右结合', '2^3^2', '512')
check('负指数', '2^-3', '0.125')
check('幂与乘', '2*3^2', '18')
check('零次方', '12345^0', '1')
check('零的零次方', '0^0', '1')
check('负底数奇次', '(-2)^3', '-8')
check('负底数偶次', '(-2)^4', '16')
check('小数底数幂', '1.5^3', '3.375')
check('小数底数负幂', '0.5^10', '0.0009765625')
check('幂函数形式', 'pow(3,4)', '81')
check('星号幂', '2**10', '1024')

# ---------------------------------------------------------------- 除法除零判断
check_error('除以零', '1/0', '除数不能等于0')
check_error('零除以零', '0/0', '除数不能等于0')
check_error('表达式除数为零', '1/(2-2)', '除数不能等于0')
check_error('除数为零(括号)', '5/(1-1)*3', '除数不能等于0')
check_error('零的负次幂', '0^-1', '除数不能等于0')
check_error('零的负次幂2', '0^(-5)', '除数不能等于0')
check_error('sqrt 除零', 'sqrt(1/0)', '除数不能等于0')
check_error('除数为零非法格式', '3/0.000', '除数不能等于0')

# ---------------------------------------------------------------- 开平方
check('sqrt 完全平方', 'sqrt(144)', '12')
check('√ 符号', '√9', '3')
check('√ 无括号', '√(49)', '7')
check('√ 大完全平方', f'sqrt({10**40 * 10**40})', str(10**40))
check('sqrt 零', 'sqrt(0)', '0')
check('sqrt 小数完全平方', 'sqrt(0.25)', '0.5')
check('sqrt 分数完全平方', 'sqrt(1/4)', '0.5')
check_error('负数开平方', 'sqrt(-4)', '负数不能开平方')
check_error('负数开平方2', '√(-1)', '负数不能开平方')
check_error('表达式负数开平方', 'sqrt(3-10)', '负数不能开平方')

# ---------------------------------------------------------------- 次方不限大小
check('2^100', '2^100', '1267650600228229401496703205376')
check('2^1000', '2^1000', str(2 ** 1000))
check('10^400', '10^400', '1' + '0' * 400)
check('3^500', '3^500', str(3 ** 500))
check('超出 double 范围', '2^2000', str(2 ** 2000))
check('2^64', '2^64', str(2 ** 64))
check('7^999', '7^999', str(7 ** 999))
check('幂套幂', '(2^100)^2', str(2 ** 200))
check('负指数大数', '2^-100', dec_str(Decimal(2) ** -100, 80))
check('次方后取模式运算', '2^100+2^100', str(2 ** 101))

# ---------------------------------------------------------------- 近似值(与 Decimal 对齐)
check('sqrt2 50位', 'sqrt(2)', '1.4142135623730950488016887242096980785696718753769')
check('1/3 50位', '1/3', '0.33333333333333333333333333333333333333333333333333')
check('1/7 50位', '1/7', '0.14285714285714285714285714285714285714285714285714')
check('pi', 'pi', '3.1415926535897932384626433832795028841971693993751')

for prec in (10, 25, 100, 300):
    getcontext().prec = prec + 20
    check(f'sqrt(2) {prec}位', 'sqrt(2)', dec_str(Decimal(2).sqrt(), prec, approx=True), precision=prec)
    check(f'1/3 {prec}位', '1/3', dec_str(Decimal(1) / Decimal(3), prec, approx=True), precision=prec)
    check(f'1/7 {prec}位', '1/7', dec_str(Decimal(1) / Decimal(7), prec, approx=True), precision=prec)
getcontext().prec = 400

# ---------------------------------------------------------------- 随机整数交叉验证
random.seed(20240517)
for i in range(120):
    na = random.randint(1, 70)
    nb = random.randint(1, 70)
    a = random.randint(-10 ** na, 10 ** na)
    b = random.randint(-10 ** nb, 10 ** nb)
    check('rand+', f'({a})+({b})', str(a + b))
    check('rand-', f'({a})-({b})', str(a - b))
    check('rand*', f'({a})*({b})', str(a * b))
    if b != 0:
        c = random.randint(-10 ** 30, 10 ** 30)
        check('rand exact /', f'({b * c})/({b})', str(c))
    base = random.choice([2, 3, 5, 7, 11, 13, 97, -3, -7, 1, -1, 10])
    e = random.randint(0, 300)
    check('rand^', f'({base})^{e}', str(base ** e))

# ---------------------------------------------------------------- 随机小数
for i in range(60):
    x = random.randint(-10 ** 6, 10 ** 6) / 1000.0
    y = random.randint(-10 ** 4, 10 ** 4) / 100.0
    prec = 30
    getcontext().prec = prec + 25
    dx, dy = Decimal(str(x)), Decimal(str(y))
    check('rand dec +', f'({x})+({y})', dec_str(dx + dy, prec), precision=prec)
    check('rand dec *', f'({x})*({y})', dec_str(dx * dy, prec), precision=prec)
    if y != 0:
        check('rand dec /', f'({x})/({y})', dec_str(dx / dy, prec, approx=True), precision=prec)
getcontext().prec = 400

# ---------------------------------------------------------------- 位数上限
# 默认上限 1e6 位: 2^4000000 约 120 万位, 应被拒绝
check_error('超安全上限', '2^4000000', '超过安全上限')
check_error('超安全上限(乘法)', '1e600000*1e600000', '超过安全上限')
# 调低上限后, 即使不太大的结果也应被拒绝; --force 可解除
check_error('自定义上限拦截', '2^5000', '超过安全上限', extra_args=['-m', '1000'])
rc, out, err = run(['-m', '1000', '--force', '2^5000'])
if rc != 0 or out != str(2 ** 5000):
    fails.append(f"[force 解除上限] rc={rc} out(len)={len(out)} err={err!r}")
else:
    passed += 1
# 指数本身巨大 -> 直接报错而不是卡死
check_error('指数过大', '2^100000000000000000000', '指数过大')

# ---------------------------------------------------------------- 语法错误
check_error('未知字符', '5!', '无法识别的字符')
check_error('括号不匹配', '(1+2', '语法错误')
check_error('多余右括号', '1+2)', '语法错误')
check_error('空括号', '()', '语法错误')
check_error('连续运算符', '1++', '语法错误')
check_error('空表达式', '   ', '表达式为空')
check_error('未知名称', 'foo(2)', '未知的名称')
check_error('缺少参数', 'pow(1)', '语法错误')

# ---------------------------------------------------------------- pi/e 位数跟随 precision
# 回归: 之前 pi/e 是硬编码常量(最多 202 字符), precision 调大也无效
rc, out, err = run(['-p', '300', 'pi'])
if rc != 0 or len(out) != 301:
    fails.append(f"[pi 300位] 期望 301 字符, 实际 {len(out)} err={err!r}")
else:
    passed += 1

rc, out, err = run(['-p', '20000', 'pi'])
if rc != 0 or len(out) != 20001:
    fails.append(f"[pi 20000位] 期望 20001 字符, 实际 {len(out)} err={err!r}")
else:
    passed += 1

rc, out, err = run(['-p', '500', 'e'])
if rc != 0 or len(out) != 501:
    fails.append(f"[e 500位] 期望 501 字符, 实际 {len(out)} err={err!r}")
else:
    passed += 1

rc, out, err = run(['-p', '3000', 'sqrt(2)'])
if rc != 0 or len(out) < 3000:
    fails.append(f"[sqrt2 3000位] 期望 >=3000 字符, 实际 {len(out)} err={err!r}")
else:
    passed += 1

# ---------------------------------------------------------------- 科学函数
check_approx('sin(1)', 'sin(1)', '0.8414709848078965', Decimal('1e-15'))
check_approx('cos(0)', 'cos(0)', '1', Decimal('1e-15'))
check_approx('tan(pi/4)', 'tan(pi/4)', '1', Decimal('1e-14'))
check_approx('sin(rad(30))', 'sin(rad(30))', '0.5', Decimal('1e-14'))
check_approx('exp(1)', 'exp(1)', '2.718281828459045', Decimal('1e-14'))
check_approx('log(e)', 'log(e)', '1', Decimal('1e-14'))
check_approx('log10(1000)', 'log10(1000)', '3', Decimal('1e-14'))
check_approx('log2(8)', 'log2(8)', '3', Decimal('1e-14'))
check_approx('asin(1)', 'asin(1)', '1.5707963267948966', Decimal('1e-14'))
check_approx('acos(0)', 'acos(0)', '1.5707963267948966', Decimal('1e-14'))
check_approx('atan(1)', 'atan(1)', '0.7853981633974483', Decimal('1e-15'))
check_approx('sinh(1)', 'sinh(1)', '1.1752011936438014', Decimal('1e-14'))
check_approx('cosh(1)', 'cosh(1)', '1.5430806348152437', Decimal('1e-14'))
check_approx('tanh(1)', 'tanh(1)', '0.7615941559557649', Decimal('1e-15'))
check_approx('rad(180)', 'rad(180)', '3.141592653589793', Decimal('1e-14'))
check_approx('deg(pi)', 'deg(pi)', '180', Decimal('1e-14'))
check('fact(20)', 'fact(20)', '2432902008176640000')
check('fact(0)', 'fact(0)', '1')
check('abs(-5)', 'abs(-5)', '5')
check('floor(2.7)', 'floor(2.7)', '2')
check('ceil(2.1)', 'ceil(2.1)', '3')
check('round(2.5)', 'round(2.5)', '3')
check('round(-2.5)', 'round(-2.5)', '-3')
check_error('log(0)', 'log(0)', '对数的真数必须大于 0')
check_error('asin(2)', 'asin(2)', 'asin 定义域为 [-1, 1]')
check_error('fact(-1)', 'fact(-1)', '阶乘仅对非负整数有定义')

# ---------------------------------------------------------------- 命令行选项
rc, out, err = run(['--version'])
if rc != 0 or 'v3.0' not in out:
    fails.append(f"[--version] rc={rc} out={out!r}")
else:
    passed += 1

rc, out, err = run(['--help'])
if rc != 0 or '用法' not in out:
    fails.append(f"[--help] rc={rc} out={out!r}")
else:
    passed += 1

rc, out, err = run(['2', '^', '100'])   # 多个参数拼接
if rc != 0 or out != '1267650600228229401496703205376':
    fails.append(f"[参数拼接] rc={rc} out={out!r} err={err!r}")
else:
    passed += 1

rc, out, err = run(['--bad-option'])
if rc != 2:
    fails.append(f"[未知选项] rc={rc}")
else:
    passed += 1

# ---------------------------------------------------------------- 管道/交互模式
p = subprocess.run([EXE], input='1+1\n2^10\nsqrt(16)\n1/0\n', capture_output=True,
                   text=True, encoding='utf-8', errors='replace')
expected_lines = ['2', '1024', '4', '错误: 除数不能等于0']
got_lines = [l for l in (p.stdout or '').splitlines() if l.strip()]
if got_lines != expected_lines:
    fails.append(f"[管道模式] 期望 {expected_lines} 实际 {got_lines}")
else:
    passed += 1

p = subprocess.run([EXE], input='precision 80\n1/3\nexit\n', capture_output=True,
                   text=True, encoding='utf-8', errors='replace')
lines = [l for l in (p.stdout or '').splitlines() if l.strip()]
ok = any(l.startswith('precision = 80') for l in lines) and \
     any(len(l.strip()) == 82 and l.strip().startswith('0.3333') for l in lines)
if not ok:
    fails.append(f"[交互命令 precision] 输出异常: {lines}")
else:
    passed += 1

# ---------------------------------------------------------------- 汇总
print(f"\n通过 {passed} 项, 失败 {len(fails)} 项")
if fails:
    print("\n失败明细:")
    for f in fails[:40]:
        print(" - " + f)
    if len(fails) > 40:
        print(f" ... 另有 {len(fails) - 40} 项")
    sys.exit(1)
print("全部通过")
