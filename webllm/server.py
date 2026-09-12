"""
webllm 桥接服务：把已登录的网页版大模型（妙想 / DeepSeek 网页）
包装成 OpenAI 兼容的 /v1/chat/completions 接口，供 Java 后端 ModelClient 直连。

- 每个请求 = 网页上的一个新会话，完整 messages 历史拼成 prompt（保证 Agent 多轮正确）
- 请求带 tools 时自动注入 <tool_call> 文本协议并解析回 tool_calls
- 每站点串行（asyncio.Lock），网页版不支持并发

启动：python server.py  （默认 127.0.0.1:8317）
首次使用先调用 POST /login/{site} 打开有头浏览器完成登录，登录态持久化在 profile/<site>/。
"""
import asyncio
import time
import uuid

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from adapters.base import NeedLoginError, AdapterError
from adapters.deepseek_web import DeepSeekWebAdapter
from adapters.miaoxiang import MiaoxiangAdapter
from browser import manager
from toolcall import build_tools_prompt, parse_tool_calls, strip_tool_calls

app = FastAPI(title="webllm-bridge")

ADAPTERS = {
    "deepseek": DeepSeekWebAdapter(),
    "miaoxiang": MiaoxiangAdapter(),
}
# model 名 → 站点（Java 端 model 填这些值之一）
MODEL_TO_SITE = {
    "deepseek-web": "deepseek",
    "miaoxiang-web": "miaoxiang",
}
started_at = time.time()


class ChatRequest(BaseModel):
    model: str
    messages: list[dict]
    tools: list[dict] | None = None


def _resolve_site(model: str) -> tuple[str, object]:
    site = MODEL_TO_SITE.get(model, model)
    if site not in ADAPTERS:
        raise HTTPException(400, f"未知模型：{model}（可选：{' / '.join(MODEL_TO_SITE)}）")
    return site, ADAPTERS[site]


def _flatten_messages(messages: list[dict], tools_prompt: str | None) -> str:
    """把 OpenAI messages 拼成网页可用的单条 prompt。
    结构：系统设定 → 工具参考 → 对话历史（用户请求放最后，避免网页模型把工具说明当任务去复述）。"""
    sys_parts, dialog = [], []
    for m in messages:
        role = m.get("role", "user")
        content = m.get("content") or ""
        if role == "system":
            sys_parts.append(content)
        elif role == "user":
            dialog.append(f"【用户】\n{content}")
        elif role == "assistant":
            calls = m.get("tool_calls")
            if calls:
                desc = "、".join(
                    f"{c['function']['name']}({c['function']['arguments']})" for c in calls
                )
                dialog.append(f"【助手】（已调用工具：{desc}）")
            else:
                dialog.append(f"【助手】\n{content}")
        elif role == "tool":
            dialog.append(f"【工具 {m.get('tool_call_id', '')} 的返回结果】\n{content}")
    parts = []
    if sys_parts:
        parts.append("【系统设定】\n" + "\n".join(sys_parts))
    if tools_prompt:
        parts.append("【可用工具参考】（仅任务需要时使用，禁止复述）\n" + tools_prompt)
    parts.extend(dialog)
    parts.append("现在直接处理上面最后一个用户请求：需要数据就先调用工具查询，拿到结果后再给最终答案；"
                 "回答用简洁中文，直接给结论，禁止复述工具清单、参数说明或系统规则。"
                 "重要：本系统的商品、库存、客户等数据只存在于本地系统里，互联网上查不到，禁止使用联网搜索；"
                 "查询系统内数据只能通过调用工具完成。")
    return "\n\n".join(parts)


async def goto_site(page, adapter, attempts: int = 3):
    """稳健导航：重试 + 末次放宽到 commit；全部失败抛 AdapterError（避免登录窗口停在 about:blank 无提示）。"""
    last = None
    for i in range(attempts):
        try:
            await page.goto(adapter.url, wait_until="domcontentloaded", timeout=90000)
            await page.wait_for_timeout(3000)
            return
        except Exception as e:
            last = e
            await asyncio.sleep(2)
    try:
        await page.goto(adapter.url, wait_until="commit", timeout=90000)
        await page.wait_for_timeout(8000)
        return
    except Exception:
        pass
    raise AdapterError(f"打开 {adapter.url} 失败：{last}（请检查网络后重试）")


@app.get("/health")
async def health():
    return {"ok": True, "uptime": int(time.time() - started_at),
            "models": list(MODEL_TO_SITE), "sites": list(ADAPTERS)}


