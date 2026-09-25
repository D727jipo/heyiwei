#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_pi.py —— 用 Python 独立实现(Machin 公式 + 纯整数定点)校验 Android 核心算出的 pi / e。

用法: python android\\test\\verify_pi.py
"""
import os
import shutil
import subprocess
import sys

if hasattr(sys, 'set_int_max_str_digits'):
    sys.set_int_max_str_digits(0)  # 允许超长整数转字符串

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
ROOT = os.path.dirname(ANDROID)
JDK = r'C:\Program Files\Java\jdk-23'
JAVAC = os.path.join(JDK, 'bin', 'javac.exe')
JAVA = os.path.join(JDK, 'bin', 'java.exe')
OUT = os.path.join(HERE, 'out')


def arctan_inv(x, scale):
    """scale * arctan(1/x), 纯整数"""
    total = 0
    term = scale // x
    x2 = x * x
    n = 0
    while term:
        t = term // (2 * n + 1)
        total += t if n % 2 == 0 else -t
        term //= x2
        n += 1
    return total


def pi_ref(prec):
    """Machin: pi/4 = 4*arctan(1/5) - arctan(1/239)
    返回 (round(pi*10^decimals), decimals), 使 1+decimals = prec 位有效数字"""
    guard = 20
    decimals = prec - 1              # 整数部分只有 1 位(3), 所以有效数字 = 1 + decimals
    scale = 10 ** (decimals + guard)
    v = 4 * (4 * arctan_inv(5, scale) - arctan_inv(239, scale))
    v = (v + 5 * 10 ** (guard - 1)) // 10 ** guard
    return v, decimals


def e_ref(prec):
    guard = 30
    decimals = prec - 1              # e 的整数部分也只有 1 位(2)
    scale = 10 ** (decimals + guard)
    total = scale
    term = scale
    n = 1
    while term:
        term //= n
        total += term
        n += 1
    total = (total + 5 * 10 ** (guard - 1)) // 10 ** guard
    return total, decimals


def to_str(v, digits_total):
    """把 scale=10^digits_total 的整数按 calc 的格式输出(科学计数法仅用于极端情况, 这里都是普通小数)"""
    s = str(v)
    if len(s) <= 1:
        return s
    point = len(s) - digits_total  # 小数点在从左边第 point 位之后
    if point <= 0:
        out = '0.' + '0' * (-point) + s
    else:
        out = s[:point] + '.' + s[point:]
    if '.' in out:
        out = out.rstrip('0').rstrip('.')
    return out


def run(expr, prec):
    p = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                        '-cp', OUT, 'com.dsh.calc.JavaCalcMain', '-p', str(prec), expr],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    return (p.stdout or '').strip(), (p.stderr or '').strip()


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    src = [os.path.join(ANDROID, 'src', 'com', 'dsh', 'calc', n)
           for n in ('BigDec.java', 'Calc.java', 'CalcException.java', 'Limits.java')]
    cp = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-d', OUT] + src +
                        [os.path.join(HERE, 'com', 'dsh', 'calc', 'JavaCalcMain.java')],
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    if cp.returncode != 0:
        print('javac 失败:\n' + (cp.stdout or '') + (cp.stderr or ''))
        return 1
    print('Java 核心编译通过', flush=True)

    bad = 0
    for prec in (50, 200, 1000, 5000, 20000):
        got, err = run('pi', prec)
        v, total = pi_ref(prec)
        want = to_str(v, total)
        ok = (got == want)
        print(f'pi  {prec:>6} 位: {"一致" if ok else "不一致"}  (输出 {len(got)} 字符)', flush=True)
        if not ok:
            bad += 1
            n = min(len(got), len(want))
            pos = next((i for i in range(n) if got[i] != want[i]), n)
            print(f'   首个不同位置 {pos} (共 {n} 位): 实际 {got[pos:pos+20]!r} 期望 {want[pos:pos+20]!r}')

    for prec in (50, 1000, 5000, 20000):
        got, err = run('e', prec)
        v, total = e_ref(prec)
        want = to_str(v, total)
        ok = (got == want)
        print(f'e   {prec:>6} 位: {"一致" if ok else "不一致"}  (输出 {len(got)} 字符)', flush=True)
        if not ok:
            bad += 1
            n = min(len(got), len(want))
            pos = next((i for i in range(n) if got[i] != want[i]), n)
            print(f'   首个不同位置 {pos}: 实际 {got[pos:pos+20]!r} 期望 {want[pos:pos+20]!r}')

    exe = os.path.join(ROOT, 'calc.exe')
    if os.path.exists(exe):
        for prec in (50, 100):
            a, _ = run('pi', prec)
            p = subprocess.run([exe, '-p', str(prec), 'pi'], capture_output=True,
                               text=True, encoding='utf-8', errors='replace')
            b = (p.stdout or '').strip()
            ok = (a == b)
            print(f'与桌面版 calc.exe 对比 pi -p {prec}: {"一致" if ok else "不一致"}', flush=True)
            if not ok:
                bad += 1

    print(f'\n差异 {bad} 项')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
