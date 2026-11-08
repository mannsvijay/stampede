---
name: explain-back
description: Quiz me on what we just built so I can explain it in an interview
disable-model-invocation: true
---
Do not change any code in this skill.

Look at the work for: $ARGUMENTS (a task ID, milestone, or "last commit" if empty; use git log and
git diff to see it).

1. Ask me 5 questions, one at a time, that an interviewer would ask about this code. Mix:
   why this design over an alternative, what happens under concurrency or failure, how a specific
   line works, and one "what breaks if we remove X" question.
2. Wait for my answer each time. Grade it honestly: correct / partly / wrong, then give the full
   answer in plain language with a pointer to the exact file and lines.
3. At the end, list the concepts I was weakest on and suggest one small change I could make by hand
   to practise each.
