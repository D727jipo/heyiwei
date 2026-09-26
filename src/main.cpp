// main.cpp -- 纯命令行计算器
// 支持: + - * / , 开平方 √(sqrt) , 次方 ^ (**) , 括号, pi/e 常量
// 大数运算: 任意位数, 次方不受 double 范围限制
// 实时输出: 长耗时计算(huge 次方/乘法/开方/输出)会实时显示"算到哪了"的进度
#include "bigdec.h"
#include "scifunc.h"
#include "mathconst.h"   // pi/e 高精度常量(20102 位), 覆盖 precision 上限 20000

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#endif

using namespace calc;

static const char* APP_VERSION = "3.4";

// ============================ 字符串小工具 ============================

static std::string toLowerA(std::string s) {
    for (char& c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}

static std::string trimA(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && (unsigned char)s[a] <= ' ') ++a;
    while (b > a && (unsigned char)s[b - 1] <= ' ') --b;
    return s.substr(a, b - a);
}

static bool isAllDigits(const std::string& s) {
    if (s.empty()) return false;
    for (char c : s)
        if (!std::isdigit((unsigned char)c)) return false;
    return true;
}

// 分块写出并立即 flush: 超大结果也能被管道/终端实时地看到, 而不是憋到最后一口气输出
static void writeChunked(const std::string& s, size_t chunk = 65536) {
    if (s.size() <= chunk) {
        std::cout << s;
        std::cout.flush();
        return;
    }
    for (size_t i = 0; i < s.size(); i += chunk) {
        size_t n = std::min(chunk, s.size() - i);
        std::cout.write(s.data() + (std::streamsize)i, (std::streamsize)n);
        std::cout.flush();
    }
}

#ifdef _WIN32
static std::string wideToUtf8(const std::wstring& w) {
    if (w.empty()) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr);
    return s;
}
#endif

