# MetalUniversal task baseline

Recorded: 2026-08-04 10:42 +08:00

## Source identity

- Requested remote repository: `21Z121Z1/MetalUniversal`
- Fetch and push remote used by this worktree: `origin`
  (`https://github.com/21Z121Z1/MetalUniversal.git`)
- Starting remote branch: `origin/feature/mobilegl-inspired-hotpath`
- Starting full SHA: `e52d84356b7edb7ff4ca2f147fcedb4f7a54b421`
- Starting commit time: `2026-08-04T10:01:18+08:00`
- Starting commit subject: `Merge pull request #19 from
  21Z121Z1/fix/metal4-device-session-lifetime`
- Local task branch: `codex/mobilegl-comprehensive-completion`
- Isolated worktree:
  `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/.codex/worktrees/mobilegl-completion-20260804`
- Required push destination: `origin/feature/mobilegl-inspired-hotpath`

Both configured remotes named `origin` and `fork` resolved the requested branch
to the same starting SHA after `git fetch --all --prune --tags`.

## Ancestry

### `master`

- Merge base: `7afa3aa5ebe021920ee7dd0af740312fb3b3d8a2`
- `origin/master...HEAD`: 0 commits unique to `master`, 329 commits unique
  to the task baseline.
- Therefore the fetched target branch contains `origin/master` as an ancestor
  at task start.

### `integration/iris-metal-next`

- Merge base: `f03935c92048e3ca8037c7a21610c064a9377ca3`
- `origin/integration/iris-metal-next...HEAD`: 0 commits unique to the
  integration branch, 187 commits unique to the task baseline.
- Therefore the fetched target branch contains
  `origin/integration/iris-metal-next` as an ancestor at task start.

## Worktree ownership and initial status

The existing canonical checkout at
`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master`
was on an older local copy of `feature/mobilegl-inspired-hotpath`, behind the
remote by 37 first-parent commits at inspection time, and contained extensive
tracked and untracked user work. It is intentionally untouched.

The task worktree was created directly from the fetched remote SHA and was clean
before this task's `docs/agent/mobilegl-completion/` files were added. Runtime
artifacts remain under ignored build, run, temporary, or `.codex-run` roots.

## Host environment

- Host: MacBook Pro (`MacBookPro18,3`)
- SoC: Apple M1 Pro
- CPU: 10 cores (8 performance, 2 efficiency)
- GPU: 16 cores
- Unified memory: 16 GB
- Metal support: Metal 4
- Built-in display: 3024 x 1964 Retina, online and primary
- macOS: 26.5.1 (`25F80`)
- Xcode: 26.6 (`17F113`)
- Swift: Apple Swift 6.3.3 (`swiftlang-6.3.3.1.3`), arm64 macOS 26 target
- Java: Homebrew OpenJDK 25.0.2
- Git: 2.54.0
- GitHub CLI: 2.92.0

The long-running task uses only the reversible `caffeinate -dimsu` assertion
recorded under the ignored aggregate-workspace `.codex-run/` directory. No
security, password, FileVault, lock-screen, or permanent power setting was
changed.

## Fixed project dependency baseline

From `gradle.properties` at the starting SHA:

- Minecraft: 26.2
- Sodium: `mc26.2-0.9.1-fabric`
- Iris: `1.11.2+26.2-fabric`
- Java language level: 25

Exact Fabric loader/API, production JAR, validation JAR, native dylib, world,
shader-pack, options, display, and power identities will be added to run
manifests before any comparable Minecraft or performance claim.
