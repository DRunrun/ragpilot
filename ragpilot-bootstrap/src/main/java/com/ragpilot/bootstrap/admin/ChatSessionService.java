package com.ragpilot.bootstrap.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 多轮会话持久化（ADM-5.1）：session / message CRUD。
 */
@Service
public class ChatSessionService {

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SessionView(
            String id,
            String title,
            String mode,
            String knowledgeBaseId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record MessageView(
            String id,
            String sessionId,
            String role,
            String content,
            String citationsJson,
            String traceId,
            String promptVersion,
            Integer tokenCount,
            OffsetDateTime createdAt
    ) {
    }

    private final RowMapper<SessionView> sessionMapper = (rs, i) -> new SessionView(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("mode"),
            rs.getString("knowledge_base_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class)
    );

    private final RowMapper<MessageView> messageMapper = (rs, i) -> new MessageView(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("citations_json"),
            rs.getString("trace_id"),
            rs.getString("prompt_version"),
            (Integer) rs.getObject("token_count"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    public SessionView create(String title, String mode, String knowledgeBaseId) {
        String id = "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String m = (mode == null || mode.isBlank()) ? "RAG" : mode.strip().toUpperCase();
        jdbcTemplate.update(
                """
                        INSERT INTO chat_session(id, title, mode, knowledge_base_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, NOW(), NOW())
                        """,
                id,
                title == null || title.isBlank() ? "新会话" : title.strip(),
                m,
                knowledgeBaseId);
        return get(id).orElseThrow();
    }

    public Optional<SessionView> get(String id) {
        List<SessionView> rows = jdbcTemplate.query(
                """
                        SELECT id, title, mode, knowledge_base_id, created_at, updated_at
                        FROM chat_session WHERE id = ?
                        """,
                sessionMapper, id);
        return rows.stream().findFirst();
    }

    public List<SessionView> list(int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        return jdbcTemplate.query(
                """
                        SELECT id, title, mode, knowledge_base_id, created_at, updated_at
                        FROM chat_session
                        ORDER BY updated_at DESC
                        LIMIT ?
                        """,
                sessionMapper, lim);
    }

    @Transactional
    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM chat_message WHERE session_id = ?", id);
        jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?", id);
    }

    public List<MessageView> listMessages(String sessionId) {
        return jdbcTemplate.query(
                """
                        SELECT id, session_id, role, content, citations_json, trace_id,
                               prompt_version, token_count, created_at
                        FROM chat_message
                        WHERE session_id = ?
                        ORDER BY created_at ASC
                        """,
                messageMapper, sessionId);
    }

    public MessageView appendMessage(
            String sessionId,
            String role,
            String content,
            String citationsJson,
            String traceId,
            String promptVersion,
            Integer tokenCount
    ) {
        if (get(sessionId).isEmpty()) {
            throw new KnowledgeBaseService.NotFoundException("会话不存在: " + sessionId);
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                        INSERT INTO chat_message(
                            id, session_id, role, content, citations_json, trace_id,
                            prompt_version, token_count, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                        """,
                id, sessionId, role, content, citationsJson, traceId, promptVersion, tokenCount);
        // 首条用户消息可更新标题
        if ("user".equalsIgnoreCase(role)) {
            jdbcTemplate.update(
                    """
                            UPDATE chat_session
                            SET title = CASE WHEN title = '新会话' THEN ? ELSE title END,
                                updated_at = NOW()
                            WHERE id = ?
                            """,
                    content.length() > 40 ? content.substring(0, 40) + "…" : content,
                    sessionId);
        } else {
            jdbcTemplate.update("UPDATE chat_session SET updated_at = NOW() WHERE id = ?", sessionId);
        }
        return listMessages(sessionId).stream()
                .filter(m -> m.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    /**
     * 组装多轮上下文前缀（最近若干轮），拼进当前问题前供检索/生成参考。
     */
    public String buildContextualQuestion(String sessionId, String currentQuestion, int maxTurns) {
        List<MessageView> all = listMessages(sessionId);
        int keep = Math.max(maxTurns, 1) * 2;
        List<MessageView> recent = all.size() <= keep ? all : all.subList(all.size() - keep, all.size());
        if (recent.isEmpty()) {
            return currentQuestion;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是对话历史，请结合历史理解当前问题：\n");
        for (MessageView m : recent) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        sb.append("user问题: ").append(currentQuestion);
        return sb.toString();
    }

    public Map<String, Object> toListPayload(List<SessionView> items) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        return data;
    }
}
