package com.dsh.calc12;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.calc.BigDec;
import com.dsh.calc.Calc;
import com.dsh.calc.CalcException;
import com.dsh.calc.Limits;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 科学计算器横屏界面(通过普通界面上的「科学」按钮进入)。
 *
 * <p>与普通模式完全一致的计算体验:
 * <ul>
 *   <li>计算在后台线程执行, 界面不卡死</li>
 *   <li>大数运算时实时显示进度(进度条 + 百分比), 与普通模式同一套回调</li>
 *   <li>超长结果只渲染头尾预览, 「查看全部数字」可分页浏览/复制/导出</li>
 * </ul>
 * 复用同一套 {@link Calc}/{@link BigDec} 核心, 大数精确计算能力完全保留。
 */
public class SciActivity extends Activity {

    private static final int SHOW_MAX_CHARS = 4000;
    /** 完整结果查看器每页字符数 */
    private static final int PAGE_CHARS = 50000;
    private static final int CLIPBOARD_WARN = 100000;
    private static final int REQ_EXPORT = 2001;

    private EditText input;
    private TextView result;
    private TextView note;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button viewAll;
    private View root;

    private Limits limits = new Limits();
    private BigDec lastValue = null;   // 最近一次计算结果(供 M+/M− 使用)
    private BigDec memory = null;      // 记忆存储
    private boolean degMode = false;   // false=Rad 弧度制, true=Deg 角度制
    private boolean invMode = false;   // Inv: 切换反函数/指数
    private String fullResult = "";
    private boolean resultTruncated = false;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int generation = 0;
    private volatile boolean computing = false;
    private volatile int lastPercent = -1;

    private final BigDec.Progress progressCallback = new BigDec.Progress() {
        @Override
        public void onProgress(final String msg, final int percent) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    showProgress(msg, percent);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scientific);

        limits.precision = getSharedPreferences("MainActivity", MODE_PRIVATE)
                .getInt("precision", 50);
        limits.maxDigits = getSharedPreferences("MainActivity", MODE_PRIVATE)
                .getInt("maxDigits", 1000000);
        limits.force = getSharedPreferences("MainActivity", MODE_PRIVATE)
                .getBoolean("force", false);

