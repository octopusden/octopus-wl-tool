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

**Unscanned** — a file whose content was not looked at, although the configuration did not exclude it. Distinct from *excluded*, because it is the tool's decision rather than the configuration's.

**Partially scanned** — a file some but not all of whose checks ran. A file too large for a check that needs it whole is still validated by the checks that read it as a stream.

Both are reported, and reported apart from each other. A clean result over content that was not read means "found nothing", not "there is nothing", and only the report can carry that difference. A file's format is never grounds for skipping it: a compiled executable carries its string constants in the clear, and so do the metadata of compressed and media containers - an image's text chunks, an archive's stored file names.
