# Context

Glossary of the terms this project uses. Vocabulary only: no implementation details, no decisions.

## Validation

**Source root** — the directory tree submitted for validation. Everything below is about the files found in it.

**Restricted item** — a text a released artifact must not contain. Its presence is the defect this tool exists to find.

**Exception item** — a text that contains a restricted item but is permitted anyway. Exception items are matched before restricted ones, so a permitted text never becomes a finding.

**Validation problem** — one occurrence of a restricted item in a file, located precisely enough to be looked at: by line and position in text, or by byte offset in binary content.

**Suggested replacement** — the text a restricted item is to be replaced with. Part of a finding, so a report says what to do and not only what is wrong.

## What is looked at

**Excluded** — a file the configuration decided against: by directory, by name, or by matching content. Exclusion is a statement about intent, not about the file's nature. An excluded file is never read.

**Binary** — content that is not text: it holds bytes no text encoding accounts for. Binary content **is** validated. A compiled executable is binary, yet carries its string constants in the clear, and a restricted item can sit in one; such content is read as its sequences of printable characters rather than as lines.

**Opaque** — content in which a restricted item cannot appear at all, because the format encodes text away: compressed streams, media codecs. Opaque content is not validated, since looking would find nothing by construction. Opaque is narrower than binary: every opaque file is binary, most binary files are not opaque.

**Unscanned** — a file whose content was not looked at, although the configuration did not exclude it: it is opaque, or too large to read safely. Distinct from *excluded*, because it is the tool's decision rather than the configuration's, and it is the case a reader of a clean result needs to know about — a clean result over an unscanned file means "found nothing", not "there is nothing".
