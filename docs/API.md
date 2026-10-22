# API quick reference

Base `/api/v1`. JSON except CSV. Bearer token except login/refresh. Pages are zero-based, 50 rows; scoreboard contains the full single-contest roster. Unknown request properties are rejected. Common errors: 400 validation, 401 session, 403 role, 404 missing/private resource, 409 conflict/closed contest, 429 rate/outstanding quota and sanitized 500.

| Method/path | Request | Response |
|---|---|---|
| POST /auth/login | email,password | 200 accessToken,refreshToken,user |
| POST /auth/refresh | refreshToken | 200 rotated tokens,user |
| POST /auth/logout | {} | 200 ok; current session revoked |
| GET /auth/me | — | id,email,name,role |
| POST /auth/password | currentPassword,newPassword | ok; all sessions revoked |
| GET /contest | — | title,startAt,endAt,serverNow |
| GET /languages | — | code,runtime list |
| GET /problems?page=0 | — | public problems, no hidden tests |
| GET /problems/{id} | — | public details |
| GET /drafts/{problem}/{language} | — | source,version,updatedAt |
| PUT /drafts/{problem}/{language} | source,version | saved draft; 409 on mismatch |
| POST /runs | problemId,language,source,stdin; Idempotency-Key UUID | 202 id |
| POST /submissions | same shape, stdin normally empty; Idempotency-Key UUID | 202 id |
| GET /submissions?page=0&state= | — | own list, all for ADMIN |
| GET /submissions/{id} | — | owner/admin source and verdict; only Run has outputs |
| GET /submissions/{id}/history | — | generation,state,startedAt,finishedAt,attempts |
| GET /scoreboard | — | userId,name,solved,lastAccepted,rank |
| GET /admin/participants?page=0 | — | id,email,name,active |
| POST /admin/participants | email,name,password | 200 id; always PARTICIPANT |
| PATCH /admin/participants/{id} | active | ok |
| POST /admin/participants/{id}/password | password | ok; participant sessions revoked |
| PUT /admin/contest | title,startAt,endAt with timezone | ok |
| GET /admin/problems/{id} | — | full problem including hidden tests |
| POST /admin/problems | ProblemData | id |
| PUT /admin/problems/{id} | ProblemData | id; creates new version |
| GET /admin/overview | — | participant/evaluation counts, oldest queue age |
| POST /admin/submissions/{id}/rejudge | {} | ok; new generation |
| GET /admin/results.csv | — | rank,name,solved,lastAccepted CSV |

ProblemData: title,statement,inputFormat,outputFormat,constraints,examples:[{input,output}],tests:[{input,output}],languages:[CPP,JAVA,PYTHON],cpuSeconds,memoryKb. Seed JSON provides complete valid requests. No public registration, provider callback or arbitrary compiler-options endpoint exists.
