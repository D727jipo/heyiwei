package com.dsh.calc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大数计算器 —— 使用 Android 自带框架(android.widget / android.app), 不依赖任何第三方库。
 *
 * <p>功能与桌面版 calc.exe 一致: + - * / 、√ 开平方、^ 次方(不限大小)、括号、pi/e。
 * 另外:
 * <ul>
 *   <li>边输入边计算(实时结果显示)</li>
 *   <li>长耗时计算在后台线程执行, 并实时显示"算到哪了"的进度(百分比 + 说明)</li>
 *   <li>超长结果只渲染前面一部分, 完整结果可一键复制</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private static final int SHOW_MAX_CHARS = 4000;   // 结果框里最多渲染多少字符
    private static final int PREVIEW_CHARS = 2000;    // 超长结果预览长度

    private EditText input;
    private TextView result;
    private TextView note;
    private TextView progressText;
    private ProgressBar progressBar;
    private CheckBox liveBox;

    private Limits limits = new Limits();
    private String fullResult = "";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int generation = 0;           // 丢弃过期结果
    private Runnable pendingEval;
    private volatile boolean computing = false;
    private volatile int lastPercent = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        input = (EditText) findViewById(R.id.input);
        result = (TextView) findViewById(R.id.result);
        note = (TextView) findViewById(R.id.note);
        progressText = (TextView) findViewById(R.id.progressText);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        liveBox = (CheckBox) findViewById(R.id.live);

        loadPrefs();
        BigDec.setProgress(new BigDec.Progress() {
            @Override
            public void onProgress(final String msg, final int percent) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showProgress(msg, percent);
                    }
                });
            }
        });

        // 键盘
        int[] ids = {R.id.k0, R.id.k1, R.id.k2, R.id.k3, R.id.k4, R.id.k5, R.id.k6, R.id.k7,
                R.id.k8, R.id.k9, R.id.kDot, R.id.kPlus, R.id.kMinus, R.id.kMul, R.id.kDiv,
                R.id.kPow, R.id.kSqrt, R.id.kLP, R.id.kRP, R.id.kPi, R.id.kE};
        String[] texts = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "+", "-", "×",
                "÷", "^", "√", "(", ")", "π", "e"};
        for (int k = 0; k < ids.length; ++k) {
            final String s = texts[k];
            findViewById(ids[k]).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    insert(s);
                }
            });
        }
        findViewById(R.id.kBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backspace();
            }
        });
        findViewById(R.id.kClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                fullResult = "";
                result.setText("");
                note.setText("");
                hideProgress();
            }
        });
        findViewById(R.id.kEq).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                startEval(input.getText().toString(), true);
            }
        });
        findViewById(R.id.kCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyResult();
            }
        });
        findViewById(R.id.kSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (liveBox.isChecked()) scheduleLiveEval();
            }
        });
    }

    @Override
    protected void onDestroy() {
        BigDec.setProgress(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ======================= 输入 =======================

    private void insert(String s) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        if (start > end) {
            int t = start;
            start = end;
            end = t;
        }
        input.getText().replace(start, end, s);
    }

    private void backspace() {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        if (start != end) {
            input.getText().delete(Math.min(start, end), Math.max(start, end));
            return;
        }
        if (start > 0) input.getText().delete(start - 1, start);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void copyResult() {
        String text = fullResult;
        if (text == null || text.length() == 0) {
            Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("计算结果", text));
            Toast.makeText(this, "已复制 " + text.length() + " 个字符", Toast.LENGTH_SHORT).show();
        }
    }

    // ======================= 实时(边输入边算) =======================

    private void scheduleLiveEval() {
        if (pendingEval != null) ui.removeCallbacks(pendingEval);
        pendingEval = new Runnable() {
            @Override
            public void run() {
                if (!computing) startEval(input.getText().toString(), false);
                else scheduleLiveEval();  // 上一次还没算完: 稍后再试
            }
        };
        ui.postDelayed(pendingEval, 320);  // 输入停顿 320ms 再算, 避免每敲一下都算
    }

    private void startEval(final String expr, final boolean explicit) {
        final String text = expr == null ? "" : expr.trim();
        if (text.length() == 0) {
            result.setText("");
            note.setText("");
            fullResult = "";
            hideProgress();
            return;
        }
        final int myGen = ++generation;
        computing = true;
        if (explicit) showProgress("计算中…", -1);

        worker.execute(new Runnable() {
            @Override
            public void run() {
                BigDec value = null;
                String error = null;
                try {
                    value = Calc.evaluate(text, limits);
                } catch (CalcException e) {
                    error = e.getMessage();
                } catch (Throwable e) {
                    error = "内部错误: " + e;
                }
                final BigDec v = value;
                final String err = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        computing = false;
                        if (myGen != generation) return;  // 已有更新的输入, 丢弃
                        showProgressOrResult(v, err);
                    }
                });
            }
        });
    }

    private void showProgressOrResult(BigDec v, String err) {
        if (err != null) {
            hideProgress();
            result.setText("");
            fullResult = "";
            String hint = err.contains("超过安全上限")
                    ? "\n(可在\"设置\"里勾选\"解除位数上限\")" : "";
            note.setText("错误: " + err + hint);
            note.setTextColor(0xFFC62828);
            return;
        }
        if (v == null) {
            hideProgress();
            return;
        }
        String s = v.toString();
        fullResult = s;
        if (s.length() <= SHOW_MAX_CHARS) {
            result.setText(s);
        } else {
            result.setText(s.substring(0, PREVIEW_CHARS)
                    + "\n… …(此处省略 " + (s.length() - PREVIEW_CHARS) + " 个字符)\n… …"
                    + s.substring(s.length() - 200));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(s.length()).append(" 个字符");
        if (v.approx) sb.append(" · 近似值(").append(Calc.reliableOf(v, limits)).append(" 位有效数字)");
        if (v.isZero()) sb.append(" · 零");
        note.setText(sb.toString());
        note.setTextColor(0xFF546E7A);
        hideProgress();
    }

    private void showProgress(String msg, int percent) {
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText(msg);
        if (percent >= 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            progressBar.setProgress(percent);
            lastPercent = percent;
        } else if (lastPercent < 0) {
            progressBar.setIndeterminate(true);
        }
    }

    private void hideProgress() {
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setIndeterminate(true);
        progressText.setVisibility(View.INVISIBLE);
        progressText.setText("");
        lastPercent = -1;
    }

    // ======================= 设置 =======================

    private void loadPrefs() {
        SharedPreferences sp = getPreferences(MODE_PRIVATE);
        limits.precision = sp.getInt("precision", 50);
        limits.maxDigits = sp.getInt("maxDigits", 1000000);
        limits.force = sp.getBoolean("force", false);
    }

    private void savePrefs() {
        SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
        e.putInt("precision", limits.precision);
        e.putInt("maxDigits", limits.maxDigits);
        e.putBoolean("force", limits.force);
        e.apply();
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final EditText pPrec = (EditText) view.findViewById(R.id.setPrecision);
        final EditText pMax = (EditText) view.findViewById(R.id.setMaxDigits);
        final CheckBox pForce = (CheckBox) view.findViewById(R.id.setForce);
        pPrec.setText(String.valueOf(limits.precision));
        pMax.setText(String.valueOf(limits.maxDigits));
        pForce.setChecked(limits.force);

        new AlertDialog.Builder(this)
                .setTitle("计算设置")
                .setView(view)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            int p = Integer.parseInt(pPrec.getText().toString().trim());
                            if (p >= 1 && p <= 20000) limits.precision = p;
                        } catch (Exception ignored) {
                        }
                        try {
                            int m = Integer.parseInt(pMax.getText().toString().trim());
                            if (m >= 100 && m <= 100000000) limits.maxDigits = m;
                        } catch (Exception ignored) {
                        }
                        limits.force = pForce.isChecked();
                        savePrefs();
                        if (liveBox.isChecked()) startEval(input.getText().toString(), false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
