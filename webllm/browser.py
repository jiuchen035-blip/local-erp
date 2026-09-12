"""
Playwright 持久化浏览器管理：每站点独立 profile 保存登录态，按站点串行锁。
网页版聊天天然不支持并发，同一时间一个站点只跑一个对话。
profile 目录可用环境变量 WEBLLM_PROFILE_DIR 覆盖（打包模式下由 Java 指到 data 目录，升级重装不丢登录）。
"""
import asyncio
import os
from pathlib import Path
from playwright.async_api import async_playwright, Browser, BrowserContext, Page

PROFILE_ROOT = Path(os.environ.get("WEBLLM_PROFILE_DIR") or (Path(__file__).parent / "profile"))


class SiteSession:
    def __init__(self, site: str):
        self.site = site
        self.lock = asyncio.Lock()
        self.context: BrowserContext | None = None
        self.page: Page | None = None


class BrowserManager:
    def __init__(self):
        self._pw = None
        self._browser: Browser | None = None
        self._sessions: dict[str, SiteSession] = {}
        self._global = asyncio.Lock()

    async def _ensure_pw(self):
        async with self._global:
            if self._pw is None:
                self._pw = await async_playwright().start()

    def session(self, site: str) -> SiteSession:
        if site not in self._sessions:
            self._sessions[site] = SiteSession(site)
        return self._sessions[site]

    async def get_page(self, site: str, headless: bool = True) -> Page:
        """获取（或复用）某站点的页面；上下文已断开时重建。"""
        s = self.session(site)
        if s.page is not None and not s.page.is_closed():
            return s.page
        await self._ensure_pw()
        profile_dir = PROFILE_ROOT / site
        profile_dir.mkdir(parents=True, exist_ok=True)
        if s.context:
            try:
                await s.context.close()
            except Exception:
                pass
        s.context = await self._pw.chromium.launch_persistent_context(
            str(profile_dir),
            headless=headless,
            viewport={"width": 1280, "height": 900},
            args=["--disable-blink-features=AutomationControlled"],
        )
        s.page = s.context.pages[0] if s.context.pages else await s.context.new_page()
        return s.page

    async def close(self, site: str):
        s = self._sessions.get(site)
        if s and s.context:
            try:
                await s.context.close()
            except Exception:
                pass
            s.context = None
            s.page = None


manager = BrowserManager()