static void setupConsole() {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

// ============================ 实时进度输出 ============================
// 长耗时计算时在 stderr 上实时显示"算到哪一步了"。stdout 只放结果, 便于脚本使用。

static bool g_quiet = false;      // -q / --quiet 或 quiet on
static bool g_progressInPlace = false;  // stderr 是支持 ANSI 的控制台时可以原地刷新一行

static void setupProgress() {
#ifdef _WIN32
    HANDLE hErr = GetStdHandle(STD_ERROR_HANDLE);
    DWORD mode = 0;
    bool isConsole = (hErr != INVALID_HANDLE_VALUE) && GetConsoleMode(hErr, &mode);
    if (isConsole) {
        HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
        DWORD om = 0;
        if (hOut != INVALID_HANDLE_VALUE && GetConsoleMode(hOut, &om) &&
            SetConsoleMode(hOut, om | ENABLE_VIRTUAL_TERMINAL_PROCESSING)) {
            g_progressInPlace = true;   // 可以覆盖同一行刷新进度
        }
    }
#endif
    setProgress([](const std::string& msg, bool force) {
        if (g_quiet) return;
        static auto last = std::chrono::steady_clock::now() - std::chrono::seconds(10);
        auto now = std::chrono::steady_clock::now();
        if (!force &&
            std::chrono::duration_cast<std::chrono::milliseconds>(now - last).count() < 100)
            return;  // 节流: 非强制消息最快 100ms 一条
        last = now;
        if (g_progressInPlace)
            std::cerr << "\r\x1b[2K[进度] " << msg << std::flush;
        else
            std::cerr << "[进度] " << msg << "\n" << std::flush;
    });
}

// 结果即将写到 stdout, 先擦掉进度行, 避免和结果混在同一行
static void progressClear() {
    if (g_quiet || !g_progressInPlace) return;
    std::cerr << "\r\x1b[2K" << std::flush;
}

static bool stdinIsTty() {
#ifdef _WIN32
    DWORD mode = 0;
    HANDLE h = GetStdHandle(STD_INPUT_HANDLE);
    if (h == nullptr || h == INVALID_HANDLE_VALUE) return false;
    return GetConsoleMode(h, &mode) != 0;
#else
    return isatty(0) != 0;
#endif
}

// ============================ 词法分析 ============================

enum class Tok { Num, Ident, Plus, Minus, Star, Slash, Caret, LParen, RParen, Comma, Sqrt, End };

struct Token {
    Tok k = Tok::End;
    std::string text;
    BigDec num;
};

static std::vector<Token> tokenize(const std::string& s) {
    std::vector<Token> out;
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { ++i; continue; }

        // 数字: 123 / 1.5 / .5 / 1.5e10
        if (std::isdigit(c) || (c == '.' && i + 1 < n && std::isdigit((unsigned char)s[i + 1]))) {
            size_t j = i;
            bool dot = false;
            while (j < n && (std::isdigit((unsigned char)s[j]) || s[j] == '.')) {
                if (s[j] == '.') { if (dot) break; dot = true; }
                ++j;
            }
            if (j < n && (s[j] == 'e' || s[j] == 'E')) {
                size_t k = j + 1;
                if (k < n && (s[k] == '+' || s[k] == '-')) ++k;
                if (k < n && std::isdigit((unsigned char)s[k])) {
                    while (k < n && std::isdigit((unsigned char)s[k])) ++k;
                    j = k;
                }
            }
            Token t;
            t.k = Tok::Num;
            t.text = s.substr(i, j - i);
            t.num = decFromString(t.text);
            out.push_back(t);
            i = j;
            continue;
        }

        // 标识符: sqrt / pow / pi / e
        if (std::isalpha(c) || c == '_') {
            size_t j = i;
            while (j < n && (std::isalnum((unsigned char)s[j]) || s[j] == '_')) ++j;
            Token t;
            t.k = Tok::Ident;
            t.text = toLowerA(s.substr(i, j - i));
            if (t.text == "\xCF\x80") t.text = "pi";   // π 统一当成 pi
            out.push_back(t);
            i = j;
            continue;
        }

        switch (c) {
            case '+': out.push_back(Token{Tok::Plus, "+", {}}); ++i; continue;
            case '-': out.push_back(Token{Tok::Minus, "-", {}}); ++i; continue;
            case '/': out.push_back(Token{Tok::Slash, "/", {}}); ++i; continue;
            case '^': out.push_back(Token{Tok::Caret, "^", {}}); ++i; continue;
            case '\\': out.push_back(Token{Tok::Slash, "\\", {}}); ++i; continue;
            case '(': out.push_back(Token{Tok::LParen, "(", {}}); ++i; continue;
            case ')': out.push_back(Token{Tok::RParen, ")", {}}); ++i; continue;
            case ',': out.push_back(Token{Tok::Comma, ",", {}}); ++i; continue;
            case '*':
                if (i + 1 < n && s[i + 1] == '*') { out.push_back(Token{Tok::Caret, "**", {}}); i += 2; }
                else { out.push_back(Token{Tok::Star, "*", {}}); ++i; }
                continue;
            default: break;
        }

        // UTF-8 多字节符号
        struct MB { const char* p; size_t len; Tok k; };
        static const MB mbs[] = {
            {"\xE2\x88\x9A", 3, Tok::Sqrt},    // √ U+221A
            {"\xE2\x88\x92", 3, Tok::Minus},   // − U+2212
            {"\xC3\x97", 2, Tok::Star},        // ×
            {"\xC3\xB7", 2, Tok::Slash},       // ÷
            {"\xEF\xBC\x88", 3, Tok::LParen},  // （
            {"\xEF\xBC\x89", 3, Tok::RParen},  // ）
            {"\xEF\xBC\x8C", 3, Tok::Comma},   // ，
            {"\xEF\xBC\x8B", 3, Tok::Plus},    // ＋
            {"\xEF\xBC\x8D", 3, Tok::Minus},   // －
        };
        bool matched = false;
        for (const MB& m : mbs) {
            if (s.compare(i, m.len, m.p) == 0) {
                Token t;
                t.k = m.k;
                t.text = m.p;
                out.push_back(t);
                i += m.len;
                matched = true;
                break;
            }
        }
        if (matched) continue;
        throw CalcError("无法识别的字符: " + s.substr(i, 1));
    }
    Token e;
    e.k = Tok::End;
    out.push_back(e);
    return out;
}

