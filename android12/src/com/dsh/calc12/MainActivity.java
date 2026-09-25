package com.dsh.calc12;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.calc.BigDec;
import com.dsh.calc.Calc;
import com.dsh.calc.CalcException;
import com.dsh.calc.Limits;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大数计算器 · Android 12 (Material You) 界面。
 *
 * <p>界面只用系统自带能力, 不引入任何第三方库:
 * <ul>
 *   <li>{@code Theme.DeviceDefault.DayNight} + 系统动态取色(壁纸配色) —— Material You 观感</li>
 *   <li>Android 12 的 SplashScreen 主题属性(开屏画面)</li>
 *   <li>RippleDrawable 水波纹、ShapeDrawable 大圆角、elevation 卡片</li>
 *   <li>edge-to-edge + WindowInsets 适配(状态栏/导航栏/刘海)</li>
 *   <li>PopupMenu(原生) 做设置入口</li>
 * </ul>
 *
 * <p>计算语义与桌面版 calc.exe 一致(同一套 {@link Calc}/{@link BigDec} 核心)。
 */
public class MainActivity extends Activity {

    private static final int SHOW_MAX_CHARS = 4000;
    private static final int PREVIEW_CHARS = 2000;

    /** 背景颜色偏好: 0=跟随系统, 1=白色(浅色), 2=深色 */
    private static final int THEME_FOLLOW = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_DARK = 2;

    private static final String PREFS = "MainActivity";

    private EditText input;
    private TextView result;
    private TextView note;
    private TextView progressText;
    private ProgressBar progressBar;
    private CheckBox liveBox;
    private View root;

    private Limits limits = new Limits();
    private String fullResult = "";
    private int themeMode = THEME_FOLLOW;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int generation = 0;
    private Runnable pendingEval;
    private volatile boolean computing = false;
    private volatile int lastPercent = -1;

    /**
     * 背景颜色: 跟随系统时不做任何覆盖; 手动选白色/深色时只给本 Activity 套一份
     * 覆盖了 uiMode 的 Configuration(平台自带能力, 不需要 AppCompat)。
     * 这样 values-night / values-v31 等资源都会按所选模式重新解析。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        int mode = newBase.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt("themeMode", THEME_FOLLOW);
        if (mode != THEME_LIGHT && mode != THEME_DARK) {
            super.attachBaseContext(newBase);
            return;
        }
        Configuration cfg = new Configuration(newBase.getResources().getConfiguration());
        int night = (mode == THEME_DARK)
                ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        super.attachBaseContext(newBase.createConfigurationContext(cfg));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        input = (EditText) findViewById(R.id.input);
        result = (TextView) findViewById(R.id.result);
        note = (TextView) findViewById(R.id.note);
        progressText = (TextView) findViewById(R.id.progressText);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        liveBox = (CheckBox) findViewById(R.id.live);

        applyEdgeToEdge();
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
                clearResult();
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
        findViewById(R.id.kMenu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
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

    // ======================= Android 12 全屏/沉浸式适配 =======================

    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().setStatusBarColor(0x00000000);
        }
        final int basePad = (int) (16 * getResources().getDisplayMetrics().density);
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
                v.setPadding(left + basePad, top + basePad / 2, right + basePad, bottom + basePad);
                return insets;
            }
        });
        root.requestApplyInsets();
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
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void clearResult() {
        fullResult = "";
        result.setText("");
        note.setText("");
        hideProgress();
    }

    private void copyResult() {
        if (fullResult == null || fullResult.length() == 0) {
            Toast.makeText(this, "还没有结果", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("计算结果", fullResult));
            Toast.makeText(this, "已复制 " + fullResult.length() + " 个字符", Toast.LENGTH_SHORT).show();
        }
    }

    // ======================= 原生 PopupMenu(现代化菜单) =======================

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.menu_settings);
        menu.getMenu().add(0, 2, 1, R.string.menu_copy);
        menu.getMenu().add(0, 3, 2, R.string.menu_about);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        showSettings();
                        return true;
                    case 2:
                        copyResult();
                        return true;
                    default:
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.menu_about)
                                .setMessage(R.string.about_text)
                                .setPositiveButton("好", null)
                                .show();
                        return true;
                }
            }
        });
        menu.show();
    }

    // ======================= 实时(边输入边算) =======================

    private void scheduleLiveEval() {
        if (pendingEval != null) ui.removeCallbacks(pendingEval);
        pendingEval = new Runnable() {
            @Override
            public void run() {
                if (!computing) startEval(input.getText().toString(), false);
                else scheduleLiveEval();
            }
        };
        ui.postDelayed(pendingEval, 320);
    }

    private void startEval(final String expr, final boolean explicit) {
        final String text = expr == null ? "" : expr.trim();
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
            String hint = err.contains("超过安全上限") ? "\n(可在菜单→计算设置里勾选\"解除位数上限\")" : "";
            note.setText("错误: " + err + hint);
            note.setTextColor(getResources().getColor(R.color.md_error));
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
        note.setText(sb.toString());
        note.setTextColor(getResources().getColor(R.color.md_on_surface_variant));
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
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        limits.precision = sp.getInt("precision", 50);
        limits.maxDigits = sp.getInt("maxDigits", 1000000);
        limits.force = sp.getBoolean("force", false);
        themeMode = sp.getInt("themeMode", THEME_FOLLOW);
    }

    private void savePrefs() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putInt("precision", limits.precision);
        e.putInt("maxDigits", limits.maxDigits);
        e.putBoolean("force", limits.force);
        e.putInt("themeMode", themeMode);
        e.apply();
    }

    /** 只有"手动选择"时才允许选白色/深色 */
    private void updateThemeColorEnabled(RadioGroup mode, View[] colorViews) {
        boolean manual = mode.getCheckedRadioButtonId() == R.id.setThemeManual;
        for (View v : colorViews) {
            v.setEnabled(manual);
            v.setAlpha(manual ? 1f : 0.4f);
        }
    }

