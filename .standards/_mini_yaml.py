# -*- coding: utf-8 -*-
"""_mini_yaml.py — 受控子集 YAML 解析器（纯标准库·V1.9.28）

背景：meta_check.py 与 aegis_check.py 曾顶层 import yaml（PyYAML）挂门禁链——
用户环境无 pyyaml 时装完自检即崩（S3 违纪·F-001-01，范围 2026-10-16 执行批
经零依赖审计扩至两文件）。本模块以纯标准库实现**模板所需的受控子集**：
  - 两空格任意倍数缩进的嵌套 dict
  - 块式列表（key: 换行后缩进 - 项）与流式列表（[] / ["a", "b"]）
  - null / true / false / 裸词字符串（partial / workflow / 质量门型 等）
  - 单双引号字符串（受控子集内无转义）
  - # 注释（独立行 + 值后注释——仅当 # 前有空白且值未开引号时剥离）
超集形态（块式多行字符串/锚点/merge/dict-in-list 等）不在支持范围——遇到即抛
MiniYAMLError，由调用方按解析失败处理（fail fast·不静默降级）。

对账依据：CAPA-001 verify——本解析器在现存全部 4 件模板（meta 1+aegis 3）上
输出 == yaml.safe_load 输出（逐键对账·2026-10-16 执行批验证记录）。
"""


class MiniYAMLError(ValueError):
    pass


def _parse_flow_map(body):
    out, buf, quote = {}, "", None
    for ch in body:
        if quote:
            buf += ch
            if ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
            buf += ch
        elif ch == ",":
            out[buf.strip()] = True  # 占位，稍后统一处理
            buf = ""
        else:
            buf += ch
    parts = []
    buf, quote = "", None
    for ch in body:
        if quote:
            buf += ch
            if ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
            buf += ch
        elif ch == ",":
            parts.append(buf)
            buf = ""
        else:
            buf += ch
    if buf.strip():
        parts.append(buf)
    out = {}
    for part in parts:
        k, v = _split_kv(part.strip())
        if k is None:
            raise MiniYAMLError("流式映射键值解析失败: %r" % part)
        out[k] = _parse_scalar(v) if v else None
    return out


def _parse_scalar(s):
    s = s.strip()
    if s == "" or s == "null" or s == "~":
        return None
    if s in ("true", "True", "TRUE"):
        return True
    if s in ("false", "False", "FALSE"):
        return False
    if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"'):
        return s[1:-1]
    if s.startswith("{"):
        if not s.endswith("}"):
            raise MiniYAMLError("流式映射未闭合: %r" % s)
        return _parse_flow_map(s[1:-1])
    if s.startswith("["):
        if not s.endswith("]"):
            raise MiniYAMLError("流式列表未闭合: %r" % s)
        body = s[1:-1].strip()
        if not body:
            return []
        items, buf, quote = [], "", None
        for ch in body:
            if quote:
                buf += ch
                if ch == quote:
                    quote = None
            elif ch in ("'", '"'):
                quote = ch
                buf += ch
            elif ch == ",":
                items.append(buf)
                buf = ""
            else:
                buf += ch
        if buf.strip():
            items.append(buf)
        return [_parse_scalar(i) for i in items]
    if s and (s.isdigit() or (s[0] in "+-" and s[1:].isdigit())):
        return int(s)
    return s


def _split_kv(stripped):
    in_q = None
    for i, ch in enumerate(stripped):
        if in_q:
            if ch == in_q:
                in_q = None
        elif ch in ("'", '"'):
            in_q = ch
        elif ch == ":" and (i + 1 == len(stripped) or stripped[i + 1] in " \t"):
            return stripped[:i].strip(), stripped[i + 1:].strip()
    return None, None


def _strip_comment(rest):
    if rest.startswith("#"):
        return ""
    if rest.startswith(("'", '"')):
        q = rest[0]
        for i in range(1, len(rest)):
            if rest[i] == q:
                return rest[: i + 1]
        raise MiniYAMLError("引号未闭合: %r" % rest)
    for i in range(1, len(rest)):
        if rest[i] == "#" and rest[i - 1] in " \t":
            return rest[:i].rstrip()
    return rest.rstrip()


def loads(text):
    """解析受控子集 YAML 文本 → dict/list。不支持形态抛 MiniYAMLError。"""
    lines = []
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.lstrip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(stripped)
        if indent % 2 != 0:
            raise MiniYAMLError("缩进非两空格倍数: %r" % line)
        lines.append((indent, stripped))

    def parse_node(i, base):
        """解析从 i 起、缩进=base 的节点 → (obj, next_i)。"""
        def is_item(idx):
            return lines[idx][1].startswith("- ") or lines[idx][1] == "-"

        def read_item(j):
            """读一个列表项 → (值, next_j)。标量项 / dict 项（- key: v + 同列续键）。"""
            item = lines[j][1][1:].strip()
            k, v = _split_kv(item)
            if k is None:
                if not item:
                    raise MiniYAMLError("空列表项不支持: 行 %d" % (j + 1))
                return _parse_scalar(item), j + 1
            if v:
                d = {k: _parse_scalar(_strip_comment(v))}
            else:
                d = {k: None}
            j += 1
            while j < len(lines) and lines[j][0] == base + 2 and not is_item(j):
                k2, v2 = _split_kv(lines[j][1])
                if k2 is None:
                    raise MiniYAMLError("dict 列表项续行解析失败: %r" % lines[j][1])
                d[k2] = _parse_scalar(_strip_comment(v2)) if v2 else None
                j += 1
            return d, j

        if is_item(i):
            out = []
            while i < len(lines) and lines[i][0] == base and is_item(i):
                val, i = read_item(i)
                out.append(val)
            return out, i
        out = {}
        while i < len(lines) and lines[i][0] == base:
            stripped = lines[i][1]
            if is_item(i):
                raise MiniYAMLError("dict 块中混入列表项: %r" % stripped)
            key, rest = _split_kv(stripped)
            if key is None:
                raise MiniYAMLError("无法解析键值行: %r" % stripped)
            if rest:
                rest = _strip_comment(rest)
            if rest:
                out[key] = _parse_scalar(rest)
                i += 1
            else:
                i += 1
                if i < len(lines) and lines[i][0] > base:
                    out[key], i = parse_node(i, lines[i][0])
                else:
                    out[key] = {}
        return out, i
    if not lines:
        return {}
    obj, _ = parse_node(0, lines[0][0])
    return obj
