# Security and privacy model

## Principles

1. **The model proposes, the app decides.** Tool calls are validated and authorized in
   `ToolExecutor`, outside the model. Nothing in a prompt, web page, file or skill can change
   approval rules or grant a capability.
2. **Never fabricate.** A tool result is always the real output of the real call. When a call
   fails, is denied or is cancelled, the model is told exactly that.
3. **Secrets never leave the Keystore path.** API keys, SSH passwords and HTTP-tool secrets
   live only in `SecretStore` (Android Keystore AES-GCM). They are not stored in memory, logs,
   audit entries, exports or prompts.
4. **Android consent is never bypassed.** Screen capture, APK installs, accessibility, overlay,
   camera and mic all go through Android's own screens.
5. **Outside content is untrusted.** Tool output, web pages, files and skills are put into the
   prompt as data, inside clearly marked blocks, with an instruction not to follow commands
   found there.

## Tool call lifecycle

```
REQUESTED ─► VALIDATED ─► AUTHORIZED ─► EXECUTING ─► SUCCEEDED
     │            │             │             └────► FAILED / CANCELLED
     └─ invalid ──┴─ no capability / denied ─────────► DENIED
```

- **Validation:** JSON Schema subset (types, required, enum, min/max, lengths, patterns,
  nested objects and arrays). Unknown tools and malformed JSON are rejected and the error is
  returned to the model.
- **Capability check:** each tool declares the capabilities it needs (accessibility, Shizuku,
  Termux, network, camera…). A missing capability is reported as "not available", and the tool
  is not run.
- **Approval:** the risk is computed per call (`riskFor(args)`), so a permanent delete in an
  external folder or a destructive shell command is rated higher than a plain read. Typing into
  password fields is refused outright.
- **Audit:** every transition is written to the audit log with the tool name, a redacted
  summary of the arguments, the risk, the decision and the duration.

## Approval modes

| Mode | Read-only | Low | Medium | High |
|---|---|---|---|---|
| Assist | auto | blocked | blocked | blocked |
| Confirm (default) | auto | auto | ask | ask |
| Autonomous | auto | auto | auto | ask |

High risk always asks. Per-tool overrides: *always ask*, *blocked* or *disabled* (hidden from
the model). Turning off *auto tool execution* makes every non-read-only call ask. An approval
that gets no answer times out as **denied**, and so does a cancelled task.

## Memory safety

- `SecretFilter.rejectReason()` blocks API-key patterns (`sk-…`, `sk-ant-…`, `AIza…`, GitHub,
  Slack and Telegram tokens, JWTs), PEM private keys, card-like digit runs, and phrases like
  `password: …` / `api key is …` (also in Hindi, Bengali and Japanese).
- `SecretFilter.redact()` is applied to logs and audit summaries.
- Every memory has a source and an importance score, and can be viewed, edited, pinned or
  deleted in the Memory screen.

## Network safety

- Web tools resolve the host and refuse loopback, private (RFC 1918), link-local, CGNAT,
  unique-local IPv6 and multicast addresses, so pages cannot be used to reach your LAN. You can
  allow LAN access in Settings (for a self-hosted SearXNG, for example).
- Downloads are size-capped and stored in the private workspace.
- The network security config allows plain `http://`, because LAN servers (Ollama, LM Studio,
  omnivoice-server) usually have no TLS. Use `https://` for anything on the internet.

## Files

- The agent's default area is a private workspace inside the app's storage.
- Folders outside it are reachable only through SAF (Storage Access Framework) folders that you
  pick yourself, shown as `ext:Name/...`.
- Deletes go to a trash folder first.
- `WorkspaceProvider` shares single files with other apps only through `content://` URIs with
  temporary read grants.

## SSH

Host keys are pinned on first connection (trust on first use). If a key changes later, the
connection is refused until you remove the pinned key in Terminal → SSH hosts.

## Reporting

Found a security issue? Open a private security advisory on the GitHub repository instead of a
public issue.
