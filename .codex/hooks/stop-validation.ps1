[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

function Write-HookJson {
    param(
        [Parameter(Mandatory = $true)]
        $Value
    )

    $json = $Value | ConvertTo-Json -Depth 10 -Compress
    [Console]::Out.WriteLine($json)
}

function Invoke-RepoGit {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(
            & git `
                -c "safe.directory=$script:safeRepoRoot" `
                -c 'core.quotepath=false' `
                -C $script:repoRoot `
                @Arguments 2>&1
        )
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output | ForEach-Object { $_.ToString() })
    }
}

function Add-Failure {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    [void] $script:failures.Add($Message)
}

$stopHookActive = $false

try {
    $eventJson = [Console]::In.ReadToEnd()
    if (-not [string]::IsNullOrWhiteSpace($eventJson)) {
        $eventData = $eventJson | ConvertFrom-Json
        $activeProperty = $eventData.PSObject.Properties['stop_hook_active']
        if ($null -ne $activeProperty) {
            $stopHookActive = [bool] $activeProperty.Value
        }
    }

    $script:repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
    $script:safeRepoRoot = $script:repoRoot.Replace('\', '/')
    $script:failures = [System.Collections.Generic.List[string]]::new()

    $requiredPaths = @(
        @{ Path = 'AGENTS.md'; Type = 'Leaf' },
        @{ Path = '.codex\hooks.json'; Type = 'Leaf' },
        @{ Path = '.codex\hooks\session-start.ps1'; Type = 'Leaf' },
        @{ Path = '.codex\hooks\stop-validation.ps1'; Type = 'Leaf' },
        @{ Path = '.agents\skills\resolve-problem\SKILL.md'; Type = 'Leaf' },
        @{ Path = 'docs\adr'; Type = 'Container' },
        @{ Path = 'docs\prd'; Type = 'Container' }
    )

    foreach ($required in $requiredPaths) {
        $absolutePath = Join-Path $script:repoRoot $required.Path
        if (-not (Test-Path -LiteralPath $absolutePath -PathType $required.Type)) {
            Add-Failure -Message "Required harness path is missing: $($required.Path)"
        }
    }

    $diffCheck = Invoke-RepoGit -Arguments @(
        'diff',
        '--check',
        'HEAD',
        '--',
        'AGENTS.md',
        '.codex',
        '.agents'
    )
    if ($diffCheck.ExitCode -ne 0) {
        $detail = @($diffCheck.Output) -join [Environment]::NewLine
        Add-Failure -Message "Harness-only git diff --check failed.`n$detail"
    }

    $hooksPath = Join-Path $script:repoRoot '.codex\hooks.json'
    if (Test-Path -LiteralPath $hooksPath -PathType Leaf) {
        try {
            $hooksConfig = Get-Content -LiteralPath $hooksPath -Raw | ConvertFrom-Json
            if ($null -eq $hooksConfig.hooks.SessionStart -or $null -eq $hooksConfig.hooks.Stop) {
                Add-Failure -Message '.codex/hooks.json must define SessionStart and Stop hooks.'
            }
        }
        catch {
            Add-Failure -Message "Invalid .codex/hooks.json: $($_.Exception.Message)"
        }
    }

    $hookScripts = @(
        Join-Path $script:repoRoot '.codex\hooks\session-start.ps1'
        Join-Path $script:repoRoot '.codex\hooks\stop-validation.ps1'
    )

    foreach ($hookScript in $hookScripts) {
        if (-not (Test-Path -LiteralPath $hookScript -PathType Leaf)) {
            continue
        }

        $tokens = $null
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            $hookScript,
            [ref] $tokens,
            [ref] $parseErrors
        ) | Out-Null

        if (@($parseErrors).Count -gt 0) {
            $detail = @($parseErrors | ForEach-Object { $_.Message }) -join '; '
            Add-Failure -Message "PowerShell syntax error in ${hookScript}: $detail"
        }
    }

    $skillPath = Join-Path $script:repoRoot '.agents\skills\resolve-problem\SKILL.md'
    if (Test-Path -LiteralPath $skillPath -PathType Leaf) {
        $skillText = Get-Content -LiteralPath $skillPath -Raw
        if ($skillText -notmatch '(?s)\A---\s*\r?\nname:\s*resolve-problem\s*\r?\ndescription:\s*.+?\r?\n---') {
            Add-Failure -Message 'resolve-problem/SKILL.md must contain name and description frontmatter.'
        }
    }

    if ($script:failures.Count -eq 0) {
        Write-HookJson -Value ([ordered]@{ continue = $true })
        exit 0
    }

    $reason = "Local harness validation failed:`n- " + ($script:failures -join "`n- ")
    if ($stopHookActive) {
        Write-HookJson -Value ([ordered]@{
            continue = $true
            systemMessage = $reason
        })
    }
    else {
        Write-HookJson -Value ([ordered]@{
            decision = 'block'
            reason = "$reason`nFix the local harness checks, then try to finish again."
        })
    }

    exit 0
}
catch {
    $failure = 'Local harness validation hook failed unexpectedly. Review the local hook script and Git access.'
    if ($stopHookActive) {
        Write-HookJson -Value ([ordered]@{
            continue = $true
            systemMessage = $failure
        })
    }
    else {
        Write-HookJson -Value ([ordered]@{
            decision = 'block'
            reason = "$failure`nInspect the hook configuration before finishing."
        })
    }
    exit 0
}
