#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
compare_with_exe.py -- 把 Java 版核心(android/src)与已验证的桌面版 calc.exe 逐条对比。

两者语义应完全一致: 结果字符串、错误信息、退出码、近似值提示。
用法: python android\\test\\compare_with_exe.py
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
ROOT = os.path.dirname(ANDROID)
EXE = os.path.join(ROOT, 'calc.exe')
JDK = r'C:\Program Files\Java\jdk-23'
JAVAC = os.path.join(JDK, 'bin', 'javac.exe')
JAVA = os.path.join(JDK, 'bin', 'java.exe')
OUT = os.path.join(HERE, 'out')

CASES = [
    # (表达式, 额外参数)
    ('1+2*3', []),
    ('(1+2)*3/4', []),
    ('0.1+0.2', []),
    ('1/8', []),
    ('10/4', []),
    ('12345678901234567890123/100', []),
    ('1.5e10+1', []),
    ('-2^2', []),
    ('2^3^2', []),
    ('2^-3', []),
    ('2*3^2', []),
    ('12345^0', []),
    ('0^0', []),
    ('(-2)^3', []),
    ('(-2)^4', []),
    ('1.5^3', []),
    ('0.5^10', []),
    ('pow(3,4)', []),
    ('2**10', []),
    ('1/0', []),
    ('0/0', []),
    ('1/(2-2)', []),
    ('5/(1-1)*3', []),
    ('0^-1', []),
    ('sqrt(1/0)', []),
    ('sqrt(144)', []),
    ('√9', []),
    ('√(49)', []),
    ('sqrt(0)', []),
    ('sqrt(0.25)', []),
    ('sqrt(1/4)', []),
    ('sqrt(-4)', []),
    ('√(-1)', []),
    ('2^100', []),
    ('2^1000', []),
    ('10^400', []),
    ('3^500', []),
    ('7^999', []),
    ('2^2000', []),
    ('(2^100)^2', []),
    ('2^-100', []),
    ('1/1024', []),
    ('1/2^100', []),
    ('sqrt(2)', []),
    ('1/3', []),
    ('1/7', []),
    ('pi', []),
    ('e', []),
    ('2^0.5', []),
    ('(-8)^(1/3)', []),
    ('sqrt(2^1000000)', []),
    ('2^1000000/3', []),
    ('1e20*1e20', []),
    ('1e-10+1e-12', []),
    ('1000000000000000000000000000000/7', []),
    ('1116049702248352640198684110487083586012293984052482719485021746840153560283014/(-4589875216668122044968745798315891727406232573254)', []),
    ('12345678901234567890*98765432109876543210', []),
    ('99999999999999999999+1', []),
    ('1/998001', []),
    ('2^33200', []),
    ('sqrt(10^400)', []),
    ('sqrt(2)', ['-p', '100']),
    ('1/3', ['-p', '100']),
    ('1/7', ['-p', '300']),
    ('sqrt(2)', ['-p', '10']),
    ('1/3', ['-p', '25']),
    ('2^5000', ['-m', '1000']),
    ('1e600000*1e600000', []),
    ('(1+2', []),
    ('1+2)', []),
    ('()', []),
    ('1++', []),
    ('   ', []),
    ('foo(2)', []),
    ('pow(1)', []),
    ('5!', []),
    ('1/(3-3)', []),
    ('2^(3-1)', []),
    ('0-(-5)', []),
    ('1e3', []),
    ('.5+.5', []),
    ('2^10-2^9', []),
]

JVM_ENCODING = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = (p.stdout or '').strip()
    # 进度信息是给人看的实时输出, 与结果无关: 忽略(Java 测试入口没有装进度回调)
    err = '\n'.join(l for l in (p.stderr or '').splitlines() if not l.startswith('[进度]')).strip()
    return p.returncode, out, err


def main():
    if not os.path.exists(EXE):
        print('找不到 calc.exe')
        return 1
    if os.path.isdir(OUT):
        import shutil
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    src = [os.path.join(ANDROID, 'src', 'com', 'dsh', 'calc', 'BigDec.java'),
           os.path.join(ANDROID, 'src', 'com', 'dsh', 'calc', 'Calc.java'),
           os.path.join(HERE, 'com', 'dsh', 'calc', 'JavaCalcMain.java')]
    cp = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-nowarn', '-d', OUT] + src,
                        capture_output=True, text=True, encoding='utf-8', errors='replace')
    if cp.returncode != 0:
        print('javac 失败:\n' + (cp.stdout or '') + (cp.stderr or ''))
        return 1
    print('Java 核心编译通过')

    bad = 0
    for expr, extra in CASES:
        rc1, out1, err1 = run([EXE] + extra + [expr])
        rc2, out2, err2 = run([JAVA] + JVM_ENCODING + ['-cp', OUT, 'com.dsh.calc.JavaCalcMain']
                              + extra + [expr])
        # 近似提示在 exe 里走 stderr, Java 版也走 stderr, 直接比较
        same = (rc1 == rc2) and (out1 == out2) and (err1 == err2)
        if not same:
            bad += 1
            print(f'\n[差异] {expr} {extra}')
            print(f'   exe : rc={rc1} out={out1[:160]!r} err={err1[:120]!r}')
            print(f'   java: rc={rc2} out={out2[:160]!r} err={err2[:120]!r}')
    print(f'\n共 {len(CASES)} 个用例, 差异 {bad} 个')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
