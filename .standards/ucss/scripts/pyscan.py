#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
UCSS-PyScan V1.1 —— 轻量代码扫描引擎（纯 Python 标准库 · 零第三方依赖 · 天然离线）
================================================================================
定位：三级降级链 L3 兜底盘（L1 semgrep 在线规则 / L2 本地规则 / L3 本引擎——永不缺位）
  · scan 子命令：AST 级静态分析（64 条规则=56 py+8 js，CWE 映射）→ SARIF 2.1 兼容输出
  · sca  子命令：依赖组件漏洞（OSV 离线快照 / 在线 API 双模）
  · selftest 子命令：回归自检（检出率/误报棘轮断言——防能力回缩）
网络模式：扫描本体永远离线可用；SCA 按 --net auto|online|offline（脚本层探测，不信自报）
等级映射 SARIF level：HIGH→error（gate 阻断）· MEDIUM→warning · LOW/INFO→note
Python ≥ 3.8 · import 白名单：ast/json/re/sys/os/argparse/tempfile/urllib
来源：可行性评估（2026-10-12，reports/ucss-feasibility/）·PoC 检出率 94.4% 零误报
V1.1.0（V1.9.18）：规则 40→64（AI 场景 R108-R110/注入增强 R205-R207/R605-R606 污点增强）
  + JS 正则浅扫层（RJ 族 8 条·.js/.jsx/.ts/.tsx）+ glob 隐藏目录漏扫修复（os.walk·勘误④）
