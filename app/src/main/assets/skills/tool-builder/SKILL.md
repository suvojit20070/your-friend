---
name: Tool builder
description: Create a new custom tool (Python or HTTP) when a task needs a capability the built-in tools lack.
version: 1.0
author: Suvo
license: Apache-2.0
triggers: [make a tool, create a tool, new tool, automate this, add a capability]
required_tools: [py_meta_tools, list_tools]
---

# Tool builder

1. Call `list_tools` first — maybe a built-in tool already does it.
2. Design the tool: a lowercase name (`a-z0-9_`), a one-sentence description the AI will understand, and a JSON Schema for the parameters (type object, properties, required, additionalProperties false).
3. Python tools: the script reads its arguments as JSON from stdin and prints plain text or `{"status": "ok"|"error", "message": "...", "data": {...}}`. Keep it short and use the standard library.
   HTTP tools: give the URL with `{{param}}` placeholders, method, headers and an optional body template. Secrets go in the secret field, never in the URL or code.
4. Call `py_meta_tools` with `action=create`. The user must approve the creation.
5. Call `py_meta_tools` with `action=test` and realistic arguments. Fix and `update` until it works.
6. Tell the user the tool's name, what it does and its risk level. They can review, edit or disable it in Tools & skills > Custom.
