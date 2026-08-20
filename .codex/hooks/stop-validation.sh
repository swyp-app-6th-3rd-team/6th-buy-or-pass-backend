#!/bin/sh

# Stop must emit exactly one JSON object on stdout. Checks are intentionally
# bounded to the local harness and never mutate Git state.

event_json=$(cat 2>/dev/null || true)
stop_hook_active=false
if printf '%s' "$event_json" | grep -Eq '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
    stop_hook_active=true
fi

script_dir=$(CDPATH= cd -P "$(dirname "$0")" 2>/dev/null && pwd)
repo_root=$(CDPATH= cd -P "$script_dir/../.." 2>/dev/null && pwd)

failure_count=0
failures=

add_failure() {
    failure_count=$((failure_count + 1))
    if [ -z "$failures" ]; then
        failures=$1
    else
        failures="$failures; $1"
    fi
}

for required_file in \
    AGENTS.md \
    .gitattributes \
    .codex/hooks.json \
    .codex/hooks/session-start.ps1 \
    .codex/hooks/stop-validation.ps1 \
    .codex/hooks/session-start.sh \
    .codex/hooks/stop-validation.sh \
    .agents/skills/resolve-problem/SKILL.md
do
    if [ ! -f "$repo_root/$required_file" ]; then
        add_failure "missing $required_file"
    fi
done

for required_dir in docs/adr docs/prd
do
    if [ ! -d "$repo_root/$required_dir" ]; then
        add_failure "missing $required_dir"
    fi
done

if ! git -c "safe.directory=$repo_root" -C "$repo_root" diff --check HEAD -- .gitattributes AGENTS.md .codex .agents >/dev/null 2>&1; then
    add_failure 'harness git diff check failed'
fi

attributes_file="$repo_root/.gitattributes"
if [ -f "$attributes_file" ] && ! grep -Eq '^\.codex/hooks/\*\.sh[[:space:]]+text[[:space:]]+eol=lf[[:space:]]*$' "$attributes_file"; then
    add_failure 'gitattributes must keep Codex shell hooks on LF line endings'
fi

hooks_file="$repo_root/.codex/hooks.json"
if [ -f "$hooks_file" ]; then
    if ! grep -q '"SessionStart"' "$hooks_file" || ! grep -q '"Stop"' "$hooks_file"; then
        add_failure 'hooks config is missing SessionStart or Stop'
    fi
    if ! grep -q 'session-start.sh' "$hooks_file" || ! grep -q 'stop-validation.sh' "$hooks_file"; then
        add_failure 'default hooks must use POSIX shell scripts'
    fi
    if ! grep -q 'session-start.ps1' "$hooks_file" || ! grep -q 'stop-validation.ps1' "$hooks_file"; then
        add_failure 'Windows hooks must use PowerShell scripts'
    fi
fi

if ! sh -n "$repo_root/.codex/hooks/session-start.sh" 2>/dev/null; then
    add_failure 'session-start.sh syntax check failed'
fi
if ! sh -n "$repo_root/.codex/hooks/stop-validation.sh" 2>/dev/null; then
    add_failure 'stop-validation.sh syntax check failed'
fi

skill_file="$repo_root/.agents/skills/resolve-problem/SKILL.md"
if [ -f "$skill_file" ]; then
    if ! grep -Eq '^name:[[:space:]]*resolve-problem[[:space:]]*$' "$skill_file" ||
       ! grep -Eq '^description:[[:space:]]*[^[:space:]].*$' "$skill_file"; then
        add_failure 'resolve-problem skill frontmatter is invalid'
    fi
fi

if [ "$failure_count" -eq 0 ]; then
    printf '%s\n' '{"continue":true}'
    exit 0
fi

reason="Local harness validation failed ($failure_count check(s)): $failures"
if [ "$stop_hook_active" = true ]; then
    printf '{"continue":true,"systemMessage":"%s"}\n' "$reason"
else
    printf '{"decision":"block","reason":"%s. Fix the local harness checks, then try to finish again."}\n' "$reason"
fi

exit 0
