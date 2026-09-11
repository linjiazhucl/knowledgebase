INSERT INTO kb_user (id, username, password_hash, nickname, role, status, last_active_at)
VALUES
    (1, 'admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '知识库管理员', 'admin', 'active', CURRENT_TIMESTAMP),
    (2, 'li.ming', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '李明', 'admin', 'active', CURRENT_TIMESTAMP),
    (3, 'wang.yue', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '王悦', 'admin', 'active', CURRENT_TIMESTAMP),
    (4, 'chen.jie', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '陈杰', 'admin', 'active', CURRENT_TIMESTAMP),
    (5, 'zhao.nan', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '赵楠', 'admin', 'active', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE role = 'admin', status = 'active';

INSERT INTO kb_model_config (model_type, config_name, provider, api_url, model_name, api_key, status)
VALUES
    ('chat', '主对话模型', '', '', '', '', 'active'),
    ('embedding', '知识向量模型', '', '', '', '', 'active')
ON DUPLICATE KEY UPDATE
    config_name = CASE
        WHEN config_name IS NULL OR TRIM(config_name) = '' OR config_name REGEXP '^[?]+$'
            THEN VALUES(config_name)
        ELSE config_name
    END;

INSERT INTO kb_knowledge_base (id, name, description, chunk_size, overlap_size, document_count, segment_count, color)
VALUES
    (1, '企业制度与流程', '人事、财务、行政及内控规范', 800, 120, 0, 0, 'sage'),
    (2, '产品与解决方案', '产品手册、方案白皮书与案例', 1000, 150, 0, 0, 'blue'),
    (3, '客户服务知识', '服务标准、SOP 与常见问题', 700, 100, 0, 0, 'orange'),
    (4, '品牌与市场资料', '品牌规范、市场活动与媒体资料', 900, 120, 0, 0, 'purple')
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO kb_system_setting (setting_key, setting_value)
VALUES ('topK', '5'), ('threshold', '0.70'), ('history', '6')
ON DUPLICATE KEY UPDATE setting_key = setting_key;
