-- V33: 消息幂等支持
-- client_id 由前端生成（每次发送动作一个 UUID，网络重试复用），
-- (session_id, client_id) 唯一键兜底重复落库；历史数据 client_id 均为 NULL 不受影响。

ALTER TABLE session_message
    ADD UNIQUE KEY uk_session_message_client (session_id, client_id);