// ============================ 语法分析 + 求值 ============================
// expr   := term (('+'|'-') term)*
// term   := unary (('*'|'/') unary)*
// unary  := ('+'|'-') unary | power
// power  := atom ('^' unary)?          右结合, 且指数可为负数
// atom   := 数字 | '(' expr ')' | '√' atom | 'sqrt' ( '(' expr ')' | atom )
//         | 'pow' '(' expr ',' expr ')' | 'pi' | 'e'


struct Parser {
    const std::vector<Token>& t;
    size_t i = 0;
    int depth = 0;
    const Limits& L;

    Parser(const std::vector<Token>& toks, const Limits& lim) : t(toks), L(lim) {}

    const Token& cur() const { return t[i]; }
    bool accept(Tok k) {
        if (t[i].k == k) { ++i; return true; }
        return false;
    }
    void expect(Tok k, const char* what) {
        if (!accept(k)) throw CalcError(std::string("语法错误: 期望 \"") + what + "\"");
    }
    void enter() {
        if (++depth > 400) throw CalcError("表达式嵌套过深(>400 层)");
    }
    void leave() { --depth; }

    BigDec parseExpr() {
        enter();
        BigDec v = parseTerm();
        for (;;) {
            if (accept(Tok::Plus)) {
                v = decAdd(v, parseTerm(), L);
            } else if (accept(Tok::Minus)) {
                BigDec rhs = parseTerm();
                if (!rhs.mant.isZero()) rhs.neg = !rhs.neg;
                v = decAdd(v, rhs, L);
            } else {
                break;
            }
        }
        leave();
        return v;
    }

    BigDec parseTerm() {
        BigDec v = parseUnary();
        for (;;) {
            if (accept(Tok::Star)) {
                v = decMul(v, parseUnary(), L);
            } else if (accept(Tok::Slash)) {
                BigDec d = parseUnary();
                v = decDiv(v, d, L.precision, L);  // 内部含除数为 0 判断
            } else {
                break;
            }
        }
        return v;
    }

    BigDec parseUnary() {
        if (accept(Tok::Plus)) return parseUnary();
        if (accept(Tok::Minus)) {
            BigDec v = parseUnary();
            if (!v.mant.isZero()) v.neg = !v.neg;
            return v;
        }
        return parsePower();
    }

    BigDec parsePower() {
        BigDec b = parseAtom();
        if (accept(Tok::Caret)) {
            BigDec e = parseUnary();
            return decPow(b, e, L);
        }
        return b;
    }

