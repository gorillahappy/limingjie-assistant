# Login resolution regression fixtures

`win-next-20260924.jpg`: live test.3 2560x1440 WIN screen at route node 26/36.
The original next-button score was below .80. Tests cover bounded alignment
search at native size and scaled 720p/1080p; no account identifiers are visible.

Additional live test.2 evidence: `joined-modal-20260924.jpg` is the 2560x1440
character-joined reward popup captured from the user's MuMu run. No account ID
or credentials are visible. It reproduces the popup/background-map score
ambiguity and is tested at its native size plus scaled 720p and 1080p copies.

Source: user-supplied `limingjie-debug-20260923-125801.zip`, assistant 1.0.12.

- `connecting.png`: original 1920x1080 `frame/latest.jpg` at 12:56:31.637.
- `connecting-archive.png`: independent 960x540 archived frame `000052-f52.jpg`.
- `idle.png`: 960x540 archived frame `000036-f36.jpg`, title before login.

The bottom-right 18% x 10% area is masked to remove the account ID and client
version text. No account welcome banner, credentials, or original debug logs
are included. Images retain the game publisher's rights; they are regression
evidence, not newly licensed game assets.

Tests using scaled copies establish image-scale behavior only. Synthetic
edge-aligned layouts establish mapper/search behavior, not actual device or
game layout compatibility. Fresh screenshots from each aspect ratio are still
needed for device acceptance testing.
