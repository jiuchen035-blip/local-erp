"""DeepSeek 网页版（chat.deepseek.com）适配器。选择器基于 2025-09 版 UI，改版后需更新候选列表。"""
from .base import BaseAdapter


class DeepSeekWebAdapter(BaseAdapter):
    name = "deepseek"
    url = "https://chat.deepseek.com/"

    input_selectors = [
        "textarea#chat-input",
        "textarea[placeholder*='DeepSeek']",
        "textarea",
    ]
    answer_selectors = [
        "div.ds-markdown--block",
        ".ds-markdown",
        "[class*='markdown'] p",
    ]
    send_with_enter = True
    # 未登录会重定向到 /sign_in
    login_url_patterns = ["/sign_in", "/sign-in"]
    login_hints = [
        "button:has-text('登录')",
        "a:has-text('登录')",
    ]
    # 深度思考=几分钟才出答案；联网搜索=把模型带去互联网查系统里才有的数据，都会带偏任务
    toggle_off_labels = ["深度思考", "联网搜索"]