    BigDec parseAtom() {
        enter();
        BigDec v;
        if (accept(Tok::Sqrt)) {
            v = decSqrt(parseAtom(), L);
        } else if (cur().k == Tok::Num) {
            v = cur().num;
            ++i;
        } else if (accept(Tok::LParen)) {
            v = parseExpr();
            expect(Tok::RParen, ")");
        } else if (cur().k == Tok::Ident) {
            std::string name = cur().text;
            ++i;
            auto parseArg = [&]() -> BigDec {
                if (accept(Tok::LParen)) {
                    BigDec a = parseExpr();
                    expect(Tok::RParen, ")");
                    return a;
                }
                return parseAtom();
            };
            if (name == "sqrt") {
                v = decSqrt(parseArg(), L);
            } else if (name == "pow") {
                expect(Tok::LParen, "(");
                BigDec a = parseExpr();
                expect(Tok::Comma, ",");
                BigDec b = parseExpr();
                expect(Tok::RParen, ")");
                v = decPow(a, b, L);
            } else if (name == "pi") {
                v = decFromString(PI_DIGITS);
                v.approx = true;
                v = decRoundSig(v, L.precision);
            } else if (name == "e") {
                v = decFromString(E_DIGITS);
                v.approx = true;
                v = decRoundSig(v, L.precision);
            } else if (name == "abs") {
                v = decAbs(parseArg());
            } else if (name == "floor") {
                v = decFloor(parseArg());
            } else if (name == "ceil") {
                v = decCeil(parseArg());
            } else if (name == "round") {
                v = decRound(parseArg());
            } else if (name == "fact") {
                v = decFact(parseArg(), L);
            } else if (name == "rad") {
                v = degToRad(parseArg(), L);
            } else if (name == "deg") {
                v = radToDeg(parseArg(), L);
            } else if (name == "sin") {
                v = decSin(parseArg(), L);
            } else if (name == "cos") {
                v = decCos(parseArg(), L);
            } else if (name == "tan") {
                v = decTan(parseArg(), L);
            } else if (name == "asin") {
                v = decAsin(parseArg(), L);
            } else if (name == "acos") {
                v = decAcos(parseArg(), L);
            } else if (name == "atan") {
                v = decAtan(parseArg(), L);
            } else if (name == "sinh") {
                v = decSinh(parseArg(), L);
            } else if (name == "cosh") {
                v = decCosh(parseArg(), L);
            } else if (name == "tanh") {
                v = decTanh(parseArg(), L);
            } else if (name == "exp") {
                v = decExp(parseArg(), L);
            } else if (name == "log" || name == "ln") {
                v = decLog(parseArg(), L);
            } else if (name == "log10") {
                v = decLog10(parseArg(), L);
            } else if (name == "log2") {
                v = decLog2(parseArg(), L);
            } else {
                throw CalcError("未知的名称: " + name +
                    " (可用: sqrt, pow, pi, e, abs, floor, ceil, round, fact,"
                    " sin, cos, tan, asin, acos, atan, sinh, cosh, tanh,"
                    " exp, log, ln, log10, log2, rad, deg)");
            }
        } else {
            throw CalcError("语法错误: 缺少操作数");
        }
        leave();
        return v;
    }
};

static BigDec evaluate(const std::string& expr, const Limits& L) {
    std::vector<Token> toks = tokenize(expr);
    if (toks.size() == 1) throw CalcError("表达式为空");
    Parser p(toks, L);
    BigDec v = p.parseExpr();
    if (p.cur().k != Tok::End) throw CalcError("语法错误: 表达式末尾有多余内容");
    decCheckSize(v, L, "计算");
    return v;
}

// ============================ 输出与帮助 ============================

static int reliableOf(const BigDec& v, const Limits& L) {
    return v.reliable > 0 ? v.reliable : (int)L.precision;
}

static void printResultLine(const BigDec& v, const Limits& L, bool bare) {
    progressClear();  // 先擦掉进度行, 再输出结果
    std::string s = decToString(v);
    if (bare) {
        writeChunked(s);
        std::cout << "\n";
        if (v.approx) std::cout << "注意: 结果为近似值(" << reliableOf(v, L) << " 位有效数字)\n";
        std::cout.flush();
        return;
    }
    std::cout << "= ";
    writeChunked(s);
    std::cout << "\n";
    if (s.size() > 60) {
        size_t digits = s.size() - (v.neg ? 1 : 0);
        std::cout << "(结果 " << digits << " 个字符";
        if (s.find('e') != std::string::npos) std::cout << ", 已用科学计数法";
        std::cout << ")\n";
    }
    if (v.approx) std::cout << "(近似值: " << reliableOf(v, L) << " 位有效数字)\n";
    std::cout.flush();
}