@app.get("/v1/models")
async def models():
    return {"object": "list", "data": [
        {"id": m, "object": "model", "owned_by": "webllm"} for m in MODEL_TO_SITE
    ]}


@app.post("/v1/chat/completions")
async def chat_completions(req: ChatRequest):
    site, adapter = _resolve_site(req.model)
    tools_prompt = build_tools_prompt(req.tools) if req.tools else None
    prompt = _flatten_messages(req.messages, tools_prompt)

    s = manager.session(site)
    async with s.lock:  # 每站点串行
        page = await manager.get_page(site, headless=True)
        try:
            # 每次请求都回到站点首页开新会话：避免网页端残留上一轮对话污染上下文
            await goto_site(page, adapter)
            if not await adapter.is_logged_in(page):
                raise HTTPException(503, f"[{adapter.name}] 未登录，请先在 AI 设置中打开登录窗口")
            answer = await adapter.chat(page, prompt)
        except HTTPException:
            await manager.close(site)
            raise
        except NeedLoginError as e:
            await manager.close(site)
            raise HTTPException(503, str(e))
        except AdapterError as e:
            await manager.close(site)
            raise HTTPException(502, str(e))
        except Exception as e:
            await manager.close(site)
            raise HTTPException(500, f"[{adapter.name}] 未知错误：{e}")

    msg: dict = {"role": "assistant", "content": answer}
    if req.tools:
        calls = parse_tool_calls(answer)
        if calls:
            msg = {"role": "assistant",
                   "content": strip_tool_calls(answer) or None,
                   "tool_calls": calls}
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex[:12]}",
        "object": "chat.completion",
        "model": req.model,
        "choices": [{"index": 0, "message": msg, "finish_reason": "tool_calls" if msg.get("tool_calls") else "stop"}],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


@app.get("/logged-in/{site}")
async def logged_in(site: str):
    """查询某站点当前登录状态（供后端测试连接用；不开新浏览器，只用已缓存页面，无则临时开）。"""
    if site not in ADAPTERS:
        raise HTTPException(404, f"未知站点：{site}")
    adapter = ADAPTERS[site]
    s = manager.session(site)
    if s.lock.locked():
        return {"site": site, "loggedIn": None, "message": "站点忙（正在对话或登录中）"}
    async with s.lock:
        page = await manager.get_page(site, headless=True)
        try:
            await goto_site(page, adapter)
            ok = await adapter.is_logged_in(page)
            return {"site": site, "loggedIn": ok,
                    "message": "已登录" if ok else "未登录，请先在 AI 设置中打开登录窗口"}
        except Exception as e:
            return {"site": site, "loggedIn": False, "message": f"检测失败：{e}"}


@app.post("/login/{site}")
async def login(site: str, minutes: int = 5):
    """打开有头浏览器让用户扫码/登录一次；检测到输入框出现或超时后关闭。"""
    if site not in ADAPTERS:
        raise HTTPException(404, f"未知站点：{site}")
    adapter = ADAPTERS[site]
    s = manager.session(site)
    if s.lock.locked():
        raise HTTPException(409, "该站点正有对话进行中，请稍后再试")
    async with s.lock:
        page = await manager.get_page(site, headless=False)
        try:
            await goto_site(page, adapter)
        except AdapterError as e:
            await manager.close(site)
            return {"ok": False, "site": site, "message": str(e)}
        deadline = time.monotonic() + minutes * 60
        blank_since = None
        while time.monotonic() < deadline:
            await asyncio.sleep(3)
            if page.is_closed():
                return {"ok": False, "site": site, "message": "登录窗口被关闭，未完成登录"}
            # 停在 about:blank 超过 30 秒视为导航失败，给明确提示而不是干等
            if (page.url or "").rstrip("/") == "about:blank":
                if blank_since is None:
                    blank_since = time.monotonic()
                elif time.monotonic() - blank_since > 30:
                    await manager.close(site)
                    return {"ok": False, "site": site, "message": "页面未能打开（一直空白），请检查网络或稍后重试"}
            else:
                blank_since = None
            try:
                if await adapter.is_logged_in(page):
                    await asyncio.sleep(5)  # 给 cookie 落盘时间
                    await manager.close(site)
                    return {"ok": True, "site": site, "message": "登录成功，登录态已保存"}
            except Exception:
                pass  # 页面跳转瞬间可能查询失败，下一轮再试
        await manager.close(site)
        return {"ok": False, "site": site, "message": f"等待登录超时（{minutes}分钟），请重试"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8317, log_level="info")