    private String themeName(int mode) {
        if (mode == THEME_LIGHT) return getString(R.string.theme_white);
        if (mode == THEME_DARK) return getString(R.string.theme_dark);
        return getString(R.string.theme_follow);
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        final EditText pPrec = (EditText) view.findViewById(R.id.setPrecision);
        final EditText pMax = (EditText) view.findViewById(R.id.setMaxDigits);
        final CheckBox pForce = (CheckBox) view.findViewById(R.id.setForce);
        final RadioGroup thMode = (RadioGroup) view.findViewById(R.id.setThemeMode);
        final RadioGroup thColor = (RadioGroup) view.findViewById(R.id.setThemeColor);
        final View thLabel = view.findViewById(R.id.setThemeColorLabel);

        pPrec.setText(String.valueOf(limits.precision));
        pMax.setText(String.valueOf(limits.maxDigits));
        pForce.setChecked(limits.force);

        thMode.check(themeMode == THEME_FOLLOW ? R.id.setThemeSystem : R.id.setThemeManual);
        thColor.check(themeMode == THEME_DARK ? R.id.setThemeDark : R.id.setThemeWhite);
        final View[] colorViews = {thColor, thLabel};
        updateThemeColorEnabled(thMode, colorViews);
        thMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int checkedId) {
                updateThemeColorEnabled(g, colorViews);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_settings)
                .setView(view)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
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

                        int newTheme;
                        if (thMode.getCheckedRadioButtonId() == R.id.setThemeSystem) {
                            newTheme = THEME_FOLLOW;
                        } else {
                            newTheme = (thColor.getCheckedRadioButtonId() == R.id.setThemeDark)
                                    ? THEME_DARK : THEME_LIGHT;
                        }
                        boolean themeChanged = (newTheme != themeMode);
                        themeMode = newTheme;
                        savePrefs();
                        if (themeChanged) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.theme_toast, themeName(newTheme)),
                                    Toast.LENGTH_SHORT).show();
                            recreate();  // 重新套用配置(背景/配色/主题)
                        } else if (liveBox.isChecked()) {
                            startEval(input.getText().toString(), false);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
