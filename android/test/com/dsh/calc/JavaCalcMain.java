package com.dsh.calc;

/**
 * 桌面 JVM 上的测试入口(不属于 APK)。用于把 Java 版核心与桌面版 calc.exe 做逐条对比。
 * 用法: java com.dsh.calc.JavaCalcMain [-p N] [-m N] [-f] "表达式"
 */
public class JavaCalcMain {
    public static void main(String[] args) {
        Limits L = new Limits();
        String expr = null;
        for (int i = 0; i < args.length; ++i) {
            String a = args[i];
            if ("-p".equals(a) && i + 1 < args.length) L.precision = Integer.parseInt(args[++i]);
            else if ("-m".equals(a) && i + 1 < args.length) L.maxDigits = Integer.parseInt(args[++i]);
            else if ("-f".equals(a)) L.force = true;
            else expr = (expr == null ? "" : expr) + a;
        }
        if (expr == null) {
            System.err.println("need an expression");
            System.exit(2);
        }
        try {
            BigDec v = Calc.evaluate(expr, L);
            System.out.println(v.toString());
            if (v.approx) {
                System.err.println("注意: 结果为近似值(" + Calc.reliableOf(v, L) + " 位有效数字)");
            }
            System.exit(0);
        } catch (CalcException e) {
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        } catch (Throwable e) {
            System.err.println("内部错误: " + e);
            System.exit(1);
        }
    }
}
