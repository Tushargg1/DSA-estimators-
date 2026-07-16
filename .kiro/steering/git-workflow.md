---
inclusion: always
---

# Git workflow

After completing and validating any workspace file changes requested by the user, automatically create a Git commit containing the relevant changed files.

- Use a concise commit message that describes the completed work.
- Stage only files relevant to the completed request; never use `git add .`.
- Do not commit secrets, generated build output, or unrelated user changes.
- Do not push unless the user explicitly requests a push.