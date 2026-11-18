set -e
DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
export DSH_HOME
PROF="$DSH_HOME/profiles/acp-vscode"
if [ ! -d "$PROF" ]; then
  echo "[install] creating profile from acp template…"
  __COMMAND__ --profile acp-vscode --from-default-profile acp --dump-config >/dev/null
fi
echo "[install] ensuring dsh-acp-app bundle…"
node -e '
const fs = require("fs")
const p = process.argv[1] + "/package.json"
const d = JSON.parse(fs.readFileSync(p, "utf8"))
const b = d.dsh.profile.bundles
if (!b.includes("@deepseek-ai/dsh-acp-app")) { b.push("@deepseek-ai/dsh-acp-app"); fs.writeFileSync(p, JSON.stringify(d, null, 2) + "\n"); console.log("[install]   added") }
else console.log("[install]   already present")
' "$PROF"

# Always forward the registry-selected exact spec: Repair must also upgrade an
# older bridge. The positional argument is data, never shell syntax.
PKG_SPEC="$1"
printf '%s\n' "[install] installing/repairing bridge package ${PKG_SPEC}…"
__COMMAND__ plugin --profile acp-vscode add "${PKG_SPEC}"

# Only stable activation rows belong to the installer. Models, presets and
# support services are imported preview-first from a user-selected profile.
PYML="$PROF/cordis.patch.yml"
[ -f "$PYML" ] || printf '[]\n' > "$PYML"
has_row() {
  grep -Eq "^[[:space:]]*name:[[:space:]]*['\"]?$1['\"]?[[:space:]]*$" "$PYML"
}
ROWS=''
if ! has_row '@deepseek-ai/dsh-workspace'; then
  ROWS="    - id: workspace
      name: '@deepseek-ai/dsh-workspace'
"
fi
if ! has_row 'dsh-vscode-bridge'; then
  ROWS="${ROWS}    - id: dsh-vscode-bridge
      name: 'dsh-vscode-bridge'
"
fi
if [ -n "$ROWS" ]; then
  echo "[install] adding only the missing workspace/bridge activation rows…"
  PATCH_TMP="$(mktemp "$PROF/.cordis.patch.yml.bridge-install.XXXXXX")"
  trap 'rm -f -- "$PATCH_TMP"' EXIT
  stripped="$(grep -vE '^[[:space:]]*(#|$)' "$PYML" | tr -d '[:space:]')"
  {
    if [ -n "$stripped" ] && [ "$stripped" != "[]" ]; then
      cat "$PYML"
      printf '\n'
    fi
    printf '%s\n' '- insert:'
    printf '%s' "$ROWS"
  } > "$PATCH_TMP"
  chmod 600 "$PATCH_TMP"
  mv "$PATCH_TMP" "$PYML"
  trap - EXIT
else
  echo "[install] workspace/bridge activation rows already present"
fi
echo "[install] DONE"
