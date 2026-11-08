---
name: session-end
description: Wrap up a work session - verify, update the plan, log, and commit
disable-model-invocation: true
---
1. Run the backend tests (and frontend checks if frontend changed). Show the output.
2. If anything fails, stop and report. Do not commit.
3. Update docs/PLAN.md: tick finished tasks, add any new follow-up tasks you discovered.
4. Append a short entry to docs/SESSION_LOG.md: date, task ID, what changed, what's next, open problems.
5. Show me `git diff --stat` and propose a commit message in conventional-commit style.
   Commit only after I say yes.
