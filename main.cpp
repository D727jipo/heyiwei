// 爱心弹窗程序
// 双击运行后, 按顺序弹出多个"普通窗口样式"的小窗口(有标题栏),
// 客户区底色为纯粉色, 沿爱心曲线等弦长(等距)分布, 依次弹出拼成爱心;
// 全部弹出后停留 5 秒, 再按弹出顺序依次关闭。
//
// 弹出速度: 0.05 秒/个 ; 关闭速度: 0.02 秒/个。
//
// 构建 (MSVC):
//   cl /EHsc /O2 /std:c++17 /utf-8 main.cpp /link user32.lib gdi32.lib /SUBSYSTEM:WINDOWS /ENTRY:wWinMainCRTStartup /OUT:"爱心弹窗.exe"

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <cmath>
#include <vector>
#include <algorithm>

// ---------- 可调参数 ----------
static const COLORREF kPink         = RGB(255, 105, 180); // 纯粉色
static const int     kClientW       = 126;  // 小窗口客户区宽(像素)  (放大, 可显示祝福语)
static const int     kClientH       = 82;   // 小窗口客户区高(像素)
static const double kSpacingRatio   = 0.5;  // 中心间距 = 窗口尺寸*该比例 (<1 即相邻窗口重叠)
static const double kGap            = 4.0;  // 相邻窗口之间的额外间距(像素)
static const double kFitMargin      = 0.99; // 爱心+窗口外框占屏幕的比例(半径尽量大且不溢出)
static const DWORD  kPopDelayMs     = 50;   // 弹出间隔: 0.05 秒/个
static const DWORD  kHoldMs         = 5000; // 全部弹出后停留 5 秒
static const DWORD  kCloseDelayMs   = 20;   // 关闭间隔: 0.02 秒/个
// ------------------------------

static const wchar_t* kClassName = L"HeartPopupWindow";

// 各窗口标题: 各类祝福/提醒语 (窗口数多于语数时循环使用)
static const wchar_t* kTitles[] = {
    L"爱你哟",     L"天天开心",   L"心想事成",   L"万事如意",
    L"平安喜乐",   L"幸福美满",   L"好运连连",   L"笑口常开",
    L"身体健康",   L"前程似锦",   L"梦想成真",   L"温馨甜蜜",
    L"永远幸福",   L"快乐无边",   L"珍惜当下",   L"你最重要",
    L"陪你到老",   L"甜在心头",   L"一帆风顺",   L"吉祥如意",
    L"福星高照",   L"步步高升",   L"事业有成",   L"财源滚滚",
    L"阖家欢乐",   L"青春永驻",   L"心情美丽",   L"好运常伴",
    L"爱你一生",   L"心动时刻",   L"温暖如初",   L"记得微笑",
    L"别熬夜哦",   L"按时吃饭",   L"多喝热水",   L"注意休息",
    L"今天也要加油", L"抱抱你",   L"想你啦",     L"我的小确幸",
};
static const size_t kTitleCount = sizeof(kTitles) / sizeof(kTitles[0]);

struct Cell { HWND hwnd; int cx; int cy; }; // 窗口中心屏幕坐标
static std::vector<Cell> g_cells;

enum class AppState { Popping, Holding, Closing, Done };
static AppState g_state    = AppState::Popping;
static size_t   g_index    = 0;
static DWORD    g_nextTick = 0;
static DWORD    g_holdEnd  = 0;

static void PumpState();

// 爱心参数方程: x = 16 sin^3 t, y = 13cos t - 5cos2t - 2cos3t - cos4t
struct Pt { double x, y; };
static Pt HeartPoint(double t) {
    Pt p;
    p.x = 16.0 * pow(sin(t), 3.0);
    p.y = 13.0 * cos(t) - 5.0 * cos(2.0 * t) - 2.0 * cos(3.0 * t) - cos(4.0 * t);
    return p;
}

static double Dist2(const Pt& a, const Pt& b) {
    double dx = a.x - b.x, dy = a.y - b.y;
    return dx * dx + dy * dy;
}