static void printHelp() {
    std::cout <<
        "支持: +  -  *  /  ^(次方, 也可写 **)  √(开平方, 也可写 sqrt)  () 括号\n"
        "      科学函数: sin cos tan asin acos atan  sinh cosh tanh\n"
        "                exp log ln log10 log2  abs floor ceil round fact\n"
        "                rad(角度转弧度) deg(弧度转角度)   常量: pi e π\n"
        "      也接受 × ÷ 与全角括号\n"
        "\n"
        "说明: 三角函数默认使用弧度制; 可用 rad(x) 把角度转成弧度后再计算,\n"
        "      例如 sin(rad(30)) = 0.5。科学函数走浮点近似, 可靠位数约 15 位。\n"
        "\n"
        "实时输出: 耗时的计算(大次方/大乘法/大开方/超长结果输出)会在 stderr 上\n"
        "          实时显示进度(算到哪一步、已经多少位), 不干扰 stdout 上的结果\n"
        "          用 -q / --quiet 或命令 quiet on 可以关闭\n"
        "\n"
        "命令:\n"
        "  precision N   设置近似结果有效数字位数(范围 1~20000, 默认 50)\n"
        "  maxdigits N   设置结果位数安全上限(默认 1000000)\n"
        "  force on|off  是否解除位数安全上限(内存允许时)\n"
        "  quiet on|off  是否显示计算进度\n"
        "  help / ?      显示本帮助\n"
        "  version       版本信息\n"
        "  cls / clear   清屏\n"
        "  exit / quit   退出\n"
        "\n"
        "示例:\n"
        "  1+2*3                  -> 7\n"
        "  (1+2)*3/4              -> 2.25\n"
        "  1/0                    -> 错误: 除数不能等于0\n"
        "  sqrt(2)  或  √2         -> 1.4142135623730950488016887242096980785696718753769\n"
        "  2^100                  -> 1267650600228229401496703205376 (精确, 不限大小)\n"
        "  2^1000 3^500 ...       -> 任意大整数次方\n"
        "  1/7                    -> 0.142857...(保留 precision 位)\n"
        "  pow(2,100)  或  2**100   -> 同上\n"
        "  sin(1)  cos(0)  tan(pi/4)  -> 三角函数(弧度)\n"
        "  log(e)  exp(1)  log10(100)  -> 对数/指数\n"
        "  fact(20)  abs(-5)  floor(2.7)  -> 整数/取整\n";
}

static void printAbout() {
    std::cout << "命令行计算器 (任意精度) v" << APP_VERSION << "\n"
              << "大数实现: 10^9 进制 + Karatsuba 乘法 + Knuth 除法\n"
              << "构建: MSVC / C++17\n";
}

static void printUsage() {
    std::cout <<
        "用法:\n"
        "  calc.exe                         进入交互模式\n"
        "  calc.exe \"表达式\"                计算一次并输出结果\n"
        "  calc.exe 2 ^ 100                 多个参数会自动拼成 2^100\n"
        "\n"
        "选项:\n"
        "  -p, --precision N    近似结果保留的有效数字位数 (默认 50)\n"
        "  -m, --max-digits N   结果位数安全上限 (默认 1000000)\n"
        "  -f, --force          解除位数安全上限(仍受内存与 1e8 位硬上限约束)\n"
        "  -q, --quiet          不显示实时计算进度\n"
        "  -i, --interactive    强制进入交互模式\n"
        "  -h, --help           显示帮助\n"
        "  -v, --version        显示版本\n"
        "\n"
        "实时进度:\n"
        "  计算 2^1000000 这类耗时运算时, stderr 上会实时刷新\"算到哪了\", 例如:\n"
        "    [进度] 次方: 开始计算 2^1000000, 预计结果约 3.010e+05 位数字\n"
        "    [进度] 次方: 第 19/20 步(平方), 中间结果 150515 位, 下一步规模 301030 位\n"
        "    [进度] 输出结果: 已转换 45% (135463 位)\n"
        "  stdout 上只有结果本身, 进度不会污染管道输出; 用 -q 可关闭。\n"
        "\n"
        "示例:\n"
        "  calc.exe \"2^100\"\n"
        "  calc.exe \"sqrt(2)\" -p 100\n"
        "  calc.exe --force \"2^4000000\"     (120 万位, 可以看到实时进度)\n"
        "  calc.exe \"1/(3-3)\"        (退出码 1, 提示除数不能等于0)\n"
        "  calc.exe \"sin(rad(30))\"  calc.exe \"log10(1000)\"  calc.exe \"fact(20)\"\n";
}

