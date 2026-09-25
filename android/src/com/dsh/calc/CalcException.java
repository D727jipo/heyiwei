package com.dsh.calc;

/** 计算器可预期错误(除数为 0、负数开方、语法错误等) */
public class CalcException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CalcException(String msg) {
        super(msg);
    }
}
