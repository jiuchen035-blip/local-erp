"""东方财富「妙想」适配器。真实聊天页 https://ai.eastmoney.com/chat（未登录会重定向到 /miaoxiang/）。
选择器为候选列表，站点改版后按实际 DOM 更新。"""
from .base import BaseAdapter


class MiaoxiangAdapter(BaseAdapter):
    name = "miaoxiang"
    url = "https://ai.eastmoney.com/chat"

    input_selectors = [
        "textarea[placeholder*='输入']",
        "textarea[placeholder*='了解']",
        "textarea",
        "[contenteditable='true']",
    ]
    answer_selectors = [
        "[class*='markdown-body']",
        "[class*='answer'] [class*='markdown']",
        "[class*='chat'] [class*='markdown']",
        "[class*='message'] [class*='content']",
        "[class*='markdown']",
    ]
    send_with_enter = True
    # 未登录：重定向到 /miaoxiang/ 落地页
    login_url_patterns = ["ai.eastmoney.com/miaoxiang/", "passport."]
    login_hints = [
        "button:has-text('登录')",
        "a:has-text('登录')",
    ]
