# Local Collaboration Rules

- WL validation rules must be defined only in `wl-conf`.
- Do not add local hardcoded rule exceptions in this repository without explicit user approval in the current conversation.
- Before each commit, run relevant tests for the changed scope and ensure they pass.

# Test-Driven Development (strict)

Not a preference. No production change lands without having been driven by a
test.

- Write the failing test first. Run it. See it fail, and fail for the reason
  the change exists — a test that has never been red proves nothing.
- Then write the smallest code that makes it pass. Refactor only while green.
- One behaviour per red-green cycle. Never batch several fixes into one.
- A bug fix starts with a test that reproduces the bug at the level the bug
  lives: the failing input, not a paraphrase of it.
- The code satisfies the test, never the reverse. Change an expectation only
  when the expectation itself is provably wrong, and say so in the commit
  message.
- Production change and its test go in the same commit.
- Deleting or weakening a test to get to green is a defect, not progress.