static void clearScreen() {
#ifdef _WIN32
    HANDLE h = GetStdHandle(STD_OUTPUT_HANDLE);
    CONSOLE_SCREEN_BUFFER_INFO csbi;
    if (h != INVALID_HANDLE_VALUE && GetConsoleScreenBufferInfo(h, &csbi)) {
        DWORD cells = (DWORD)csbi.dwSize.X * (DWORD)csbi.dwSize.Y, written = 0;
        COORD home = {0, 0};
        FillConsoleOutputCharacterA(h, ' ', cells, home, &written);
        FillConsoleOutputAttribute(h, csbi.wAttributes, cells, home, &written);
        SetConsoleCursorPosition(h, home);
        return;
    }
#endif
    std::cout << "\n";
}

// ============================ 交互模式 ============================

static bool handleCommand(const std::string& line, Limits& L, bool bare) {
    std::string t = trimA(line);
    std::string low = toLowerA(t);
    if (low.rfind("set ", 0) == 0) { t = trimA(t.substr(4)); low = toLowerA(t); }
    if (t.empty()) return true;

    size_t sp = t.find_first_of(" \t");
    std::string word = toLowerA(sp == std::string::npos ? t : t.substr(0, sp));
    std::string rest = sp == std::string::npos ? std::string() : trimA(t.substr(sp + 1));

    auto number = [&](size_t lo, size_t hi, size_t& out) -> bool {
        if (!isAllDigits(rest)) return false;
        try {
            unsigned long long v = std::stoull(rest);
            if (v < lo) v = lo;
            if (v > hi) v = hi;
            out = (size_t)v;
            return true;
        } catch (...) { return false; }
    };

    if (word == "precision" || word == "prec") {
        size_t v = L.precision;
        if (!rest.empty() && number(1, 20000, v)) L.precision = v;
        std::cout << "precision = " << L.precision << " (有效数字位数)\n";
        return true;
    }
    if (word == "maxdigits" || word == "maxd") {
        size_t v = L.maxDigits;
        if (!rest.empty() && number(100, 100000000, v)) L.maxDigits = v;
        std::cout << "maxdigits = " << L.maxDigits << " (结果位数安全上限)\n";
        return true;
    }
    if (word == "force") {
        if (rest == "on" || rest == "1" || rest == "true") L.force = true;
        else if (rest == "off" || rest == "0" || rest == "false") L.force = false;
        std::cout << "force = " << (L.force ? "on" : "off") << "\n";
        return true;
    }
    if (word == "quiet" || word == "progress") {
        if (rest == "on" || rest == "1" || rest == "true") g_quiet = false;
        else if (rest == "off" || rest == "0" || rest == "false") g_quiet = true;
        std::cout << "quiet = " << (g_quiet ? "on" : "off") << " (是否显示实时计算进度)\n";
        return true;
    }
    if (word == "help" || word == "?") { printHelp(); return true; }
    if (word == "version" || word == "about") { printAbout(); return true; }
    if (word == "cls" || word == "clear") { if (!bare) clearScreen(); return true; }
    return false;
}

static int runInteractive(Limits& L) {
    bool tty = stdinIsTty();
    if (tty) {
        std::cout << "=== 命令行计算器 (任意精度) v" << APP_VERSION << " ===\n";
        std::cout << "输入表达式直接回车计算; 输入 help 查看帮助, exit 退出。\n";
        std::cout << "例如: 1+2*3   2^100   sqrt(2)   √9   sin(1)   log10(100)   fact(20)\n";
        std::cout << "      1/0(会给出错误提示)   输入 help 查看全部科学函数\n\n";
        std::cout.flush();
    }
    std::string line;
    for (;;) {
        if (tty) { std::cout << "calc> " << std::flush; }
        if (!std::getline(std::cin, line)) {
            if (tty) std::cout << "\n";
            break;
        }
        std::string t = trimA(line);
        if (t.empty()) continue;
        std::string low = toLowerA(t);
        if (low == "exit" || low == "quit" || low == "q" || low == ":q") break;
        if (handleCommand(t, L, !tty)) continue;
        try {
            BigDec v = evaluate(t, L);
            printResultLine(v, L, !tty);
        } catch (const CalcError& e) {
            progressClear();
            std::cout << "错误: " << e.what() << "\n";
        } catch (const std::exception& e) {
            progressClear();
            std::cout << "内部错误: " << e.what() << "\n";
        }
        std::cout.flush();
    }
    return 0;
}

