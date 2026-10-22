# Judge0 integration and event security gates

## Evidence, checked 2026-10-20

The early spike POSTed a real Python 3.13.2 program and polled its token: input `2 3` produced `5`, status 3. The backend queue later ran both sample problems in C++, Java and Python, plus wrong-answer, compilation/runtime/time/memory/output cases and rejudge. See `docs/real-execution-results.json`. No mock execution provider exists.

The [official API](https://ce.judge0.com/docs) documents asynchronous tokens, result statuses, authentication headers and execution-limit fields. The [live language catalog](https://ce.judge0.com/languages) was checked before pinning IDs/names. The adapter encodes source/input/output with base64 and verifies runtime names. API compatibility is proven; production isolation is not.

The [upstream CE 1.13.1 release](https://github.com/judge0/judge0/releases/tag/v1.13.1) fixes three critical vulnerabilities affecting versions through 1.13.0 and describes its supported Linux/cgroup setup. That does not prove the public service or another image has current host/runtime/dependency patches. The [official SSRF advisory](https://github.com/judge0/judge0/security/advisories/GHSA-q7vg-26pg-v5hr) explains the danger of sandbox network access to execution infrastructure. Recheck [all advisories](https://github.com/judge0/judge0/security/advisories) before deployment.

Judge0 CE is [GPL-3.0](https://github.com/judge0/judge0/blob/master/LICENSE); it is not vendored here. Preserve licensing and applicable distribution obligations for images or modifications. Hosted-provider event quotas, confidentiality and retention require separate confirmation. No service was purchased or deployed.

## Required event topology

Use a dedicated, patched Linux execution VM or a vetted managed service. Application host/platform PostgreSQL and execution host must not share secrets, mounts, DB credentials or a routable platform DB network. Judge0's PostgreSQL/Redis must be separate. A container alone is not proof of isolation.

For self-hosting, follow the current official release procedure on a **separate execution host**, not this Windows app machine. Pin reviewed image digests, update kernel/dependencies, and verify the required cgroup mode. Authenticate and TLS-protect the provider API; allow backend access only. Do not expose provider queues/databases to participants.

The operator must enforce and test:

- Deny sandbox outbound traffic to app/data networks, provider databases, metadata services and the internet. Per-request `enable_network=false` does not replace host firewall rules.
- CPU at most 5 s (sample problems 3 s), wall time 10 s, memory at most 512 MiB, stack 64 MiB, 64 processes/threads, output/file size 64 KiB, one execution. Verify Java startup under these limits.
- No app mounts, Docker socket, application secrets or provider-admin credentials in student sandboxes.
- Source/custom input at most 64 KiB; at most 20 hidden tests. The app caps provider responses at 1 MiB and decoded output at 64 KiB. Also test compilation-output and disk limits.
- Provider quotas/monitoring, sanitized operator logs, and retention/deletion policy for source and test input. Never reveal provider tokens to contestants.

Before setting `JUDGE0_SECURITY_REVIEWED=true`, record the actual build/digest, vulnerability scan, patch date, runtime versions, egress proof, resource-limit tests and capacity. The public catalog includes old runtimes, especially JDK 17.0.6. Obtain maintained patched builds, update pins and rerun references/errors before student use. This remains an event blocker.

## Configuration and behavior

`JUDGE0_URL` must be HTTPS. `JUDGE0_KEY`, `JUDGE0_AUTH_HEADER` and optional `JUDGE0_RAPIDAPI_HOST` are backend-only. The app forbids the public endpoint outside explicit development mode. With no provider it never invents acceptance: worker-off jobs remain queued; enabled workers retry and eventually return SYSTEM_ERROR.

Mappings cover QUEUED, RUNNING, ACCEPTED after local output comparison, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, COMPILATION_ERROR, RUNTIME_ERROR and SYSTEM_ERROR. Memory-at-limit runtime errors become MEMORY_LIMIT_EXCEEDED; the synthetic allocation case confirmed it. Ambiguous allocation failures can remain RUNTIME_ERROR. Excessive output terminates as RUNTIME_ERROR.

The public spike does not prove private-test secrecy, malicious sandbox escape resistance, event quota, 80-way execution or reliability. Perform those checks on the intended topology. The load harness refuses execution bursts against the public service.
