#!/usr/bin/env bash
set -euo pipefail

plugin_id="dev.effect.jetbrains"
install_dir_name="effect-jetbrains-plugin"
product_dir="${EFFECT_JETBRAINS_PRODUCT_DIR:-}"
zip_path=""

# Default the product config directory to whatever WebStorm build JetBrains Toolbox currently
# installs (its product-info.json names the config/plugin directory, e.g. WebStorm2026.3), so an
# IDE line bump does not silently install into a stale directory. Fall back to the last stable line.
detect_product_dir() {
  local data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
  local product_info="${data_home}/JetBrains/Toolbox/apps/webstorm/product-info.json"
  if [[ -f "$product_info" ]] && command -v node >/dev/null 2>&1; then
    node -e '
      const info = JSON.parse(require("node:fs").readFileSync(process.argv[1], "utf8"))
      if (typeof info.dataDirectoryName === "string" && /^WebStorm[0-9.]+$/.test(info.dataDirectoryName)) {
        process.stdout.write(info.dataDirectoryName)
      }
    ' "$product_info" 2>/dev/null || true
  fi
}

usage() {
  cat <<USAGE
Usage: scripts/install-local-webstorm-plugin.sh [--product WebStorm2026.3] [--zip build/distributions/effect-jetbrains-plugin-0.1.6.zip]

Installs the locally built Effect TSGO plugin into a JetBrains WebStorm config.
Close WebStorm before running this script.

Environment:
  EFFECT_JETBRAINS_PRODUCT_DIR  JetBrains product config directory, default: the Toolbox WebStorm
                                install's dataDirectoryName (e.g. WebStorm2026.3), else WebStorm2026.2
  XDG_DATA_HOME                 JetBrains plugin install root base, default: ~/.local/share
  XDG_CACHE_HOME                JetBrains cache root base, default: ~/.cache
  XDG_CONFIG_HOME               JetBrains config root base, default: ~/.config
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --product)
      product_dir="${2:?--product requires a JetBrains product directory name}"
      shift 2
      ;;
    --zip)
      zip_path="${2:?--zip requires a plugin ZIP path}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$zip_path" ]]; then
        zip_path="$1"
        shift
      else
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 64
      fi
      ;;
  esac
done

if [[ -z "$product_dir" ]]; then
  product_dir="$(detect_product_dir)"
  product_dir="${product_dir:-WebStorm2026.2}"
fi

if [[ -z "$zip_path" ]]; then
  zip_path="$(ls -t build/distributions/effect-jetbrains-plugin-*.zip 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$zip_path" || ! -f "$zip_path" ]]; then
  echo "No plugin ZIP found. Run ./gradlew buildPlugin first or pass --zip <path>." >&2
  exit 66
fi

if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required to install the plugin ZIP." >&2
  exit 69
fi

if ! command -v node >/dev/null 2>&1; then
  echo "node is required to safely update JetBrains Settings Sync JSON metadata." >&2
  exit 69
fi

running_pids="$(
  {
    pgrep -f "/webstorm/bin/webstorm" || true
    pgrep -f "/JetBrains/${product_dir}/" || true
  } | sort -u | tr '\n' ' '
)"

if [[ -n "${running_pids// }" ]]; then
  echo "WebStorm appears to be running for ${product_dir} (PIDs: ${running_pids})." >&2
  echo "Close WebStorm first, then rerun this script so the plugin directory and Settings Sync metadata are not edited live." >&2
  exit 75
fi

data_home="${XDG_DATA_HOME:-$HOME/.local/share}"
cache_home="${XDG_CACHE_HOME:-$HOME/.cache}"
config_home="${XDG_CONFIG_HOME:-$HOME/.config}"

share_dir="${data_home}/JetBrains/${product_dir}"
cache_plugins_dir="${cache_home}/JetBrains/${product_dir}/plugins"
config_dir="${config_home}/JetBrains/${product_dir}"
install_dir="${share_dir}/${install_dir_name}"
settings_sync_plugins="${config_dir}/settingsSync/.metainfo/plugins.json"
disabled_plugins="${config_dir}/disabled_plugins.txt"
zip_path="$(realpath "$zip_path")"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

unzip -q "$zip_path" -d "$tmp_dir"
extracted_root="$(find "$tmp_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1 || true)"
if [[ -z "$extracted_root" || ! -d "$extracted_root/lib" ]]; then
  echo "The plugin ZIP did not unpack to the expected JetBrains plugin layout." >&2
  exit 65
fi

mkdir -p "$share_dir"
rm -rf "$install_dir"
mv "$extracted_root" "$install_dir"

if [[ -d "$cache_plugins_dir" ]]; then
  rm -f "${cache_plugins_dir}/${install_dir_name}"*.zip
fi

if [[ -f "$disabled_plugins" ]] && grep -Fxq "$plugin_id" "$disabled_plugins"; then
  backup="${disabled_plugins}.bak-$(date -u +%Y%m%dT%H%M%SZ)"
  cp "$disabled_plugins" "$backup"
  grep -Fxv "$plugin_id" "$backup" > "$disabled_plugins"
  echo "Removed ${plugin_id} from disabled_plugins.txt (backup: ${backup})."
fi

if [[ -f "$settings_sync_plugins" ]]; then
  node - "$settings_sync_plugins" "$plugin_id" <<'NODE'
const fs = require("node:fs")

const [path, pluginId] = process.argv.slice(2)
const text = fs.readFileSync(path, "utf8")
const data = JSON.parse(text)
const plugins = data.plugins

if (!plugins || typeof plugins !== "object") {
  process.exit(0)
}

const plugin = plugins[pluginId]
if (!plugin || typeof plugin !== "object" || plugin.enabled !== false) {
  process.exit(0)
}

const stamp = new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z")
const backup = `${path}.bak-${stamp}`
fs.copyFileSync(path, backup)
delete plugin.enabled
fs.writeFileSync(path, `${JSON.stringify(data, null, 4)}\n`)
console.log(`Enabled ${pluginId} in Settings Sync metadata (backup: ${backup}).`)
NODE
fi

echo "Installed $(basename "$zip_path") into ${install_dir} (product config: ${product_dir})."
echo "Start WebStorm and confirm Settings | Plugins | Installed shows Effect TSGO enabled."
