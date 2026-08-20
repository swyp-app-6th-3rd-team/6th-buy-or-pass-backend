-- 인증 테이블 — OAuth2 소셜 로그인 + JWT
--
-- 설계 근거는 ADR-0006 참조.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,

    -- 소셜 식별자.
    -- 식별자 하나만으로 조회하면 프로바이더가 달라도 subject 가 같을 때 계정이 섞인다.
    -- (provider, provider_id) 복합 유니크로 막는다.
    provider      VARCHAR(20)  NOT NULL COMMENT 'GOOGLE | KAKAO | NAVER',
    provider_id   VARCHAR(255) NOT NULL COMMENT '프로바이더가 발급한 subject',

    email         VARCHAR(255) DEFAULT NULL,
    name          VARCHAR(100) DEFAULT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    state         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | INACTIVE',

    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_provider (provider, provider_id),
    KEY idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE user_refresh_token (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,

    -- 원문이 아니라 SHA-256 해시를 저장한다.
    -- DB 가 유출되어도 토큰을 그대로 쓰지 못하게 한다.
    token_hash  CHAR(64)     NOT NULL COMMENT 'SHA-256 hex',

    expires_at  DATETIME     NOT NULL,
    created_at  DATETIME     NOT NULL,

    PRIMARY KEY (id),

    -- 사용자당 한 행. 재발급 시 INSERT 가 아니라 UPDATE 한다.
    -- 로그인마다 INSERT 하면 행이 쌓여 어느 것이 유효한지 알 수 없게 된다.
    UNIQUE KEY uk_refresh_user (user_id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),

    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