        root = findViewById(R.id.sciRoot);
        input = (EditText) findViewById(R.id.sciInput);
        result = (TextView) findViewById(R.id.sciResult);
        note = (TextView) findViewById(R.id.sciNote);
        progressText = (TextView) findViewById(R.id.sciProgressText);
        progressBar = (ProgressBar) findViewById(R.id.sciProgress);
        viewAll = (Button) findViewById(R.id.sciViewAll);
        viewAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFullResult();
            }
        });

        applyEdgeToEdge();

        // ---- 左侧函数区: 插入文本在点击时按 Inv/Deg 状态动态决定 ----
        bindInsert(R.id.skLP, "(");
        bindInsert(R.id.skRP, ")");
        bindInsert(R.id.skRecip, "1/(");
        bindInsert(R.id.skSq, "^2");
        bindInsert(R.id.skCube, "^3");
        bindInsert(R.id.skPowY, "^");
        bindInsert(R.id.skFact, "fact(");
        bindInsert(R.id.skSqrt, "sqrt(");
        bindInsert(R.id.skRootY, "^(1/");
        bindInsert(R.id.skE, "e");
        bindInsert(R.id.skPi, "π");
        bindDynamic(R.id.skSin);
        bindDynamic(R.id.skCos);
        bindDynamic(R.id.skTan);
        bindDynamic(R.id.skLn);
        bindDynamic(R.id.skLog);

        findViewById(R.id.skInv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleInv();
            }
        });
        findViewById(R.id.skRadDeg).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleDeg();
            }
        });

        // ---- 右侧数字区 ----
        int[] ids = {R.id.sk0, R.id.sk1, R.id.sk2, R.id.sk3, R.id.sk4, R.id.sk5,
                R.id.sk6, R.id.sk7, R.id.sk8, R.id.sk9, R.id.skDot,
                R.id.skPlus, R.id.skMinus, R.id.skMul, R.id.skDiv, R.id.skPercent};
        String[] texts = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".",
                "+", "−", "×", "÷", "/100"};
        for (int k = 0; k < ids.length; ++k) {
            bindInsert(ids[k], texts[k]);
        }

        findViewById(R.id.skBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backspace();
            }
        });
        findViewById(R.id.skClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
                clearResult();
            }
        });
        findViewById(R.id.skEq).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                startEval(input.getText().toString(), true);
            }
        });

        // ---- 记忆键 ----
        findViewById(R.id.skMC).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                memory = null;
                Toast.makeText(SciActivity.this, "记忆已清除", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.skMPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                memoryAdd(false);
            }
        });
        findViewById(R.id.skMMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                memoryAdd(true);
            }
        });
        findViewById(R.id.skMR).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (memory == null) {
                    Toast.makeText(SciActivity.this, "记忆为空", Toast.LENGTH_SHORT).show();
                } else {
                    insert(memory.toString());
                }
            }
        });

        findViewById(R.id.sciExit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置(精度/位数上限/force)可能在本界面关闭期间被修改, 回到前台时重新读取
        android.content.SharedPreferences sp = getSharedPreferences("MainActivity", MODE_PRIVATE);
        limits.precision = sp.getInt("precision", 50);
        limits.maxDigits = sp.getInt("maxDigits", 1000000);
        limits.force = sp.getBoolean("force", false);
        // 进度回调是进程级单例: 普通界面和本界面共用, 谁在前台谁持有
        BigDec.setProgress(progressCallback);
    }

    @Override
    protected void onDestroy() {
        BigDec.setProgress(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    // ======================= 边距适配 =======================

    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        final int base = (int) (10 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom, left, right;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                    left = bars.left;
                    right = bars.right;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                }
                v.setPadding(left + base / 2, top + base / 2, right + base / 2, bottom + base);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    // ======================= 输入 =======================

    private void bindInsert(int id, final String text) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insert(text);
            }
        });
    }

    /** sin/cos/tan/ln/log: 插入文本随 Inv/Deg 状态变化 */
    private void bindDynamic(final int id) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insert(dynamicTextOf(id));
            }
        });
    }

    private String dynamicTextOf(int id) {
        switch (id) {
            case R.id.skSin: return trigText("sin");
            case R.id.skCos: return trigText("cos");
            case R.id.skTan: return trigText("tan");
            case R.id.skLn:  return invMode ? "exp(" : "ln(";
            case R.id.skLog: return invMode ? "10^(" : "log10(";
            default: return "";
        }
    }

    /** Deg 模式下自动包一层 rad()/deg() 转换; 求值时缺右括号会自动补全 */
    private String trigText(String base) {
        if (!degMode) return base + "(";
        if (base.startsWith("a")) return "deg(" + base + "(";
        return base + "(rad(";
    }

    private void toggleInv() {
        invMode = !invMode;
        ((Button) findViewById(R.id.skSin)).setText(invMode ? "asin" : "sin");
        ((Button) findViewById(R.id.skCos)).setText(invMode ? "acos" : "cos");
        ((Button) findViewById(R.id.skTan)).setText(invMode ? "atan" : "tan");
        ((Button) findViewById(R.id.skLn)).setText(invMode ? "eˣ" : "ln");
        ((Button) findViewById(R.id.skLog)).setText(invMode ? "10ˣ" : "log");
    }

    private void toggleDeg() {
        degMode = !degMode;
        ((Button) findViewById(R.id.skRadDeg)).setText(degMode ? "Deg" : "Rad");
        Toast.makeText(this, degMode ? "角度制(Deg)" : "弧度制(Rad)", Toast.LENGTH_SHORT).show();
    }

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
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** 补全缺失的右括号: Deg 模式的 sin(rad(30 这类输入按 = 直接得到正确结果 */
    private String autoCloseParens(String expr) {
        int open = 0;
        for (int i = 0; i < expr.length(); ++i) {
            char c = expr.charAt(i);
            if (c == '(') ++open;
            else if (c == ')' && open > 0) --open;
        }
        if (open <= 0) return expr;
        StringBuilder sb = new StringBuilder(expr);
        for (int i = 0; i < open; ++i) sb.append(')');
        return sb.toString();
    }

    // ======================= 记忆 =======================

    private void memoryAdd(boolean subtract) {
        if (lastValue == null) {
            Toast.makeText(this, "还没有结果, 先按 = 计算一次", Toast.LENGTH_SHORT).show();
            return;
        }
        BigDec v = lastValue;
        if (subtract && !v.isZero()) {
            v = v.copy();
            v.neg = !v.neg;
        }
        memory = (memory == null) ? v : BigDec.add(memory, v, limits);
        Toast.makeText(this, (subtract ? "M− 已存入: " : "M+ 已存入: ") + memory,
                Toast.LENGTH_SHORT).show();
    }

    // ======================= 计算(后台线程 + 实时进度) =======================

    private void startEval(final String expr, final boolean explicit) {
        final String text = autoCloseParens(expr == null ? "" : expr.trim());
        if (text.length() == 0) {
            clearResult();
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
                        if (myGen != generation) return;
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
            resultTruncated = false;
            lastValue = null;
            viewAll.setVisibility(View.GONE);
            String hint = err.contains("超过安全上限") ? "\n(可在普通模式菜单→计算设置里勾选\"解除位数上限\")" : "";
            note.setText("错误: " + err + hint);
            note.setTextColor(getResources().getColor(R.color.md_error));
            return;
        }
        if (v == null) {
            hideProgress();
            return;
        }
        lastValue = v;
        String s = v.toString();
        fullResult = s;
        resultTruncated = s.length() > SHOW_MAX_CHARS;
        if (!resultTruncated) {
            result.setText(s);
            result.setTextIsSelectable(true);
            result.setOnClickListener(null);
            viewAll.setVisibility(View.GONE);
        } else {
            // 横屏空间有限: 只渲染头尾各 100 字符, 完整数字用「查看全部数字」分页浏览
            result.setText(s.substring(0, 100)
                    + "\n… …(中间省略 " + (s.length() - 200) + " 个字符)… …\n… …"
                    + s.substring(s.length() - 100));
            result.setTextIsSelectable(false);
            result.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFullResult();
                }
            });
            viewAll.setVisibility(View.VISIBLE);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(s.length()).append(" 个字符");
        if (v.approx) sb.append(" · 近似值(").append(Calc.reliableOf(v, limits)).append(" 位有效数字)");
        if (resultTruncated) {
            sb.append("\n上方只是头尾预览; 点「查看全部数字」可逐页浏览完整数字");
        }
        note.setText(sb.toString());
        note.setTextColor(getResources().getColor(R.color.md_on_surface_variant));
        hideProgress();
    }

    private void clearResult() {
        fullResult = "";
        resultTruncated = false;
        lastValue = null;
        result.setText("");
        note.setText("");
        viewAll.setVisibility(View.GONE);
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

    // ======================= 复制/导出/完整结果查看器 =======================

    private void copyResult() {
        if (fullResult == null || fullResult.length() == 0) {
            Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        try {
            cm.setPrimaryClip(ClipData.newPlainText("计算结果", fullResult));
            String msg = "已复制全部 " + fullResult.length() + " 个字符";
            if (fullResult.length() > CLIPBOARD_WARN)
                msg += "\n(文本很长, 若粘贴出来不完整请用「导出到文件」)";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "剪贴板放不下这么长的文本(" + fullResult.length()
                    + " 字符), 请用「导出到文件」", Toast.LENGTH_LONG).show();
        }
    }

    private void exportResult() {
        if (fullResult == null || fullResult.length() == 0) {
            Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "计算结果-" + fullResult.length() + "位.txt");
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件保存对话框: " + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String text = fullResult;
        Toast.makeText(this, "正在导出 " + text.length() + " 个字符…", Toast.LENGTH_SHORT).show();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    OutputStream os = getContentResolver().openOutputStream(uri, "wt");
                    if (os == null) throw new IllegalStateException("无法写入所选文件");
                    Writer w = new OutputStreamWriter(os, "UTF-8");
                    w.write(text);
                    w.flush();
                    w.close();
                } catch (Throwable t) {
                    err = String.valueOf(t);
                }
                final String e = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (e == null)
                            Toast.makeText(SciActivity.this,
                                    "已导出 " + text.length() + " 个字符", Toast.LENGTH_LONG).show();
                        else
                            Toast.makeText(SciActivity.this, "导出失败: " + e, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 分页浏览完整数字(与普通模式一致) */
    private void showFullResult() {
        if (fullResult == null || fullResult.length() == 0) {
            Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_full_result);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }

        final TextView info = (TextView) dialog.findViewById(R.id.frInfo);
        final TextView text = (TextView) dialog.findViewById(R.id.frText);
        final Button first = (Button) dialog.findViewById(R.id.frFirst);
        final Button prev = (Button) dialog.findViewById(R.id.frPrev);
        final Button next = (Button) dialog.findViewById(R.id.frNext);
        final Button last = (Button) dialog.findViewById(R.id.frLast);
        final Button copyPage = (Button) dialog.findViewById(R.id.frCopyPage);
        final int pages = (fullResult.length() + PAGE_CHARS - 1) / PAGE_CHARS;
        final int[] page = {0};

        final Runnable render = new Runnable() {
            @Override
            public void run() {
                int from = page[0] * PAGE_CHARS;
                int to = Math.min(fullResult.length(), from + PAGE_CHARS);
                text.setText(fullResult.substring(from, to));
                info.setText("共 " + fullResult.length() + " 个字符 · 第 " + (page[0] + 1)
                        + " / " + pages + " 页 (每页 " + PAGE_CHARS + " 字符)");
                first.setEnabled(page[0] > 0);
                prev.setEnabled(page[0] > 0);
                next.setEnabled(page[0] < pages - 1);
                last.setEnabled(page[0] < pages - 1);
                first.setAlpha(page[0] > 0 ? 1f : 0.4f);
                prev.setAlpha(page[0] > 0 ? 1f : 0.4f);
                next.setAlpha(page[0] < pages - 1 ? 1f : 0.4f);
                last.setAlpha(page[0] < pages - 1 ? 1f : 0.4f);
            }
        };
        render.run();

        first.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { page[0] = 0; render.run(); }
        });
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { if (page[0] > 0) --page[0]; render.run(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { if (page[0] < pages - 1) ++page[0]; render.run(); }
        });
        last.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { page[0] = pages - 1; render.run(); }
        });
        copyPage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int from = page[0] * PAGE_CHARS;
                int to = Math.min(fullResult.length(), from + PAGE_CHARS);
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("计算结果",
                            fullResult.substring(from, to)));
                    Toast.makeText(SciActivity.this,
                            "已复制本页 " + (to - from) + " 个字符", Toast.LENGTH_SHORT).show();
                }
            }
        });
        ((Button) dialog.findViewById(R.id.frCopyAll)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { copyResult(); }
        });
        ((Button) dialog.findViewById(R.id.frExport)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { exportResult(); }
        });
        ((Button) dialog.findViewById(R.id.frClose)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dialog.dismiss(); }
        });
        dialog.show();
    }
}