// 沿折线(两圈, 可跨越起点)以弦长 D 行走一圈, 输出按顺序放置的点
static bool WalkByChord(const std::vector<Pt>& pw, const std::vector<double>& cw,
                        double per, double D, std::vector<Pt>& out) {
    out.clear();
    if (pw.empty()) return false;
    Pt cur = pw.front();
    out.push_back(cur);
    const double D2 = D * D;
    size_t i = 1;
    while (i < pw.size()) {
        while (i < pw.size() && Dist2(pw[i], cur) < D2) ++i; // 前进到距离 >= D
        if (i >= pw.size()) break;
        if (cw[i - 1] >= per) break;                          // 已走满一圈
        // 在线段 pw[i-1] -> pw[i] 上求与 cur 距离恰为 D 的点
        Pt a = pw[i - 1], b = pw[i];
        double dx = b.x - a.x, dy = b.y - a.y;
        double wx = a.x - cur.x, wy = a.y - cur.y;
        double A = dx * dx + dy * dy;
        double B = 2.0 * (wx * dx + wy * dy);
        double C = wx * wx + wy * wy - D2;
        double disc = B * B - 4.0 * A * C;
        double t = 0.0;
        if (disc >= 0.0 && A > 0.0) {
            t = (-B + std::sqrt(disc)) / (2.0 * A);
            if (t < 0.0) t = 0.0;
            if (t > 1.0) t = 1.0;
        }
        cur.x = a.x + dx * t;
        cur.y = a.y + dy * t;
        out.push_back(cur);
    }
    return out.size() >= 4;
}

// 求相邻点间距的最大偏差(闭环)
static double ChordDeviation(const std::vector<Pt>& p) {
    size_t n = p.size();
    if (n < 2) return 1e18;
    double mn = 1e18, mx = -1e18;
    for (size_t i = 0; i < n; ++i) {
        double d = std::sqrt(Dist2(p[i], p[(i + 1) % n]));
        mn = std::min(mn, d); mx = std::max(mx, d);
    }
    return mx - mn;
}

// 等弦长(等欧氏距离)放置: 扫描弦长 D, 选出使"闭环相邻间距最均匀"的方案。
// 该曲线在爱心上下两处存在尖点, 直接行走会在起点处留下接缝(间距不均),
// 因此同时尝试"保留末点"与"丢弃末点"两种闭环方式, 取偏差最小者。
static void PlaceEqualChord(const std::vector<Pt>& pix, const std::vector<double>& cum,
                            double per, double Dtarget, std::vector<Pt>& placed) {
    placed.clear();
    const int M = static_cast<int>(pix.size()) - 1;

    // 构造两圈折线, 行走时可跨越起点(闭环)
    std::vector<Pt> pw(2 * M + 1);
    std::vector<double> cw(2 * M + 1);
    for (int i = 0; i <= 2 * M; ++i) {
        int k = i % M;
        pw[i] = pix[k];
        cw[i] = cum[k] + static_cast<double>(i / M) * per;
    }

    // 扫描弦长 D: 在"闭环且间距均匀(偏差<=tol)"的候选中, 选最小的 D 以获得最多窗口;
    // 若没有满足 tol 的候选, 则退而选偏差最小者。
    const double kTol = 0.20;          // 相邻间距最大允许偏差(像素)
    bool   haveClosed = false;
    double closedD    = 1e18;
    std::vector<Pt> closedPts;
    double bestDev    = 1e18;
    std::vector<Pt> bestPts;

    const int kSteps = 600;
    for (int s = 0; s <= kSteps; ++s) {
        double D = Dtarget * (0.70 + 0.60 * s / kSteps);
        std::vector<Pt> p;
        if (!WalkByChord(pw, cw, per, D, p)) continue;

        double dev1 = ChordDeviation(p);                 // 选项1: 保留末点
        if (dev1 <= kTol && D < closedD) { closedD = D; closedPts = p; haveClosed = true; }
        if (dev1 < bestDev) { bestDev = dev1; bestPts = p; }

        if (p.size() > 5) {                              // 选项2: 丢弃末点(消除接缝)
            std::vector<Pt> q(p.begin(), p.end() - 1);
            double dev2 = ChordDeviation(q);
            if (dev2 <= kTol && D < closedD) { closedD = D; closedPts = q; haveClosed = true; }
            if (dev2 < bestDev) { bestDev = dev2; bestPts = q; }
        }
    }
    placed = haveClosed ? closedPts : bestPts;

    // 兜底: 若扫描未得到有效结果, 用等弧长放置
    if (placed.size() < 4) {
        int N = std::max(8, static_cast<int>(per / Dtarget + 0.5));
        placed.clear();
        for (int k = 0; k < N; ++k) {
            double target = per * k / N;
            size_t j = static_cast<size_t>(
                std::lower_bound(cum.begin(), cum.end(), target) - cum.begin());
            if (j == 0) j = 1;
            if (j > static_cast<size_t>(M)) j = M;
            double seg = cum[j] - cum[j - 1];
            double f = seg > 0.0 ? (target - cum[j - 1]) / seg : 0.0;
            placed.push_back(Pt{ pix[j - 1].x + (pix[j].x - pix[j - 1].x) * f,
                                 pix[j - 1].y + (pix[j].y - pix[j - 1].y) * f });
        }
    }
}