"""
import argparse
import ast
import json
import os
import re
import sys
import tempfile

try:
    import urllib.request
except ImportError:      # 内网无 urllib 场景兜底（3.8+ 标配，理论到不了）
    urllib = None

__version__ = "1.1.0"   # V1.9.18：规则 40→64（56 py + 8 js）·JS 正则浅扫层·R605/R606 污点增强

# ─────────────────────────── 规则定义（40 条 · 声明式） ───────────────────────────
RULES = {
    # R10x 危险调用族（C1 确定性）
    "R101": ("HIGH", "CWE-95",  "动态执行 eval/exec/compile——不可信输入可达即 RCE"),
    "R102": ("HIGH", "CWE-502", "不安全反序列化 pickle/marshal/shelve"),
    "R103": ("HIGH", "CWE-502", "yaml.load 未指定 Loader——不安全反序列化"),
    "R104": ("HIGH", "CWE-78",  "shell 执行（os.system/popen·subprocess shell=True）"),
    "R105": ("MEDIUM", "CWE-95", "__import__ 动态导入"),
    "R106": ("MEDIUM", "CWE-78", "os.exec*/spawn* 进程族调用"),
    "R107": ("MEDIUM", "CWE-377", "tempfile.mktemp 已弃用（竞态条件）"),
    "R108": ("MEDIUM", "CWE-502", "AI 模型不安全加载 torch.load 未设 weights_only"),
    "R109": ("MEDIUM", "CWE-502", "AI 数据不安全反序列化 numpy.load(allow_pickle=True)/joblib.load"),
    "R110": ("MEDIUM", "CWE-502", "HuggingFace trust_remote_code=True（远程代码执行面）"),
    # R20x 注入模式（C2）
    "R201": ("HIGH", "CWE-78",  "命令注入模式：动态拼接进命令 sink"),
    "R202": ("HIGH", "CWE-89",  "SQL 注入模式：动态拼接进 execute 族"),
    "R203": ("MEDIUM", "CWE-22", "路径拼接：动态拼接进文件操作 sink（穿越风险）"),
    "R204": ("MEDIUM", "CWE-90", "LDAP 注入模式：动态拼接进 ldap 查询"),
    "R205": ("MEDIUM", "CWE-134", "格式化字符串注入：动态/污点输入作格式模板"),
    "R206": ("MEDIUM", "CWE-470", "污点→getattr/setattr 反射调用"),
    "R207": ("MEDIUM", "CWE-94", "模板注入：动态输入进 Template() 构造"),
    # R30x 硬编码凭据（C1/C3）
    "R301": ("HIGH", "CWE-798", "硬编码凭据（凭据命名常量赋值）"),
    "R302": ("HIGH", "CWE-798", "硬编码密钥/token（长字面量+凭据上下文）"),
    "R303": ("HIGH", "CWE-798", "疑似 AWS Access Key（AKIA 模式）"),
    "R304": ("HIGH", "CWE-321", "硬编码私钥（PEM 头）"),
    "R305": ("MEDIUM", "CWE-522", "硬编码 JWT（eyJ 头）"),
    "R306": ("MEDIUM", "CWE-521", "弱口令字面量（弱值表/过短）"),
    "R307": ("HIGH", "CWE-798", "连接串硬编码凭据（scheme://user:pass@）"),
    "R308": ("HIGH", "CWE-798", "HTTP Basic 认证硬编码（auth= 字面量元组）"),
    # R40x 弱密码学（C1/C2）
    "R401": ("MEDIUM", "CWE-327", "弱哈希 md5/sha1 用于安全上下文"),
    "R402": ("MEDIUM", "CWE-338", "random 系用于安全用途（token/secret 命名）"),
    "R403": ("HIGH", "CWE-295", "SSL/TLS 校验关闭（verify=False）"),
    "R404": ("MEDIUM", "CWE-327", "弱加密模式（DES/3DES/ECB）"),
    "R405": ("MEDIUM", "CWE-798", "硬编码盐值"),
    "R406": ("MEDIUM", "CWE-1204", "硬编码初始化向量 IV/nonce"),
    # R50x 质量与安全缺陷（C1）
    "R501": ("MEDIUM", "CWE-396", "裸 except（吞掉一切异常含退出信号）"),
    "R502": ("MEDIUM", "CWE-754", "except...pass 静默吞噬异常"),
    "R503": ("MEDIUM", "CWE-617", "assert 用于鉴权/权限判定（-O 即剥除）"),
    "R504": ("HIGH", "CWE-489", "调试特性开启（app.run(debug=True)）"),
    "R505": ("LOW", "CWE-?",  "可变默认参数（列表/字典作默认值）"),
    "R506": ("LOW", "CWE-?",  "通配导入 from x import *（命名污染）"),
    # R60x 污点链（C2 · def-use 不动点传播）
    "R601": ("HIGH", "CWE-78",  "污点链：不可信输入→命令 sink"),
    "R602": ("HIGH", "CWE-89",  "污点链：不可信输入→SQL sink"),
    "R603": ("HIGH", "CWE-95",  "污点链：不可信输入→动态执行 sink"),
    "R604": ("MEDIUM", "CWE-22", "污点链：不可信输入→文件操作 sink"),
    "R605": ("HIGH", "CWE-78",  "污点链：不可信输入→命令 sink（非首参/关键字参）"),
    "R606": ("MEDIUM", "CWE-22", "污点链：不可信输入→文件写 sink（'w'/'a' 模式）"),
    # R70x 框架配置（C1）
    "R701": ("HIGH", "CWE-798", "Django SECRET_KEY 硬编码"),
    "R702": ("MEDIUM", "CWE-346", "ALLOWED_HOSTS 通配（host 头伪造面）"),
    "R703": ("MEDIUM", "CWE-942", "CORS 允许来源通配 *"),
    "R704": ("MEDIUM", "CWE-732", "世界可写权限（chmod 0o777/0o666）"),
    "R705": ("MEDIUM", "CWE-489", "Django DEBUG=True 生产风险"),
    "R706": ("MEDIUM", "CWE-494", "从不安全来源加载模型/数据（http:// URL）"),
    "R707": ("MEDIUM", "CWE-532", "日志/打印敏感变量（凭据命名变量进 print）"),
    # R80x 明文与信息暴露（C3 正则级）
    "R801": ("LOW", "CWE-200", "硬编码内网 IP/地址段（信息暴露）"),
    "R802": ("MEDIUM", "CWE-319", "明文协议（telnet/ftp 传输）"),
    "R803": ("LOW", "CWE-319", "外呼 http:// 明文 URL（非本地）"),
    "R806": ("LOW", "CWE-359", "PII 硬编码（身份证号模式）"),
    "R807": ("LOW", "CWE-798", "AWS secret 命名+长字面量（R303 名字补充）"),
    # RJ 族 JS 正则浅扫层（C3 · scan_js 产出·V1.9.18）
    "RJ01": ("HIGH", "CWE-95", "JS eval() 动态执行"),
    "RJ02": ("HIGH", "CWE-79", "JS innerHTML 直接赋值（XSS）"),
    "RJ03": ("MEDIUM", "CWE-79", "JS document.write（XSS 面）"),
    "RJ04": ("HIGH", "CWE-78", "JS child_process exec 拼接（命令注入）"),
    "RJ05": ("HIGH", "CWE-798", "JS 硬编码密钥字面量（命名匹配）"),
    "RJ06": ("MEDIUM", "CWE-319", "JS 外呼 http:// 明文 URL"),
    "RJ07": ("HIGH", "CWE-79", "React dangerouslySetInnerHTML"),
    "RJ08": ("MEDIUM", "CWE-338", "JS Math.random() 用于 token/secret"),
    # R90x SCA（sca 子命令产出）
    "R901": ("HIGH", "CWE-1104", "依赖含已知漏洞（OSV 命中）"),
}

CRED_NAME = re.compile(r"(?i)(password|passwd|pwd|secret|token|api[_-]?key|aksk|"
                       r"access[_-]?key|credential|private[_-]?key|auth)")
SALT_NAME = re.compile(r"(?i)(salt|pepper|nonce)")
TAINT_NAME = re.compile(r"(?i)^(user\w*|input\w*|req\w*|param\w*|msg\w*|query\w*|"
                        r"body\w*|payload\w*|search\w*|keyword\w*|comment\w*|"
                        r"content\w*|field\w*|arg\w*|text\w*|cmd\w*|filename|"
                        r"path_param\w*|\w*name)$")   # fullmatch 语义：裸 path/uid/data 不误伤
SAFE_MD5_CTX = re.compile(r"(?i)(checksum|cache|etag|fingerprint|digest_cache|dedup)")
WEAK_PASSWORDS = {"123456", "password", "admin", "root", "abc123", "111111",
                  "123123", "admin123", "test", "guest", "qwerty", "letmein", "000000"}
CMD_SINKS = {"system", "popen", "run", "call", "check_output", "check_call",
             "Popen", "execv", "execve", "execvp", "spawn", "spawnl", "spawnv"}
SQL_SINKS = {"execute", "executemany", "executescript"}
FILE_SINKS = {"open", "remove", "unlink", "rename", "rmtree", "copy", "move", "mkfile"}
EVAL_SINKS = {"eval", "exec", "compile"}
LDAP_SINKS = {"search", "search_s", "modify", "add"}
UNSAFE_DESER = {"loads", "load"}
IP_PRIVATE = re.compile(r"\b(10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)\b")
AWS_AK = re.compile(r"\bAKIA[0-9A-Z]{16}\b")
PEM_KEY = re.compile(r"-----BEGIN\s+(RSA\s+|EC\s+|OPENSSH\s+)?PRIVATE KEY-----")
JWT_LIT = re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.")
HTTP_URL = re.compile(r"(?<![\\s])http://(?!localhost|127\.0\.0\.1|0\.0\.0\.0|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)[^\s'\"]+")
# V1.9.18 增强常量
CONN_STR = re.compile(r"[a-z][a-z0-9+.-]*://[^\s'\"/@:]+:[^\s'\"@/]+@")          # scheme://user:pass@
ID_CARD = re.compile(r"\b\d{17}[\dXx]\b")                                        # 18 位身份证
IV_NAME = re.compile(r"(?i)(\biv\b|init[_-]?vector|nonce|gcm[_-]?iv)")           # IV/nonce 命名
AWS_SECRET_NAME = re.compile(r"(?i)(aws[_-])?(secret[_-]?access[_-]?key|secret[_-]?key)")
WRITE_MODES = {"w", "wb", "a", "ab", "w+", "a+"}
LOAD_SINKS = {"load", "load_state_dict", "load_model", "from_pretrained", "loadtxt"}
FORMAT_SINKS = {"format"}
REFLECT_SINKS = {"getattr", "setattr", "__getattribute__"}
TEMPLATE_SINKS = {"Template"}
PRINT_SINKS = {"print", "debug", "info", "warning", "log"}

# RJ 族 JS 正则浅扫层（C3·逐行匹配·V1.9.18）
RJ_PATTERNS = [
    ("RJ01", re.compile(r"(?<![\w.])(?:window\.)?eval\s*\(")),
    ("RJ02", re.compile(r"\.innerHTML\s*=")),
    ("RJ03", re.compile(r"document\.write(?:ln)?\s*\(")),
    ("RJ04", re.compile(r"(?:child_process[.\s]+)?(?:exec|execSync)\s*\(\s*[^)]*\+")),
    ("RJ05", re.compile(r"(?i)(?:const|let|var)\s+\w*(?:apiKey|api_key|secret|token|password|accessToken)\w*\s*=\s*[\"'][A-Za-z0-9+/_-]{16,}[\"']")),
    ("RJ06", re.compile(r"http://(?!localhost|127\.0\.0\.1)[^\s'\"]+")),
    ("RJ07", re.compile(r"dangerouslySetInnerHTML")),
    ("RJ08", re.compile(r"(?i)(?:const|let|var)\s+\w*(?:token|secret|apiKey|session)\w*\s*=\s*(?:Math\.random\(\)|.*\+\s*Math\.random\(\))")),
]
JS_EXTS = {".js", ".jsx", ".ts", ".tsx", ".mjs", ".vue"}

SEV2SARIF = {"HIGH": "error", "MEDIUM": "warning", "LOW": "note", "INFO": "note"}


class Finding(object):
    __slots__ = ("rule", "line", "col", "message", "file")

    def __init__(self, rule, line, col, message, file):
        self.rule, self.line, self.col = rule, line, col
        self.message, self.file = message, file


def _name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return ""


def _module(node):
    """Call 的模块前缀（os.system → 'os'）"""
    if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
        return node.value.id
    return ""


def _is_concat(node):
    return isinstance(node, (ast.BinOp, ast.JoinedStr))


def _expr_taint(node, tainted):
    """表达式是否接触污点（名字级近似 + input() 源 + request 属性源）"""
    for n in ast.walk(node):
        if isinstance(n, ast.Name) and n.id in tainted:
            return True
        if isinstance(n, ast.Call) and _name(n.func) in ("input", "raw_input"):
            return True
        if isinstance(n, ast.Attribute) and n.attr in ("GET", "POST", "args", "form",
                "data", "json", "cookies", "headers", "body", "params", "query_params") \
                and isinstance(n.value, ast.Name) and n.value.id.lower() in ("request", "req", "self"):
            return True
    return False


def _collect_taint(fn):
    """函数内污点不动点：参数启发源 → def-use 传播（含 BinOp/JoinedStr/Call 赋值）"""
    tainted = set(a.arg for a in fn.args.args if TAINT_NAME.search(a.arg))
    if fn.args.kwarg:
        tainted.add(fn.args.kwarg.arg)
    while True:
        grew = False
        for n in ast.walk(fn):
            if isinstance(n, ast.Assign):
                if _expr_taint(n.value, tainted):
                    for t in n.targets:
                        if isinstance(t, ast.Name) and t.id not in tainted:
                            tainted.add(t.id)
                            grew = True
                        elif isinstance(t, (ast.Tuple, ast.List)):
                            for el in t.elts:
                                if isinstance(el, ast.Name) and el.id not in tainted:
                                    tainted.add(el.id)
                                    grew = True
            elif isinstance(n, ast.AugAssign) and isinstance(n.target, ast.Name):
                if _expr_taint(n.value, tainted) and n.target.id not in tainted:
                    tainted.add(n.target.id)
                    grew = True
            elif isinstance(n, ast.For) and isinstance(n.target, ast.Name) \
                    and isinstance(n.iter, ast.Call) and _name(n.iter.func) != "":
                if _name(n.iter.func) in ("input", "read", "readlines", "recv", "readline") \
                        and n.target.id not in tainted:
                    tainted.add(n.target.id)
                    grew = True
        if not grew:
            return tainted


def _kw_is_false(kw):
    v = getattr(kw.value, "value", None)
    return v is False


def _kw_is_true(kw):
    v = getattr(kw.value, "value", None)
    return v is True


def _add(findings, rule, node, message, path):
    findings.append(Finding(rule, getattr(node, "lineno", 0),
                            getattr(node, "col_offset", 0), message, path))


# ─────────────────────────── 核心扫描（per-file） ───────────────────────────
def scan_source(src, path):
    try:
        tree = ast.parse(src)
    except SyntaxError as e:
        return [Finding("R000", e.lineno or 0, 0,
                        "语法解析失败（跳过该文件）: %s" % e.msg, path)]
    findings = []

    # pass-1 模块级：配置/硬编码/明文/协议 import
    for n in ast.walk(tree):
        if isinstance(n, ast.Assign):
            tgt = " ".join(t.id for t in n.targets if isinstance(t, ast.Name))
            val = n.value
            if isinstance(val, ast.Constant):
                v = val.value
                if isinstance(v, str):
                    if _cred_hit(tgt, v, findings, n, path):
                        pass
                    elif SALT_NAME.search(tgt) and len(v) >= 4:
                        _add(findings, "R405", n, "硬编码盐值：%s" % tgt.strip(), path)
                    for rid, rx in (("R303", AWS_AK), ("R304", PEM_KEY), ("R305", JWT_LIT)):
                        if rx.search(v):
                            _add(findings, rid, n,
                                 "%s 字面量（规则级模式命中，请复核）" % rid, path)
                    for m in IP_PRIVATE.finditer(v):
                        _add(findings, "R801", n, "内网 IP 硬编码: %s" % m.group(0), path)
                        break
                    for m in HTTP_URL.finditer(v):
                        _add(findings, "R803", n, "外呼明文 http URL: %s" % m.group(0)[:60], path)
                        break
                    # V1.9.18 常量级增强
                    if CONN_STR.search(v):
                        _add(findings, "R307", n, "连接串含硬编码凭据（user:pass@ 模式）", path)
                    for m in ID_CARD.finditer(v):
                        _add(findings, "R806", n, "PII 身份证号硬编码", path)
                        break
                    if AWS_SECRET_NAME.search(tgt) and len(v) >= 16:
                        _add(findings, "R807", n, "AWS secret 命名+长字面量：%s" % tgt.strip(), path)
                    if IV_NAME.search(tgt) and len(v) >= 4 and CRED_NAME.search(tgt) is None:
                        _add(findings, "R406", n, "硬编码 IV/nonce：%s" % tgt.strip(), path)
                    # R701/R702/R706 框架配置
                    if tgt == "SECRET_KEY" and len(v) >= 8:
                        _add(findings, "R701", n, "Django SECRET_KEY 硬编码", path)
            if isinstance(val, (ast.List, ast.Tuple, ast.Set)):
                if tgt == "ALLOWED_HOSTS" and any(
                        isinstance(x, ast.Constant) and x.value == "*" for x in val.elts):
                    _add(findings, "R702", n, "ALLOWED_HOSTS 含通配 *", path)
            if isinstance(val, ast.Constant) and val.value is True and tgt.upper() == "DEBUG":
                _add(findings, "R705", n, "DEBUG = True（生产环境必关）", path)
        # R802 明文协议
        if isinstance(n, ast.Import):
            for alias in n.names:
                if alias.name.split(".")[0] in ("telnetlib", "ftplib"):
                    _add(findings, "R802", n, "明文协议库: %s" % alias.name, path)
        if isinstance(n, ast.ImportFrom) and n.module and n.module.split(".")[0] in ("telnetlib", "ftplib"):
            _add(findings, "R802", n, "明文协议库: %s" % n.module, path)

    # pass-2 函数级：污点链 + 调用形态
    for fn in [x for x in ast.walk(tree) if isinstance(x, (ast.FunctionDef, ast.AsyncFunctionDef))]:
        tainted = _collect_taint(fn)
        fn_ctx = fn.name + " " + " ".join(x.id for x in ast.walk(fn) if isinstance(x, ast.Name))
        for n in ast.walk(fn):
            # R402：random 赋值给凭据命名变量（安全用途误用）
            if isinstance(n, ast.Assign) and isinstance(n.value, ast.Call):
                f2 = _name(n.value.func)
                mod2 = _module(n.value.func)
                if f2 in ("random", "randint", "randrange", "choice",
                          "getrandbits", "randbytes", "uniform") or mod2 == "random":
                    tgt = " ".join(t.id for t in n.targets if isinstance(t, ast.Name))
                    if CRED_NAME.search(tgt):
                        _add(findings, "R402", n,
                             "random 用于安全用途：%s" % tgt.strip(), path)
            if not isinstance(n, ast.Call):
                continue
            fname, mod = _name(n.func), _module(n.func)
            args0 = n.args[0] if n.args else None

            if fname in EVAL_SINKS and mod == "":
                _add(findings, "R101", n, "动态执行 %s()" % fname, path)
            if fname == "__import__":
                _add(findings, "R105", n, "__import__ 动态导入", path)
            if mod in ("pickle", "marshal", "shelve", "cPickle", "_pickle") and fname in UNSAFE_DESER:
                _add(findings, "R102", n, "%s.%s 反序列化不可信数据风险" % (mod, fname), path)
            if mod == "yaml" and fname == "load" and not any(k.arg == "Loader" for k in n.keywords):
                _add(findings, "R103", n, "yaml.load 未指定 Loader", path)
            shell_kw = any(k.arg == "shell" and getattr(k.value, "value", False) is True
                           for k in n.keywords)
            if (mod == "os" and fname in ("system", "popen")) or (mod == "subprocess" and shell_kw) \
                    or fname in ("execv", "execve", "execvp", "execvpe", "spawnl", "spawnv"):
                _add(findings, "R104" if mod in ("os", "subprocess") else "R106",
                     n, "shell 执行（%s.%s%s）" % (mod or "os", fname,
                                                  "·shell=True" if shell_kw else ""), path)
            if mod == "tempfile" and fname == "mktemp":
                _add(findings, "R107", n, "tempfile.mktemp 竞态条件（改 mkstemp）", path)
            # V1.9.18 AI 场景三条（CWE-502）
            if mod in ("torch", "safetensors") and fname == "load" \
                    and not any(k.arg == "weights_only" for k in n.keywords):
                _add(findings, "R108", n, "torch.load 未设 weights_only（反序列化 RCE 面）", path)
            if (mod in ("numpy", "joblib") and fname == "load"
                    and any(k.arg == "allow_pickle" and _kw_is_true(k) for k in n.keywords)) \
                    or (mod == "joblib" and fname == "load"):
                _add(findings, "R109", n, "%s.load 不安全加载（pickle 面）" % mod, path)
            if fname == "from_pretrained" and any(
                    k.arg == "trust_remote_code" and _kw_is_true(k) for k in n.keywords):
                _add(findings, "R110", n, "from_pretrained(trust_remote_code=True) 远程代码执行面", path)
            # V1.9.18 注入/反射/模板增强
            if fname in FORMAT_SINKS and isinstance(n.func, ast.Attribute) and (
                    _is_concat(n.func.value) or _expr_taint(n.func.value, tainted)):
                _add(findings, "R205", n, "格式化字符串注入：format 模板含动态/污点输入", path)
            if fname in REFLECT_SINKS and n.args and len(n.args) >= 2 and (
                    isinstance(n.args[1], ast.Name) and n.args[1].id in tainted):
                _add(findings, "R206", n, "污点→%s() 反射（属性名不可信）" % fname, path)
            if fname in TEMPLATE_SINKS and args0 is not None and not isinstance(args0, ast.Constant):
                _add(findings, "R207", n, "模板注入：Template() 参数非字面量", path)
            if fname in ("get", "post", "request", "head", "put", "delete", "patch") and mod == "requests":
                for k in n.keywords:
                    if k.arg == "auth" and isinstance(k.value, (ast.Tuple, ast.List)) and all(
                            isinstance(x, ast.Constant) for x in k.value.elts):
                        _add(findings, "R308", n, "HTTP Basic 认证硬编码（auth= 字面量元组）", path)
            if fname in LOAD_SINKS and args0 is not None and isinstance(args0, ast.Constant) \
                    and isinstance(args0.value, str) and args0.value.startswith("http://"):
                _add(findings, "R706", n, "从不安全来源加载（http://）：%.50s" % args0.value, path)
            if fname in PRINT_SINKS and n.args:
                a = n.args[0]
                if isinstance(a, ast.Name) and CRED_NAME.search(a.id):
                    _add(findings, "R707", n, "打印敏感变量：%s（日志泄密面）" % a.id, path)
            # 注入模式：拼接→sink
            if fname in CMD_SINKS and args0 is not None and _is_concat(args0):
                _add(findings, "R201", n, "命令拼接：%s() 首参动态拼接" % fname, path)
            if fname in SQL_SINKS and args0 is not None and _is_concat(args0):
                _add(findings, "R202", n, "SQL 拼接：%s() 首参动态拼接" % fname, path)
            if fname in FILE_SINKS and args0 is not None and _is_concat(args0):
                _add(findings, "R203", n, "路径拼接：%s() 首参动态拼接" % fname, path)
            if fname in LDAP_SINKS and mod in ("ldap", "ldap3", "connection") and args0 is not None and _is_concat(args0):
                _add(findings, "R204", n, "LDAP 查询拼接：%s()" % fname, path)
            # 弱密码学（上下文=函数名+变量名：含凭据词，或无安全上下文词 → 保守报）
            if mod == "hashlib" and fname in ("md5", "sha1"):
                if CRED_NAME.search(fn_ctx) or not SAFE_MD5_CTX.search(fn_ctx):
                    _add(findings, "R401", n, "弱哈希 %s（函数上下文含安全用途）" % fname, path)
            if mod == "ssl" and fname in ("wrap_socket", "_create_unverified_context") \
                    or any(k.arg in ("verify", "check_hostname") and _kw_is_false(k) for k in n.keywords) \
                    or (mod == "requests" and fname in ("get", "post", "request", "head",
                                                        "put", "delete", "patch")
                        and any(k.arg == "verify" and _kw_is_false(k) for k in n.keywords)):
                _add(findings, "R403", n, "TLS 校验关闭（verify=False / unverified）", path)
            if mod in ("Cryptodome", "Crypto") and fname in ("DES", "DES3", "new") \
                    or (mod in ("Cryptodome", "Crypto") and isinstance(n.func, ast.Attribute)
                        and fname == "MODE_ECB"):
                _add(findings, "R404", n, "弱加密（DES/ECB 模式）", path)
            if mod == "os" and fname == "chmod" and n.args and isinstance(n.args[1], ast.Constant) \
                    and n.args[1].value in (0o777, 0o666, 511, 438):
                _add(findings, "R704", n, "世界可写权限 chmod 0o%o" % n.args[1].value, path)
            if mod == "ldap" and fname == "sasl_*":
                _add(findings, "R204", n, "LDAP SASL 明文", path)
            # Flask debug
            if fname == "run" and mod == "app" or (fname == "run" and isinstance(n.func, ast.Attribute)
                                                   and any(k.arg == "debug" and
                                                           getattr(k.value, "value", False) is True
                                                           for k in n.keywords)):
                if any(k.arg == "debug" and getattr(k.value, "value", False) is True for k in n.keywords):
                    _add(findings, "R504", n, "Flask app.run(debug=True) 调试模式", path)
            # CORS 通配
            if fname in ("Cors", "CORS", "cors") or (isinstance(n.func, ast.Attribute) and fname in
                                                     ("add_cors_headers", "after_request")):
                if any(isinstance(k.value, ast.Constant) and k.value.value == "*" for k in n.keywords):
                    _add(findings, "R703", n, "CORS 允许来源 *", path)
            # 污点→sink（污点链=C2「很可能」：def-use 传播可靠但污点源为命名启发）
            if args0 is not None and fname in (CMD_SINKS | EVAL_SINKS) and _expr_taint(args0, tainted):
                _add(findings, "R601" if fname in CMD_SINKS else "R603",
                     n, "污点链：不可信输入→%s()" % fname, path)
            if args0 is not None and fname in SQL_SINKS and _expr_taint(args0, tainted):
                _add(findings, "R602", n, "污点链：不可信输入→%s()" % fname, path)
            if args0 is not None and fname in FILE_SINKS and _expr_taint(args0, tainted):
                _add(findings, "R604", n, "污点链：不可信输入→%s()" % fname, path)
            # V1.9.18 污点链增强：非首参/关键字参 + 文件写模式
            if fname in (CMD_SINKS | EVAL_SINKS | SQL_SINKS):
                extra = [a for a in n.args[1:] if _expr_taint(a, tainted)]
                extra += [k.value for k in n.keywords if _expr_taint(k.value, tainted)]
                if extra and not (args0 is not None and _expr_taint(args0, tainted)):
                    _add(findings, "R605", n, "污点链：不可信输入→%s() 非首参/关键字参" % fname, path)
            if fname == "open" and _expr_taint(args0, tainted):
                mode = n.args[1] if len(n.args) > 1 and isinstance(n.args[1], ast.Constant) else None
                mk = next((k.value for k in n.keywords if k.arg == "mode"), None)
                mv = (getattr(mode, "value", None) or getattr(mk, "value", None) or "")
                if isinstance(mv, str) and any(mv.startswith(w) or w in mv for w in WRITE_MODES):
                    _add(findings, "R606", n, "污点链：不可信输入→open() 写模式（任意写风险）", path)

    # pass-3 语句级：异常吞噬 / assert / 可变默认参数 / 通配导入
    for n in ast.walk(tree):
        if isinstance(n, ast.ImportFrom) and any(a.name == "*" for a in n.names):
            _add(findings, "R506", n, "通配导入 from %s import *" % (n.module or "?"), path)
        if isinstance(n, ast.ExceptHandler):
            swallow = all(isinstance(x, ast.Pass) for x in n.body)
            if n.type is None:
                _add(findings, "R501", n, "裸 except（含 KeyboardInterrupt/SystemExit）", path)
                if swallow:
                    _add(findings, "R502", n, "裸 except+pass 静默吞噬", path)
            elif swallow:
                _add(findings, "R502", n, "except...pass 静默吞噬", path)
        if isinstance(n, ast.Assert):
            # 3.8 兼容（V1.9.31）：ast.unparse 为 3.9+ API——3.8 无则降级 ast.dump
            # （dump 文本含 Name.id 与 Constant.value 字面量，鉴权词命中语义等价）
            try:
                ctx = ast.unparse(n.test) if hasattr(ast, "unparse") else ast.dump(n.test)
            except Exception:
                ctx = ""
            if re.search(r"(?i)(auth|login|permi|admin|role|is_admin|token|session)", ctx):
                _add(findings, "R503", n, "assert 用于鉴权（-O 剥除）: %s" % ctx[:60], path)
    for fn in [x for x in ast.walk(tree) if isinstance(x, (ast.FunctionDef, ast.AsyncFunctionDef))]:
        for d in fn.args.defaults:
            if isinstance(d, (ast.List, ast.Dict, ast.Set)):
                _add(findings, "R505", fn, "可变默认参数（%s）" % fn.name, path)
    return findings


def _cred_hit(tgt, val, findings, n, path):
    """R301/R302/R306 凭据判定（返回是否已产出 finding）"""
    if not CRED_NAME.search(tgt):
        return False
    if val.lower() in WEAK_PASSWORDS or len(val) < 6:
        _add(findings, "R306", n, "弱口令字面量：%s" % tgt.strip(), path)
        return True
    _add(findings, "R301", n, "硬编码凭据：%s（值长度 %d）" % (tgt.strip(), len(val)), path)
    return True


def scan_js_source(src, path):
    """JS/TS 正则浅扫层（RJ 族·C3 置信度·逐行）——V1.9.18"""
    findings = []
    for i, line in enumerate(src.split("\n"), 1):
        s = line.strip()
        if not s or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        for rid, rx in RJ_PATTERNS:
            m = rx.search(s)
            if m:
                findings.append(Finding(rid, i, 0, "%s：%s" % (RULES[rid][2], s[:60]), path))
                break   # 一行只报首条（避免同正则重复轰炸）
    return findings


def _walk_targets(path, exts):
    """os.walk 收集（显式进隐藏目录——glob 对点开头目录有漏扫坑·V1.9.18 dogfood 实证）"""
    out = []
    for root, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if d not in ("__pycache__", "node_modules", ".git")]
        for f in files:
            if os.path.splitext(f)[1] in exts:
                out.append(os.path.join(root, f))
    return sorted(out)


def scan_path(path, recursive=True):
    """遍历文件/目录（.py 深度扫 + .js/.ts 正则浅扫）→ findings"""
    out = []
    if os.path.isfile(path):
        targets = [path]
    elif recursive:
        targets = _walk_targets(path, {".py", ".js", ".jsx", ".ts", ".tsx", ".mjs", ".vue"})
    else:
        targets = [os.path.join(path, f) for f in sorted(os.listdir(path))
                   if os.path.splitext(f)[1] in {".py", ".js", ".jsx", ".ts", ".tsx"}]
    for p in targets:
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
            if p.endswith(".py"):
                out += scan_source(content, p)
            elif os.path.splitext(p)[1] in JS_EXTS:
                out += scan_js_source(content, p)
        except OSError as e:
            out.append(Finding("R000", 0, 0, "读取失败: %s" % e, p))
    return out


# ─────────────────────────── SARIF 输出 ───────────────────────────
def to_sarif(findings, meta):
    rules = []
    seen = set()
    for f in findings:
        if f.rule in seen or f.rule not in RULES:
            continue
        seen.add(f.rule)
        sev, cwe, desc = RULES[f.rule]
        rules.append({"id": f.rule,
                      "properties": {"cwe": cwe},
                      "fullDescription": {"text": desc}})
    results = []
    for f in findings:
        sev = RULES.get(f.rule, ("INFO",))[0]
        results.append({
            "ruleId": f.rule, "level": SEV2SARIF[sev],
            "message": {"text": f.message},
            "properties": {"cwe": RULES.get(f.rule, ("", "?"))[1]},
            "locations": [{"physicalLocation": {
                "artifactLocation": {"uri": f.file},
                "region": {"startLine": f.line, "startColumn": f.col + 1}}}],
        })
    return {"$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/"
                       "master/Schemata/sarif-schema-2.1.0.json",
            "runs": [{"tool": {"driver": {"name": "UCSS-PyScan",
                                          "version": __version__, "rules": rules}},
                      "properties": meta,
                      "results": results}]}


# ─────────────────────────── SCA（依赖漏洞·双模） ───────────────────────────
REQ_LINE = re.compile(r"^\s*([A-Za-z0-9_.\-]+)\s*([=<>!~]+)\s*([0-9][0-9A-Za-z.\-]*)")


def parse_requirements(path):
    deps = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.split("#")[0].strip()
            m = REQ_LINE.match(line)
            if m:
                deps.append((m.group(1).lower().replace("_", "-"),
                             m.group(2), m.group(3)))
    return deps


def _ver_tuple(v):
    """版本段统一键元组（数字段 > 字母段·段内可比，防 int/str 混比崩溃）"""
    parts = []
    for seg in re.split(r"[.\-]", v):
        parts.append((1, int(seg)) if seg.isdigit() else (0, seg))
    return tuple(parts) if parts else ((0, ""),)


def _in_range(version, affected):
    """OSV affected 区间判定（introduced/fixed 数轴近似，非 semver 退 C3 字符串比较）"""
    v = _ver_tuple(version)
    for rg in affected.get("ranges", []):
        intro, fixed = None, None
        for ev in rg.get("events", []):
            if "introduced" in ev:
                intro = ev["introduced"]
            if "fixed" in ev and fixed is None:
                fixed = ev["fixed"]
        if intro in (None, "0") and fixed:
            if v < _ver_tuple(fixed):
                return True, "C2"
        elif fixed is None and intro not in (None, "0"):
            if v >= _ver_tuple(intro):
                return True, "C3"
    for exact in affected.get("versions", []):
        if exact == version:
            return True, "C1"
    return False, None


def sca_check(req_path, net_mode, index_path=None):
    deps = parse_requirements(req_path)
    findings = []
    meta = {"deps": len(deps), "mode": None, "source": None}
    index = None

    # 离线快照优先（ucss-sync.py 产物 ~/.ucss/cache/——规范缓存位·不进包不进 git）
    candidates = [index_path] if index_path else [
        "osv-pypi.json",
        os.path.join(os.path.expanduser("~"), ".ucss", "cache", "osv-pypi.json")]
    for c in candidates:
        if c and os.path.isfile(c):
            try:
                with open(c, "r", encoding="utf-8") as f:
                    index = json.load(f)
                meta["source"] = "offline-snapshot: %s (%s)" % (
                    os.path.basename(c), index.get("_snapshot_time", "?"))
                break
            except (OSError, ValueError):
                pass

    if index is None and net_mode != "offline" and urllib is not None:
        # 在线批量查询
        online = {"queries": [{"package": {"name": d[0], "ecosystem": "PyPI"},
                               "version": d[2]} for d in deps]}
        try:
            req = urllib.request.Request(
                "https://api.osv.dev/v1/querybatch",
                data=json.dumps(online).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=10) as r:
                resp = json.load(r)
            meta["source"] = "online-api: api.osv.dev"
            for (name, op, ver), rslt in zip(deps, resp.get("results", [])):
                for vul in rslt.get("vulns", []):
                    hits = []
                    for aff in vul.get("affected", []):
                        if aff.get("package", {}).get("name", "").lower() == name:
                            ok, conf = _in_range(ver, aff)
                            if ok:
                                hits.append(conf)
                    if hits:
                        findings.append((name, ver, vul.get("id", "?"),
                                         vul.get("summary", "")[:80],
                                         max(hits, key=lambda c: ["C3", "C2", "C1"].index(c))))
            meta["mode"] = "online"
            return findings, meta
        except Exception as e:
            meta["note"] = "OSV 在线不可达（%s），SCA 降级快照/跳过" % str(e)[:60]

    if index is None:
        meta["mode"] = "offline-no-index"
        meta["note"] = "无离线快照且在线不可达——SCA 未执行（AST 扫描不受影响）"
        return [], meta

    # 快照查询
    meta["mode"] = "offline-snapshot"
    byname = {}
    for advisory in index.get("advisories", []):
        pks = advisory.get("packages", [])
        for pk in pks:
            byname.setdefault(pk.lower(), []).append(advisory)
    for name, op, ver in deps:
        for advisory in byname.get(name, []):
            ok, conf = _in_range(ver, {"ranges": advisory.get("ranges", []),
                                       "versions": advisory.get("versions", [])})
            if ok:
                findings.append((name, ver, advisory.get("id", "?"),
                                 advisory.get("summary", "")[:80], conf))
    return findings, meta


# ─────────────────────────── selftest（回归棘轮） ───────────────────────────
VULN_SAMPLE = '''\
import os, pickle, subprocess, hashlib, random, yaml, telnetlib
from os import *                                # R506 通配导入
API_KEY = "sk-live-abcdef1234567890xyz"
DB_PASSWORD = "hunter2SecurePass"
BACKEND = "http://payments.internal.example.com/api/v2"   # R803
WEAK_PASSWORD = "admin123"                                 # R306
DB_URL = "postgres://admin:S3cret@db.internal/x"           # R307 连接串
iv = "0123456789abcdef"                                    # R406 硬编码 IV
CUST_ID = "11010119900307425X"                             # R806 PII
aws_secret_access_key = "wJalrXUtnFEMI7KMDENGbPxRfiCY"     # R807

def run_command(user_input):
    os.system("ls " + user_input)          # R104 R201 R601
    eval(user_input)                       # R101 R603
    subprocess.run("ls", cwd=user_input)   # R605 非首参污点
    open("/tmp/x" + user_input, "w")       # R604 R606 污点写
    print(API_KEY)                         # R707 打印敏感变量

def load_cache(blob):
    pickle.loads(blob)                     # R102
    yaml.load(blob)                        # R103

def get_user(cursor, uid, username):
    cursor.execute("SELECT * FROM users WHERE id=" + uid)       # R202
    cursor.execute(f"SELECT name FROM users WHERE n={username}")  # R202 R602

def make_token():
    token = random.random()                # R402
    digest = hashlib.md5(token.encode()).hexdigest()  # R401

def load_model():
    import torch, numpy, joblib, requests
    torch.load("model.pt")                 # R108
    numpy.load("d.npy", allow_pickle=True) # R109
    joblib.load("m.joblib")                # R109
    requests.get("https://ok.example.com", auth=("u", "pass123"))  # R308
    torch.hub.load("http://cdn.example.com/m.pt")  # R706 http 加载

def hf_load(repo):
    import transformers
    transformers.AutoModel.from_pretrained(repo, trust_remote_code=True)  # R110

def fmt(user_input):
    template = "Hi " + user_input
    template.format(user_input)            # R205 模板污点

def reflect(user_input):
    getattr(os, user_input)                # R206 反射污点

def render(user_input):
    from jinja2 import Template
    Template("x" + user_input)             # R207 模板注入

def delete_file(path_param):
    cmd = "rm -rf " + path_param           # def-use 传播
    subprocess.run(cmd, shell=True)        # R104 R601（间接污点·PoC 缺口回归锚）

def fetch(url):
    import requests
    requests.get(url, verify=False)        # R403

def quiet_fail(data):
    try:
        result = risky(data)
    except:                                # R501+R502
        pass
    return result

def silent2(data):
    try:
        risky(data)
    except ValueError:                     # R502（有类型吞噬）
        pass

def check_admin(user_role):
    assert user_role == "admin", "forbidden"   # R503
    return True

def append_log(items=[]):                  # R505
    items.append("x")
    return items

def telnet_copy():
    t = telnetlib.Telnet("remote.host")    # R802
    return t
'''

CLEAN_SAMPLE = '''\
import os
import hashlib

password = os.environ.get("DB_PWD", "")        # 干净：环境变量
CACHE_URL = "redis://localhost:6379/0"         # 干净：普通常量

def run_query(cursor, uid):
    cursor.execute("SELECT * FROM config WHERE id = %s", (uid,))  # 干净：参数化

def file_checksum(path):
    with open(path, "rb") as f:                # 干净：常量路径参数变量但无拼接污点传播（path 来自本地调用，非 TAINT 名）
        return hashlib.md5(f.read()).hexdigest()  # 干净：checksum 上下文

def safe_append(items=None):
    if items is None:
        items = []
    items.append("x")
    return items

def strict():
    try:
        risky()
    except ValueError:
        return None
    return True
'''

JS_VULN_SAMPLE = '''\
const apiKey = "skJ68KxA1LmNqoP8rStUvW";        // RJ05
eval(userInput);                                 // RJ01
document.getElementById("box").innerHTML = userInput;  // RJ02
document.write(userInput);                       // RJ03
const { exec } = require("child_process");
exec("ls " + userInput);                         // RJ04
fetch("http://api.example.com/v1/users");        // RJ06
const html = "<b>" + userInput + "</b>";
const el = { dangerouslySetInnerHTML: { __html: html } };  // RJ07
const sessionToken = Math.random();              // RJ08
'''

JS_CLEAN_SAMPLE = '''\
const name = "dashboard";
const url = "https://api.example.com/v1";       // https 干净
const count = parseInt(input, 10);
const safe = el.textContent;                     // textContent 干净
const cfg = { headers: { "X-Trace": traceId } };
'''


def selftest():
    """回归自检：期望命中表 + 干净对照零误报（py+js）+ SARIF 结构 + 棘轮阈值"""
    fails_early = None
    expect = {"R101": 1, "R102": 1, "R103": 1, "R104": 2, "R201": 1, "R202": 2,
              "R205": 1, "R206": 1, "R207": 1,
              "R301": 1, "R306": 1, "R307": 1, "R308": 1,
              "R401": 1, "R402": 1, "R403": 1, "R406": 1,
              "R501": 1, "R502": 2, "R503": 1, "R505": 1, "R506": 1,
              "R601": 2, "R602": 1, "R603": 1, "R605": 1, "R606": 1,
              "R706": 1, "R707": 1,
              "R802": 1, "R803": 1, "R806": 1, "R807": 1,
              "R108": 1, "R109": 2, "R110": 1,
              "RJ01": 1, "RJ02": 1, "RJ03": 1, "RJ04": 1,
              "RJ05": 1, "RJ06": 1, "RJ07": 1, "RJ08": 1}
    with tempfile.TemporaryDirectory() as td:
        vp = os.path.join(td, "vuln.py")
        cp = os.path.join(td, "clean.py")
        jp = os.path.join(td, "vuln.js")
        jc = os.path.join(td, "clean.js")
        with open(vp, "w", encoding="utf-8") as f:
            f.write(VULN_SAMPLE)
        with open(cp, "w", encoding="utf-8") as f:
            f.write(CLEAN_SAMPLE)
        with open(jp, "w", encoding="utf-8") as f:
            f.write(JS_VULN_SAMPLE)
        with open(jc, "w", encoding="utf-8") as f:
            f.write(JS_CLEAN_SAMPLE)
        vf = scan_source(VULN_SAMPLE, vp)
        cf = scan_source(CLEAN_SAMPLE, cp)
        jf = scan_js_source(JS_VULN_SAMPLE, jp)
        jcf = scan_js_source(JS_CLEAN_SAMPLE, jc)
        # scan_path 文件类型路由断言（.js 走浅扫层）
        pf = scan_path(td)
        py_n = len([f for f in pf if f.file == vp])
        js_n = len([f for f in pf if f.file == jp])
        if js_n != len(jf) or py_n < len(vf):
            fails_early = "scan_path 路由异常 py=%d/%d js=%d/%d" % (py_n, len(vf), js_n, len(jf))

    got = {}
    for f in vf + jf:
        got[f.rule] = got.get(f.rule, 0) + 1

    fails = []
    if fails_early:
        fails.append(fails_early)
    for rid, want in expect.items():
        if got.get(rid, 0) < want:
            fails.append("规则 %s 期望≥%d 实得 %d" % (rid, want, got.get(rid, 0)))
    if cf or jcf:
        both = list(cf) + list(jcf)
        fails.append("干净对照误报 %d 项: %s" % (
            len(both), ", ".join(sorted(set(f.rule for f in both)))))
    if len(RULES) < 64:
        fails.append("规则总数 %d < 棘轮底 64（V1.9.18：56 Python + 8 JS）" % len(RULES))
    # SARIF 结构断言
    sarif = to_sarif(vf + jf, {"network": "offline", "selftest": True})
    run = sarif["runs"][0]
    if not run["tool"]["driver"]["rules"]:
        fails.append("SARIF rules 数组为空")
    for res in run["results"]:
        if not res["properties"].get("cwe"):
            fails.append("finding 缺 cwe: %s" % res["ruleId"])
        if not res["locations"][0]["physicalLocation"]["region"]["startLine"]:
            fails.append("finding 缺行号: %s" % res["ruleId"])

    # 置信度族表断言（防 R6 污点链再被误标 C3 的回归——V1.0.0 实测踩坑）
    RULE_CONF = {"1": "C1", "2": "C2", "3": "C1", "4": "C2", "5": "C1",
                 "6": "C2", "7": "C1", "8": "C3", "9": "C2", "J": "C3"}
    for rid in RULES:
        if rid[1] not in RULE_CONF:
            fails.append("规则 %s 族号不在置信度表（打印将落 C3）" % rid)

    print("UCSS-PyScan selftest V%s" % __version__)
    print("  规则总数     : %d（棘轮底 64 = 56 py + 8 js）" % len(RULES))
    print("  漏洞样本命中 : py %d + js %d findings / %d 规则族" % (len(vf), len(jf), len(got)))
    print("  干净对照误报 : py %d + js %d" % (len(cf), len(jcf)))
    print("  SARIF 结构   : rules=%d results=%d" % (len(run["tool"]["driver"]["rules"]),
                                                     len(run["results"])))
    if fails:
        print("❌ SELFTEST FAIL：")
        for x in fails:
            print("  - " + x)
        return 2
    print("✅ SELFTEST PASS（检出/误报/结构/JS 路由四面全过）")
    return 0


# ─────────────────────────── CLI ───────────────────────────
def _net_probe(mode):
    if mode != "auto":
        return mode
    if urllib is None:
        return "offline"
    try:
        req = urllib.request.Request("https://api.osv.dev/v1/query",
                                     data=b'{}', headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=3).close()
        return "online"
    except Exception:
        return "offline"


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pyscan",
                                 description="UCSS-PyScan 轻量扫描引擎（纯标准库·离线可用）")
    sub = ap.add_subparsers(dest="cmd")
    p_scan = sub.add_parser("scan", help="AST 静态扫描 → SARIF")
    p_scan.add_argument("paths", nargs="+", help="文件或目录")
    p_scan.add_argument("--sarif", default=None, help="SARIF 输出路径")
    p_scan.add_argument("--net", default="auto", choices=["auto", "online", "offline"])
    p_sca = sub.add_parser("sca", help="依赖漏洞检查（OSV 双模）")
    p_sca.add_argument("requirements", help="requirements.txt 路径")
    p_sca.add_argument("--net", default="auto", choices=["auto", "online", "offline"])
    p_sca.add_argument("--index", default=None, help="离线索引路径（默认 ~/.ucss/cache/osv-pypi.json）")
    sub.add_parser("selftest", help="回归自检（棘轮断言）")
    args = ap.parse_args(argv)

    if args.cmd == "selftest":
        return selftest()
    if args.cmd is None:
        ap.print_help()
        return 1

    if args.cmd == "scan":
        net = _net_probe(args.net)
        findings = []
        for p in args.paths:
            findings += scan_path(p)
        sev_count = {"HIGH": 0, "MEDIUM": 0, "LOW": 0, "INFO": 0}
        for f in findings:
            sev_count[RULES.get(f.rule, ("INFO",))[0]] += 1
        RULE_CONF = {"1": "C1", "2": "C2", "3": "C1", "4": "C2", "5": "C1",
                     "6": "C2", "7": "C1", "8": "C3"}   # 规则族→置信度（静态表）
        for f in sorted(findings, key=lambda x: (x.file, x.line)):
            sev, cwe, _ = RULES.get(f.rule, ("INFO", "?", ""))
            print("%s:%d  [%s/%s] %s  %s  (%s)" % (f.file, f.line, sev, cwe,
                                                   f.rule, f.message,
                                                   RULE_CONF.get(f.rule[1] if len(f.rule) > 1 else "", "C3")))
        print("\nUCSS-PyScan V%s｜模式=%s｜%d findings（HIGH %d · MEDIUM %d · LOW %d）"
              % (__version__, net, len(findings), sev_count["HIGH"],
                 sev_count["MEDIUM"], sev_count["LOW"]))
        print("网络模式说明：AST 扫描本体离线完成；SCA 需在线或快照（网络=%s）" % net)
        if args.sarif:
            with open(args.sarif, "w", encoding="utf-8") as f:
                json.dump(to_sarif(findings, {"network": net, "engine": "UCSS-PyScan",
                                              "version": __version__}), f, indent=1)
            print("SARIF → %s（可交 ucss_gate.py 判门禁）" % args.sarif)
        # 门禁退出码：存在 HIGH 即 FAIL=2（与 gate --fail-on critical,high 对齐）
        return 2 if sev_count["HIGH"] else 0

    if args.cmd == "sca":
        net = _net_probe(args.net)
        findings, meta = sca_check(args.requirements, net, args.index)
        for name, ver, vid, summary, conf in findings:
            print("[HIGH/%s] R901  %s==%s  %s  %s" % (conf, name, ver, vid, summary))
        print("\nSCA｜%d 依赖 · %d 命中 · 来源=%s" % (meta["deps"], len(findings),
                                                     meta.get("source") or meta.get("note", "?")))
        if not findings:
            print("结论：截至 %s 未发现已知漏洞" % (meta.get("source", "本模式") ))
        return 2 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
