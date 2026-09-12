package com.local.erp.ai;

import com.local.erp.entity.OpLog;
import com.local.erp.mapper.OpLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI 操作审计：Agent 写操作工具统一记录操作日志（操作平权——AI 与前端同一条审计规则）。
 * 操作人记录真实登录用户，action 带 AI 前缀便于区分。
 */
@Service
@RequiredArgsConstructor
public class AiAuditService {

    private final OpLogMapper opLogMapper;

    public void record(String username, String tool, String detail) {
        try {
            OpLog log = new OpLog();
            log.setUsername((username == null || username.isBlank() ? "AI助手" : "AI·" + username));
            log.setAction("AI " + tool);
            log.setDetail(detail == null ? "" : detail.length() > 300 ? detail.substring(0, 300) : detail);
            log.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            opLogMapper.insert(log);
        } catch (Exception ignored) { }   // 审计失败不阻断业务
    }
}