// 生成等距分布的爱心窗口中心点(屏幕坐标)
static void BuildHeartCells(std::vector<Cell>& out, int screenW, int screenH) {
    out.clear();

    // 1. 精细采样爱心曲线(heart 单位)并求包围盒
    const int M = 8000;
    const double PI = 3.14159265358979323846;
    std::vector<Pt> h(M + 1);
    double minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
    for (int i = 0; i <= M; ++i) {
        double t = 2.0 * PI * i / M;
        h[i] = HeartPoint(t);
        if (h[i].x < minx) minx = h[i].x;
        if (h[i].x > maxx) maxx = h[i].x;
        if (h[i].y < miny) miny = h[i].y;
        if (h[i].y > maxy) maxy = h[i].y;
    }
    double boxW = maxx - minx, boxH = maxy - miny;
    double bcx = (minx + maxx) / 2.0, bcy = (miny + maxy) / 2.0;
    double halfBoxW = boxW / 2.0, halfBoxH = boxH / 2.0;

    // 2. 计算实际窗口外框尺寸(含标题栏/边框)
    RECT rc{ 0, 0, kClientW, kClientH };
    AdjustWindowRectEx(&rc, WS_OVERLAPPEDWINDOW, FALSE, 0);
    int fullW = rc.right - rc.left;
    int fullH = rc.bottom - rc.top;

    // 3. 按屏幕大小确定缩放(半径尽量大), 并保证"爱心+窗口外框"不溢出屏幕:
    //    scale*halfBox + full/2 <= screen*fit/2  (对宽/高取较小者)
    double availHalfW = screenW * kFitMargin / 2.0 - fullW / 2.0;
    double availHalfH = screenH * kFitMargin / 2.0 - fullH / 2.0;
    double scale = std::min(availHalfW / halfBoxW, availHalfH / halfBoxH);
    if (scale < 1.0) scale = 1.0;

    // 4. 转为屏幕像素坐标, 以爱心包围盒中心对齐屏幕中心
    std::vector<Pt> pix(M + 1);
    std::vector<double> cum(M + 1);
    double scx = screenW / 2.0, scy = screenH / 2.0;
    cum[0] = 0.0;
    for (int i = 0; i <= M; ++i) {
        pix[i].x = scx + (h[i].x - bcx) * scale;
        pix[i].y = scy - (h[i].y - bcy) * scale; // y 向下, 取负使爱心正立
        if (i > 0) cum[i] = cum[i - 1] + std::sqrt(Dist2(pix[i], pix[i - 1]));
    }
    double per = cum[M];

    // 5. 相邻窗口中心的目标直线距离
    //    间距 = 最大外框尺寸 * kSpacingRatio + kGap
    //    kSpacingRatio < 1 时相邻窗口重叠, 因此可以在同一爱心上放置更多窗口
    double Dtarget = static_cast<double>(std::max(fullW, fullH)) * kSpacingRatio + kGap;

    // 6. 闭环等弦长放置
    std::vector<Pt> placed;
    PlaceEqualChord(pix, cum, per, Dtarget, placed);

    // 7. 输出 Cell
    for (size_t i = 0; i < placed.size(); ++i) {
        out.push_back(Cell{ nullptr,
                            static_cast<int>(placed[i].x),
                            static_cast<int>(placed[i].y) });
    }
}