// ============================ 命令行入口 ============================

static bool parseSizeArg(const std::string& s, size_t lo, size_t hi, size_t& out) {
    if (s.empty() || !isAllDigits(s)) return false;
    try {
        unsigned long long v = std::stoull(s);
        if (v < lo) v = lo;
        if (v > hi) v = hi;
        out = (size_t)v;
        return true;
    } catch (...) { return false; }
}

static int runMain(const std::vector<std::string>& args) {
    Limits L;
    bool interactive = false;
    bool sawPositional = false;
    std::string expr;
    setupProgress();  // 注册实时进度回调

    for (size_t k = 0; k < args.size(); ++k) {
        std::string a = args[k];
        auto nextValue = [&](const char* name) -> std::string {
            size_t eq = a.find('=');
            if (eq != std::string::npos) return a.substr(eq + 1);
            if (k + 1 < args.size()) return args[++k];
            std::cerr << "错误: 选项 " << name << " 缺少参数\n";
            std::exit(2);
        };
        if (a == "-h" || a == "--help") { printUsage(); return 0; }
        if (a == "-v" || a == "--version") { printAbout(); return 0; }
        if (a == "-f" || a == "--force") { L.force = true; continue; }
        if (a == "-q" || a == "--quiet") { g_quiet = true; continue; }
        if (a == "-i" || a == "--interactive") { interactive = true; continue; }
        if (a == "-p" || a == "--precision" || a.rfind("--precision=", 0) == 0 || a.rfind("-p=", 0) == 0) {
            std::string v = nextValue("--precision");
            size_t out = 0;
            if (!parseSizeArg(v, 1, 20000, out)) {
                std::cerr << "错误: precision 需要 1~20000 的整数\n";
                return 2;
            }
            L.precision = out;
            continue;
        }
        if (a == "-m" || a == "--max-digits" || a.rfind("--max-digits=", 0) == 0 || a.rfind("-m=", 0) == 0) {
            std::string v = nextValue("--max-digits");
            size_t out = 0;
            if (!parseSizeArg(v, 100, 100000000, out)) {
                std::cerr << "错误: max-digits 需要 100~100000000 的整数\n";
                return 2;
            }
            L.maxDigits = out;
            continue;
        }
        if (a.size() > 1 && a[0] == '-' &&
            !std::isdigit((unsigned char)a[1]) && a[1] != '.') {
            std::cerr << "错误: 未知选项 " << a << " (用 --help 查看帮助)\n";
            return 2;
        }
        expr += a;  // 位置参数直接拼接: calc 2 ^ 100 -> 2^100
        sawPositional = true;
    }

    if (interactive || !sawPositional) return runInteractive(L);

    try {
        BigDec v = evaluate(expr, L);
        progressClear();
        writeChunked(decToString(v));
        std::cout << "\n";
        if (v.approx) std::cerr << "注意: 结果为近似值(" << reliableOf(v, L) << " 位有效数字)\n";
        std::cout.flush();
        return 0;
    } catch (const CalcError& e) {
        progressClear();
        std::cerr << "错误: " << e.what() << "\n";
        return 1;
    } catch (const std::exception& e) {
        progressClear();
        std::cerr << "内部错误: " << e.what() << "\n";
        return 1;
    }
}

#ifdef _WIN32
int wmain(int argc, wchar_t** argv) {
    setupConsole();
    std::vector<std::string> args;
    for (int i = 1; i < argc; ++i) args.push_back(wideToUtf8(argv[i]));
    return runMain(args);
}
#else
int main(int argc, char** argv) {
    std::vector<std::string> args;
    for (int i = 1; i < argc; ++i) args.push_back(argv[i]);
    return runMain(args);
}
#endif
