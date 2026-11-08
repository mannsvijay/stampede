---
name: check-invariants
description: Run the correctness invariant checks and the concurrency tests, and report results
disable-model-invocation: true
---
1. Make sure the stack is up (`docker compose ps`); start it if not.
2. Run the invariant checker tests and every test tagged "concurrency". Show the output.
3. Call the invariant status endpoint if it exists yet and print each of the five invariants
   from PRD section 6.10 with PASS or FAIL.
4. For any failure: show the exact seat/order IDs involved and your best hypothesis for the cause.
   Do not change code. Wait for me.