// 创建一个普通样式的小窗口(初始隐藏), 标题取祝福语
static HWND CreateCellWindow(HINSTANCE hInst, int centerX, int centerY, size_t idx) {
    RECT rc{ 0, 0, kClientW, kClientH };
    AdjustWindowRectEx(&rc, WS_OVERLAPPEDWINDOW, FALSE, 0);
    int fullW = rc.right - rc.left;
    int fullH = rc.bottom - rc.top;
    int left = centerX - fullW / 2 - rc.left;
    int top  = centerY - fullH / 2 - rc.top;

    const wchar_t* title = kTitles[idx % kTitleCount];
    HWND hwnd = CreateWindowExW(
        WS_EX_TOPMOST,                 // 置顶(不改变外观), 保证爱心可见
        kClassName, title,
        WS_OVERLAPPEDWINDOW,           // 普通窗口样式: 标题栏/系统菜单/最小化最大化
        left, top, fullW, fullH,
        nullptr, nullptr, hInst, nullptr);
    return hwnd;
}

// 窗口过程: 客户区底色填充纯粉色
static LRESULT CALLBACK CellWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_ERASEBKGND: {
            HDC hdc = (HDC)wp;
            RECT rc; GetClientRect(hwnd, &rc);
            HBRUSH br = CreateSolidBrush(kPink);
            FillRect(hdc, &rc, br);
            DeleteObject(br);
            return 1;
        }
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            RECT rc; GetClientRect(hwnd, &rc);
            HBRUSH br = CreateSolidBrush(kPink);
            FillRect(hdc, &rc, br);
            DeleteObject(br);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_TIMER:
            PumpState();
            return 0;
        case WM_DESTROY:
            return 0;
        default:
            return DefWindowProcW(hwnd, msg, wp, lp);
    }
}

// 状态机推进
static void PumpState() {
    DWORD now = static_cast<DWORD>(GetTickCount64());

    switch (g_state) {
        case AppState::Popping: {
            if (now < g_nextTick) break;
            if (g_index < g_cells.size()) {
                HWND h = g_cells[g_index].hwnd;
                if (h) {
                    ShowWindow(h, SW_SHOWNOACTIVATE);
                    UpdateWindow(h);
                }
                ++g_index;
                g_nextTick = now + kPopDelayMs;
            } else {
                g_state   = AppState::Holding;
                g_holdEnd = now + kHoldMs;
            }
            break;
        }
        case AppState::Holding: {
            if (now >= g_holdEnd) {
                g_state    = AppState::Closing;
                g_index    = 0;
                g_nextTick = now;
            }
            break;
        }
        case AppState::Closing: {
            if (now < g_nextTick) break;
            if (g_index < g_cells.size()) {
                HWND h = g_cells[g_index].hwnd;
                if (h) {
                    ShowWindow(h, SW_HIDE);
                    DestroyWindow(h);
                    g_cells[g_index].hwnd = nullptr;
                }
                ++g_index;
                g_nextTick = now + kCloseDelayMs;
            } else {
                g_state = AppState::Done;
                PostQuitMessage(0);
            }
            break;
        }
        case AppState::Done:
            break;
    }
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, LPWSTR, int) {
    WNDCLASSEXW wc = { sizeof(wc) };
    wc.lpfnWndProc   = CellWndProc;
    wc.hInstance     = hInstance;
    wc.lpszClassName = kClassName;
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = CreateSolidBrush(kPink);
    RegisterClassExW(&wc);

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);

    BuildHeartCells(g_cells, screenW, screenH);

    // 先创建所有窗口(隐藏), 以便后续按顺序显示
    for (size_t i = 0; i < g_cells.size(); ++i) {
        g_cells[i].hwnd = CreateCellWindow(hInstance, g_cells[i].cx, g_cells[i].cy, i);
        if (g_cells[i].hwnd) ShowWindow(g_cells[i].hwnd, SW_HIDE);
    }

    // 隐藏宿主窗口承载定时器(10ms, 足以分辨 20ms 的关闭间隔)
    HWND hHost = CreateWindowExW(0, kClassName, L"", WS_POPUP,
                                 0, 0, 0, 0, nullptr, nullptr, hInstance, nullptr);
    SetTimer(hHost, 1, 10, nullptr);

    g_state    = AppState::Popping;
    g_index    = 0;
    g_nextTick = static_cast<DWORD>(GetTickCount64()); // 立刻弹出第一个

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}
