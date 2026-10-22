CREATE TABLE app_user (
 id BIGSERIAL PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE, name VARCHAR(80) NOT NULL,
 password_hash TEXT NOT NULL, role VARCHAR(20) NOT NULL CHECK(role IN ('ADMIN','PARTICIPANT')),
 active BOOLEAN NOT NULL DEFAULT TRUE, last_dispatch TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01', created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE auth_session(id UUID PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES app_user(id),expires_at TIMESTAMPTZ NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE refresh_token(hash VARCHAR(64) PRIMARY KEY,session_id UUID NOT NULL REFERENCES auth_session(id),used BOOLEAN NOT NULL DEFAULT FALSE);
CREATE INDEX idx_session_user ON auth_session(user_id);
CREATE TABLE contest(id INT PRIMARY KEY CHECK(id=1),title VARCHAR(120) NOT NULL,start_at TIMESTAMPTZ NOT NULL,end_at TIMESTAMPTZ NOT NULL,CHECK(start_at<end_at));
INSERT INTO contest VALUES(1,'DevFest Programming Contest',now()+interval '1 day',now()+interval '1 day 3 hours');
CREATE TABLE problem(id BIGSERIAL PRIMARY KEY,contest_id INT NOT NULL DEFAULT 1 REFERENCES contest(id),current_version INT NOT NULL DEFAULT 1);
CREATE TABLE problem_version(problem_id BIGINT NOT NULL REFERENCES problem(id),version INT NOT NULL CHECK(version>0),document TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),PRIMARY KEY(problem_id,version));
CREATE TABLE draft(user_id BIGINT NOT NULL REFERENCES app_user(id),contest_id INT NOT NULL DEFAULT 1 REFERENCES contest(id),problem_id BIGINT NOT NULL REFERENCES problem(id),language VARCHAR(10) NOT NULL CHECK(language IN ('CPP','JAVA','PYTHON')),source TEXT NOT NULL CHECK(octet_length(source)<=65536),version BIGINT NOT NULL DEFAULT 1,updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),PRIMARY KEY(user_id,contest_id,problem_id,language));
CREATE TABLE submission(id UUID PRIMARY KEY,user_id BIGINT NOT NULL REFERENCES app_user(id),contest_id INT NOT NULL DEFAULT 1 REFERENCES contest(id),problem_id BIGINT NOT NULL,problem_version INT NOT NULL,kind VARCHAR(10) NOT NULL CHECK(kind IN ('RUN','SUBMIT')),language VARCHAR(10) NOT NULL, runtime VARCHAR(80) NOT NULL,language_id INT NOT NULL,source TEXT NOT NULL CHECK(octet_length(source)<=65536),stdin TEXT NOT NULL CHECK(octet_length(stdin)<=65536),received_at TIMESTAMPTZ NOT NULL,idempotency_key UUID NOT NULL,request_hash VARCHAR(64) NOT NULL,current_generation INT NOT NULL DEFAULT 1,UNIQUE(user_id,kind,idempotency_key),FOREIGN KEY(problem_id,problem_version) REFERENCES problem_version(problem_id,version));
CREATE TABLE evaluation(id UUID PRIMARY KEY,submission_id UUID NOT NULL REFERENCES submission(id),generation INT NOT NULL,state VARCHAR(32) NOT NULL CHECK(state IN ('QUEUED','RUNNING','ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','COMPILATION_ERROR','RUNTIME_ERROR','SYSTEM_ERROR')),attempts INT NOT NULL DEFAULT 0,lease_owner UUID,lease_until TIMESTAMPTZ,available_at TIMESTAMPTZ NOT NULL DEFAULT now(),started_at TIMESTAMPTZ,finished_at TIMESTAMPTZ,stdout TEXT NOT NULL DEFAULT '',stderr TEXT NOT NULL DEFAULT '',compile_output TEXT NOT NULL DEFAULT '',passed INT NOT NULL DEFAULT 0,total INT NOT NULL DEFAULT 0,UNIQUE(submission_id,generation));
CREATE TABLE execution_task(evaluation_id UUID NOT NULL REFERENCES evaluation(id),test_index INT NOT NULL,provider_token VARCHAR(80) NOT NULL,PRIMARY KEY(evaluation_id,test_index));
CREATE INDEX queue_ready ON evaluation(state,available_at,lease_until);
CREATE INDEX submission_user_time ON submission(user_id,received_at DESC);
CREATE TABLE audit_event(id BIGSERIAL PRIMARY KEY,actor_id BIGINT REFERENCES app_user(id),action VARCHAR(80) NOT NULL,target VARCHAR(80) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE rate_bucket(key VARCHAR(160) PRIMARY KEY,window_at TIMESTAMPTZ NOT NULL,count INT NOT NULL);

