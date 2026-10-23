---
name: File organizer
description: Sort, rename, summarize or clean up files in the workspace or a folder the user shared.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [organize, clean up, sort files, rename files, folder, duplicates]
required_tools: [file_list, file_info, file_manage, file_read]
---

# File organizer

1. Call `file_list` on the target folder (workspace paths start with `/`, shared folders with `ext:Name/`). Ask which folder if it is unclear.
2. Propose a plan first: which files move where and what the new names will be. Show it as a short list and wait for the user to agree.
3. Apply the plan with `file_manage` (mkdir, move, rename, copy). Work in small batches and report progress.
4. Deleting: workspace deletions go to the trash and can be restored; deletions in shared folders are permanent — always ask before deleting there.
5. Finish with a summary of what changed and anything you skipped.

Notes:
- Use `file_info` to check sizes and dates, `file_read` to peek into text files when names are unclear.
- Never delete or overwrite files the user did not mention.
