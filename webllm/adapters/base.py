"""
站点适配器基类：通用的「输入 → 发送 → 等待流式输出完成 → 提取回答」流程。
各站点只需提供：URL、输入框定位、发送方式、回答区候选选择器。
回答完成判定：文本静默 N 秒兜底（流式输出结束的通用信号）。
"""
import asyncio
import time

from playwright.async_api import Page

# 兜底用：Playwright 候选选择器全部失效时，用 scrapling 解析全量 HTML 自适应寻找回答区
try:
    from scrapling import Selector
except Exception:  # scrapling 未安装时降级为纯 Playwright
    Selector = None

FALLBACK_CSS = [
    "[class*='markdown']",
    "[class*='answer']",
    "[class*='message'] [class*='content']",
    "[class*='response']",
    "article",
]


class NeedLoginError(Exception):
    """页面要求登录（找不到输入框，且检测到登录弹窗/按钮）"""


class AdapterError(Exception):
    """站点改版等导致定位失败"""


class BaseAdapter:
    name = ""
    url = ""
    # 输入框候选选择器（按优先级）
    input_selectors: list[str] = []
    # 回答区候选选择器：返回元素列表，取最后一个为最新回答
    answer_selectors: list[str] = []
    # 发送键（None 表示用点击 send_button_selectors）
    send_with_enter = True
    send_button_selectors: list[str] = []
    # 登录特征：出现任意一种即视为未登录（登录页上也可能有别的输入框，特征优先）
    login_hints: list[str] = []
    # URL 特征：当前地址包含任意子串即视为未登录（如重定向到登录页）
    login_url_patterns: list[str] = []

    # 发送前应关闭的站点功能开关（如 DeepSeek 的深度思考/联网搜索：前者拖慢回答，后者会把模型带去互联网上找数据）
    toggle_off_labels: list[str] = []

    # 流式输出静默判定：网页流式偶发停顿超过数秒，8 秒不变才认为回答完成（总上限 7 分钟，兼容深度思考）
    silence_seconds = 8.0
    min_wait_seconds = 3.0
    total_timeout = 420.0

    async def is_logged_in(self, page: Page) -> bool:
        """登录特征（URL / 登录按钮）优先于输入框判定：登录页也可能渲染别的输入框。"""
        u = page.url or ""
        for p in self.login_url_patterns:
            if p in u:
                return False
        for sel in self.login_hints:
            try:
                el = page.locator(sel).first
                if await el.count() > 0 and await el.is_visible():
                    return False
            except Exception:
                continue
        return await self._first(page, self.input_selectors) is not None

    async def chat(self, page: Page, prompt: str) -> str:
        """发送 prompt 并等待回答完成，返回回答文本。"""
        box = await self._first(page, self.input_selectors)
        if box is None:
            hint = await self._first(page, self.login_hints)
            if hint is not None:
                raise NeedLoginError(f"[{self.name}] 需要登录：请先在设置中打开登录窗口完成登录")
            raise AdapterError(f"[{self.name}] 找不到输入框，站点可能已改版")

        # 整段粘贴式输入（fill 不产生逐字按键）：prompt 里的换行会被逐字打字触发「回车发送」，
        # 把长 prompt 拆成几十条消息（模型每段回一句"收到"），必须一次性放进输入框
        await self._disable_toggles(page)
        await box.click()
        await box.fill(prompt)
        await page.wait_for_timeout(500)  # 等前端 input 事件渲染完（发送按钮点亮）

        if self.send_with_enter:
            await box.press("Enter")
            await page.wait_for_timeout(1500)
            # 个别情况下 Enter 未生效（输入框还有残留）则补发一次
            try:
                leftover = (await box.input_value()).strip()
            except Exception:
                leftover = ""  # contenteditable 输入框没有 input_value，跳过检查
            if leftover:
                await box.press("Enter")
        else:
            btn = await self._first(page, self.send_button_selectors)
            if btn is None:
                raise AdapterError(f"[{self.name}] 找不到发送按钮")
            await btn.click()

        return await self._wait_answer(page)

    async def _disable_toggles(self, page: Page):
        """关闭账号级功能开关（如联网搜索/深度思考）。只在能从 class 判断出"已开启"时才点，
        避免把关着的开关点开；识别不了就跳过，靠 prompt 指令兜底。"""
        for label in self.toggle_off_labels:
            try:
                els = page.locator(f"button:has-text('{label}'), [role='button']:has-text('{label}')")
                n = await els.count()
                for i in range(n):
                    el = els.nth(i)
                    if not await el.is_visible():
                        continue
                    cls = ((await el.get_attribute('class')) or '').lower()
                    if any(k in cls for k in ('active', 'select', 'checked', 'enable', 'on')):
                        await el.click()
                        await page.wait_for_timeout(600)
                        break
            except Exception:
                pass

    async def _wait_answer(self, page: Page) -> str:
        """轮询回答区：文本静默 silence_seconds 且非空即完成。"""
        start = time.monotonic()
        last_text, last_change = "", time.monotonic()
        first_text_at = None
        while time.monotonic() - start < self.total_timeout:
            await asyncio.sleep(0.8)
            text = await self._extract_last_answer(page)
            if text and text != last_text:
                last_text, last_change = text, time.monotonic()
                if first_text_at is None:
                    first_text_at = time.monotonic()
            elif text and text == last_text:
                elapsed = time.monotonic() - last_change
                if first_text_at is not None \
                        and time.monotonic() - first_text_at >= self.min_wait_seconds \
                        and elapsed >= self.silence_seconds:
                    return last_text
            # 新会话中旧回答仍在 DOM：要求文本出现且稳定
        if last_text:
            return last_text
        raise AdapterError(f"[{self.name}] 等待回答超时（{self.total_timeout:.0f}s），站点可能改版或响应极慢")

    async def _extract_last_answer(self, page: Page) -> str | None:
        for sel in self.answer_selectors:
            try:
                els = page.locator(sel)
                n = await els.count()
                if n == 0:
                    continue
                # 取最后一个可见、非空的
                for i in range(n - 1, max(n - 6, -1), -1):
                    el = els.nth(i)
                    if await el.is_visible():
                        text = (await el.inner_text()).strip()
                        if text:
                            return text
            except Exception:
                continue
        return await self._scrapling_fallback(page)

    async def _scrapling_fallback(self, page: Page) -> str | None:
        """候选选择器全部失效：抓全量 HTML 交给 scrapling 按结构自适应定位回答区（取文档序最后一个匹配）。"""
        if Selector is None:
            return None
        try:
            html = await page.content()
            tree = Selector(html)
            for css in FALLBACK_CSS:
                try:
                    els = tree.css(css)
                except Exception:
                    continue
                if els:
                    text = els[-1].get_all_text(separator=' ', strip=True)
                    if text:
                        return text
        except Exception:
            pass
        return None

    @staticmethod
    async def _first(page: Page, selectors: list[str]):
        for sel in selectors:
            try:
                el = page.locator(sel).first
                if await el.count() > 0 and await el.is_visible():
                    return el
            except Exception:
                continue
        return None
