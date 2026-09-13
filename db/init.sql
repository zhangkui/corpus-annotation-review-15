-- ============================================================================
-- 多语言语料标注冲突审查系统 — TimescaleDB 2.15 / PostgreSQL 16 初始化脚本
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- ---------------------------------------------------------------------------
-- 用户（标注员 / 仲裁员 / 管理员）
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    role         TEXT NOT NULL CHECK (role IN ('annotator', 'arbitrator', 'admin')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 语料库与语料片段
-- ---------------------------------------------------------------------------
CREATE TABLE corpus (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE segment (
    id            BIGSERIAL PRIMARY KEY,
    corpus_id     BIGINT NOT NULL REFERENCES corpus (id),
    language_code TEXT   NOT NULL,               -- BCP-47，应用层校验
    content       TEXT   NOT NULL,
    char_length   INT    NOT NULL CHECK (char_length >= 0),  -- Unicode 码点长度
    external_id   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (corpus_id, external_id)
);
CREATE INDEX idx_segment_corpus ON segment (corpus_id);
CREATE INDEX idx_segment_lang   ON segment (language_code);

-- ---------------------------------------------------------------------------
-- 标签层级（不能成环 —— 应用层递归 CTE 校验）
-- ---------------------------------------------------------------------------
CREATE TABLE tag (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    parent_id  BIGINT REFERENCES tag (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (parent_id, name)
);
CREATE UNIQUE INDEX idx_tag_root_name ON tag (name) WHERE parent_id IS NULL;

-- ---------------------------------------------------------------------------
-- 批次 / 标注任务 / 任务包含的片段
-- ---------------------------------------------------------------------------
CREATE TABLE batch (
    id         BIGSERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE task (
    id         BIGSERIAL PRIMARY KEY,
    batch_id   BIGINT NOT NULL REFERENCES batch (id),
    name       TEXT   NOT NULL,
    status     TEXT   NOT NULL DEFAULT 'open'
               CHECK (status IN ('open', 'in_progress', 'completed', 'closed')),
    created_by BIGINT REFERENCES app_user (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, name)
);

CREATE TABLE task_segment (
    task_id    BIGINT NOT NULL REFERENCES task (id)    ON DELETE CASCADE,
    segment_id BIGINT NOT NULL REFERENCES segment (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, segment_id)
);

-- ---------------------------------------------------------------------------
-- 任务分配（同一用户不能连续获得同一批次 —— 应用层校验，batch_id 冗余便于判定）
-- ---------------------------------------------------------------------------
CREATE TABLE assignment (
    id          BIGSERIAL PRIMARY KEY,
    task_id     BIGINT NOT NULL REFERENCES task (id),
    user_id     BIGINT NOT NULL REFERENCES app_user (id),
    batch_id    BIGINT NOT NULL REFERENCES batch (id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status      TEXT NOT NULL DEFAULT 'assigned'
                CHECK (status IN ('assigned', 'submitted', 'reviewed')),
    UNIQUE (task_id, user_id)
);
CREATE INDEX idx_assignment_user ON assignment (user_id, id DESC);

-- ---------------------------------------------------------------------------
-- 标注版本（每次提交产生一个不可变版本）
-- ---------------------------------------------------------------------------
CREATE TABLE annotation_version (
    id            BIGSERIAL PRIMARY KEY,
    assignment_id BIGINT NOT NULL REFERENCES assignment (id),
    version_no    INT    NOT NULL CHECK (version_no > 0),
    status        TEXT   NOT NULL DEFAULT 'submitted'
                  CHECK (status IN ('submitted', 'superseded', 'arbitrated')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (assignment_id, version_no)
);

-- ---------------------------------------------------------------------------
-- 标注（字符偏移为 Unicode 码点偏移，应用层校验范围）
-- ---------------------------------------------------------------------------
CREATE TABLE annotation (
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT NOT NULL REFERENCES annotation_version (id) ON DELETE CASCADE,
    segment_id   BIGINT NOT NULL REFERENCES segment (id),
    tag_id       BIGINT NOT NULL REFERENCES tag (id),
    start_offset INT    NOT NULL CHECK (start_offset >= 0),
    end_offset   INT    NOT NULL CHECK (end_offset > start_offset),
    note         TEXT
);
CREATE INDEX idx_annotation_segment ON annotation (segment_id, tag_id, start_offset, end_offset);
CREATE INDEX idx_annotation_version ON annotation (version_id);

-- ---------------------------------------------------------------------------
-- 仲裁结论（只能引用已有标注版本；结论为全新标注，不改原始数据）
-- ---------------------------------------------------------------------------
CREATE TABLE arbitration (
    id            BIGSERIAL PRIMARY KEY,
    segment_id    BIGINT NOT NULL REFERENCES segment (id),
    arbitrator_id BIGINT NOT NULL REFERENCES app_user (id),
    comment       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE arbitration_source (
    arbitration_id BIGINT NOT NULL REFERENCES arbitration (id)         ON DELETE CASCADE,
    version_id     BIGINT NOT NULL REFERENCES annotation_version (id),
    PRIMARY KEY (arbitration_id, version_id)
);

CREATE TABLE arbitration_annotation (
    id             BIGSERIAL PRIMARY KEY,
    arbitration_id BIGINT NOT NULL REFERENCES arbitration (id) ON DELETE CASCADE,
    tag_id         BIGINT NOT NULL REFERENCES tag (id),
    start_offset   INT    NOT NULL CHECK (start_offset >= 0),
    end_offset     INT    NOT NULL CHECK (end_offset > start_offset),
    note           TEXT
);
CREATE INDEX idx_arb_ann ON arbitration_annotation (arbitration_id);

-- ---------------------------------------------------------------------------
-- 事件日志 —— TimescaleDB hypertable（按时间分区，体现时序特性）
-- ---------------------------------------------------------------------------
CREATE TABLE annotation_event (
    time       TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_id   UUID        NOT NULL,
    event_type TEXT        NOT NULL,
    segment_id BIGINT,
    version_id BIGINT,
    actor_id   BIGINT,
    payload    JSONB,
    UNIQUE (time, event_id)
);
SELECT create_hypertable('annotation_event', 'time', if_not_exists => TRUE);
CREATE INDEX idx_event_type ON annotation_event (event_type, time DESC);

-- ============================================================================
-- 种子数据
-- ============================================================================

INSERT INTO app_user (username, display_name, role) VALUES
    ('admin', '系统管理员', 'admin'),
    ('alice', 'Alice（标注）', 'annotator'),
    ('bob',   'Bob（标注）',   'annotator'),
    ('carol', 'Carol（标注）', 'annotator'),
    ('dave',  'Dave（仲裁）',  'arbitrator');

INSERT INTO corpus (name) VALUES ('demo-news'), ('demo-reviews');

-- 标签层级：实体 / 情感 两棵树
INSERT INTO tag (id, name, parent_id) VALUES
    (1, '实体', NULL),
    (2, '人名', 1),
    (3, '地名', 1),
    (4, '机构', 1),
    (5, '情感', NULL),
    (6, '正面', 5),
    (7, '负面', 5);
SELECT setval('tag_id_seq', (SELECT MAX(id) FROM tag));

-- 多语言片段（char_length 为码点长度，由导入时计算；此处为演示数据）
INSERT INTO segment (corpus_id, language_code, content, char_length, external_id) VALUES
    (1, 'zh-Hans', '马云在杭州出席了阿里巴巴举办的新闻发布会。', 21, 'zh-0001'),
    (1, 'zh-Hans', '这部电影的剧情非常出色，演员表演也很精彩。', 21, 'zh-0002'),
    (1, 'en',      'Apple announced a new MacBook in Cupertino.', 44, 'en-0001'),
    (1, 'en',      'The service was terrible and the food arrived cold.', 51, 'en-0002'),
    (1, 'ja',      '東京で開催された会議に田中社長が出席した。', 21, 'ja-0001'),
    (2, 'zh-Hans', '物流速度太慢，包装也有破损，非常不满意。', 20, 'rv-0001');

INSERT INTO batch (name) VALUES ('batch-2026-09-a'), ('batch-2026-09-b');

INSERT INTO task (batch_id, name, created_by) VALUES
    (1, 'task-ner-zh', 1),
    (1, 'task-sentiment-mixed', 1),
    (2, 'task-ner-multilang', 1);

INSERT INTO task_segment (task_id, segment_id) VALUES
    (1, 1), (1, 2),
    (2, 2), (2, 4), (2, 6),
    (3, 1), (3, 3), (3, 5);

-- alice 已分配批次1的任务 → 再给 alice 分配批次1的其他任务会被规则4拒绝
INSERT INTO assignment (task_id, user_id, batch_id) VALUES (1, 2, 1);
INSERT INTO assignment (task_id, user_id, batch_id) VALUES (2, 3, 1);

-- alice 在任务1上提交过一个版本，包含与 bob 重复的标注（用于演示合并展示）
INSERT INTO annotation_version (assignment_id, version_no) VALUES (1, 1);
INSERT INTO annotation (version_id, segment_id, tag_id, start_offset, end_offset, note) VALUES
    (1, 1, 2, 0, 2, '人名：马云'),
    (1, 1, 3, 3, 5, '地名：杭州'),
    (1, 1, 4, 8, 12, '机构：阿里巴巴');

-- bob 也分配任务1并提交版本（与 alice 的“马云/人名”重复 → 合并展示 count=2）
INSERT INTO assignment (task_id, user_id, batch_id) VALUES (1, 3, 1);
INSERT INTO annotation_version (assignment_id, version_no) VALUES (3, 1);
INSERT INTO annotation (version_id, segment_id, tag_id, start_offset, end_offset, note) VALUES
    (2, 1, 2, 0, 2, '确认为人名'),
    (2, 1, 3, 3, 5, NULL),
    (2, 1, 4, 8, 12, NULL);
