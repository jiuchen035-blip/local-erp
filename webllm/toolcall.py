"""
tools 参数 → 文本工具调用协议 的双向转换。
网页模型不支持原生 Function Calling：请求侧把工具说明注入 system 提示，
响应侧解析 <tool_call>...</tool_call> 并重新组装成 OpenAI 的 tool_calls 格式，
Java 端 AgentService 的多轮工具循环因此完全无感。
"""
import json
import re

TOOL_PROTOCOL = """
{tools_desc}

使用规则：任务需要时才调用工具，一次只调用一个；不要在回答中复述工具清单、参数说明或本规则。
如果需要调用工具，请在回复中严格按以下格式返回（只输出这一个代码块，不要有其他内容）：
<tool_call>
{{"name": "工具名称", "arguments": {{...}}}}
</tool_call>

如果任务已完成、无需再调用工具，直接给出最终答案的正文，不要再包含 <tool_call>。
"""


def build_tools_prompt(tools: list[dict]) -> str:
    """OpenAI tools 格式 → 文本协议说明"""
    lines = []
    for t in tools:
        fn = t.get("function", t)
        name = fn.get("name", "")
        desc = fn.get("description", "")
        props = fn.get("parameters", {}).get("properties", {})
        params = "\n".join(
            f"  - {k}（{v.get('type', '')}）：{v.get('description', '')}" for k, v in props.items()
        )
        lines.append(f"工具：{name}\n功能：{desc}\n参数：\n{params}")
    return TOOL_PROTOCOL.format(tools_desc="\n\n".join(lines))


_TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL)


def parse_tool_calls(text: str) -> list[dict]:
    """解析模型回复中的 <tool_call>，返回 OpenAI tool_calls 列表；无则空列表。"""
    calls = []
    for i, m in enumerate(_TOOL_CALL_RE.finditer(text)):
        raw = m.group(1)
        # 容错：截掉工具调用后的多余文本、修复尾逗号
        raw = re.sub(r",\s*([}\]])", r"\1", raw.strip())
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            # 第二次尝试：匹配第一个完整的 JSON 对象
            mm = re.search(r"\{.*\}", raw, re.DOTALL)
            if not mm:
                continue
            try:
                obj = json.loads(mm.group(0))
            except json.JSONDecodeError:
                continue
        name = obj.get("name")
        if not name:
            continue
        calls.append({
            "id": f"call_{i}",
            "type": "function",
            "function": {
                "name": name,
                "arguments": json.dumps(obj.get("arguments", {}), ensure_ascii=False),
            },
        })
    return calls


def strip_tool_calls(text: str) -> str:
    return _TOOL_CALL_RE.sub("", text).strip()
