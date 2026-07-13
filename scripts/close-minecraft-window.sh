#!/usr/bin/env bash
# Close the running Minecraft dev-client window via KWin scripting.
# The client ignores SIGTERM; a window close performs a clean shutdown
# (fires the real LoggingOut event, saves the world). KDE Plasma 6 only.
set -euo pipefail

script=$(mktemp --suffix=.js)
trap 'rm -f "$script"' EXIT
cat > "$script" <<'EOF'
for (const w of workspace.windowList()) {
    if (w.caption.indexOf("Minecraft") !== -1) {
        w.closeWindow();
    }
}
EOF

id=$(qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.loadScript "$script" marionette-close)
qdbus6 org.kde.KWin "/Scripting/Script${id}" org.kde.kwin.Script.run
qdbus6 org.kde.KWin /Scripting org.kde.kwin.Scripting.unloadScript marionette-close
