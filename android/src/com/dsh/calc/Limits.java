package com.dsh.calc;

/** 精度与资源上限(与桌面版 calc.exe 一致) */
public class Limits {
    public int precision = 50;
    public int maxDigits = 1000000;
    public boolean force = false;

    int hardLimit() {
        return force ? 100000000 : maxDigits;
    }
}
