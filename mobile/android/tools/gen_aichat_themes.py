"""Generate the AIChat iOS-style stylesheets from the FlorisBoard day stylesheet.

Usage (from mobile/android/):
    python tools/gen_aichat_themes.py \\
        app/src/main/assets/ime/theme/org.florisboard.themes/stylesheets/floris_day.json \\
        app/src/main/assets/ime/theme/com.aichat.themes/stylesheets

AiChatThemesStylesheetTest checks the output parses strictly.
"""
import copy
import json
import sys

# Key codes treated as "function keys": shift, delete, view switches, language switch, emoji/media,
# numeric views. In the current iOS look they share the letter key color; the separate rules are kept so
# the palette alone can tint them again.
FUNCTION_KEYS = "-11,-7,-201,-202,-203,-204,-205,-212,-211,-227,-213"

PALETTES = {
    "aichat_ios_light": {
        "--background": "#e4e5e9",
        "--background-variant": "#d4d6db",
        "--surface": "#ffffff",
        "--surface-variant": "#e6e7ea",
        "--key-pressed": "#d1d3d9",
        "--function-key": "#ffffff",
        "--function-key-pressed": "#d1d3d9",
        "--key-shadow": "rgba(0,0,0,0.35)",
        "--primary": "#007aff",
        "--primary-variant": "#0062cc",
        "--secondary": "#007aff",
        "--secondary-variant": "#0062cc",
        "--popup-surface": "#ffffff",
        "--focused-popup-surface": "#007aff",
        "--on-focused-popup-surface": "#ffffff",
        "--spacer-color": "rgba(0,0,0,0.18)",
        "--one-hand-background": "#c4c8ce",
        "--one-hand-foreground": "#1c1c1e",
        "--incognito-icon-color": "#00000011",
        "--on-primary": "#ffffff",
        "--on-background": "#000000",
        "--on-background-disabled": "rgb(160,160,165)",
        "--on-surface": "#000000",
        "--on-surface-variant": "#6e6e73",
    },
    "aichat_ios_dark": {
        "--background": "#2c2c2e",
        "--background-variant": "#3a3a3c",
        "--surface": "#5b5b5e",
        "--surface-variant": "#4a4a4d",
        "--key-pressed": "#3a3a3c",
        "--function-key": "#5b5b5e",
        "--function-key-pressed": "#3a3a3c",
        "--key-shadow": "rgba(0,0,0,0.6)",
        "--primary": "#0a84ff",
        "--primary-variant": "#0062cc",
        "--secondary": "#0a84ff",
        "--secondary-variant": "#0062cc",
        "--popup-surface": "#6b6b6e",
        "--focused-popup-surface": "#0a84ff",
        "--on-focused-popup-surface": "#ffffff",
        "--spacer-color": "rgba(255,255,255,0.18)",
        "--one-hand-background": "#3a3a3c",
        "--one-hand-foreground": "#f2f2f7",
        "--incognito-icon-color": "#ffffff11",
        "--on-primary": "#ffffff",
        "--on-background": "#ffffff",
        "--on-background-disabled": "rgb(110,110,115)",
        "--on-surface": "#ffffff",
        "--on-surface-variant": "#c7c7cc",
    },
}

SHAPES = {
    "--drag-marker": "rgb(255,59,48)",
    "--shape": "rounded-corner(9dp, 9dp, 9dp, 9dp)",
    "--shape-variant": "rounded-corner(12dp, 12dp, 12dp, 12dp)",
    "--shape-chip": "rounded-corner(50%, 50%, 50%, 50%)",
}


def ios_rules():
    """Rules that replace or extend the base ones to get the iOS look."""
    return {
        "key": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface)",
            "font-size": "23sp",
            "shape": "var(--shape)",
            # Flat keys, no drop shadow (current iOS look).
            "shadow-elevation": "0dp",
            "text-max-lines": "1",
        },
        "key:pressed": {
            "background": "var(--key-pressed)",
            "foreground": "var(--on-surface)",
        },
        f"key[code={FUNCTION_KEYS}]": {
            "background": "var(--function-key)",
            "foreground": "var(--on-surface)",
            "font-size": "16sp",
        },
        f"key[code={FUNCTION_KEYS}]:pressed": {
            "background": "var(--function-key-pressed)",
            "foreground": "var(--on-surface)",
        },
        # Return is a plain key with an icon, like the other keys.
        "key[code=10]": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface)",
            "font-size": "16sp",
        },
        "key[code=10]:pressed": {
            "background": "var(--key-pressed)",
            "foreground": "var(--on-surface)",
        },
        # Muted language name on the space bar, like iOS ("Tiếng Việt" / "English").
        "key[code=32]": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface-variant)",
            "font-size": "15sp",
            "text-overflow": "ellipsis",
        },
        "key[code=32]:pressed": {
            "background": "var(--key-pressed)",
            "foreground": "var(--on-surface-variant)",
        },
        "key[code=-11][shiftstate=`shifted_manual`]": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface)",
        },
        "key[code=-11][shiftstate=`shifted_automatic`]": {
            "background": "var(--surface)",
            "foreground": "var(--on-surface)",
        },
        "key[code=-11][shiftstate=`caps_lock`]": {
            "background": "var(--surface)",
            "foreground": "var(--primary)",
        },
        "key-hint": {
            "background": "transparent",
            "foreground": "var(--on-surface-variant)",
            "font-size": "10sp",
            "padding": "0dp 2dp 2dp 0dp",
            "text-max-lines": "1",
        },
        "key-popup-box": {
            "background": "var(--popup-surface)",
            "foreground": "var(--on-surface)",
            "font-size": "30sp",
            "shape": "var(--shape-variant)",
            "shadow-elevation": "4dp",
            "shadow-color": "var(--key-shadow)",
        },
        "key-popup-element:focus": {
            "background": "var(--focused-popup-surface)",
            "foreground": "var(--on-focused-popup-surface)",
            "shape": "var(--shape)",
        },
        "smartbar": {"font-size": "17sp"},
        "smartbar-shared-actions-toggle": {
            "background": "transparent",
            "foreground": "var(--on-surface-variant)",
            "margin": "6dp",
            "shape": "circle()",
        },
        "smartbar-candidate-word": {
            "background": "transparent",
            "foreground": "var(--on-background)",
            "font-size": "16sp",
            "margin": "4dp",
            "padding": "8dp 0dp",
            "shape": "var(--shape)",
            "text-align": "center",
            "text-max-lines": "1",
            "text-overflow": "ellipsis",
        },
        "smartbar-candidate-word:pressed": {
            "background": "var(--background-variant)",
            "foreground": "var(--on-background)",
        },
        "smartbar-candidate-spacer": {"foreground": "var(--spacer-color)"},
        "media-emoji-key": {
            "background": "transparent",
            "foreground": "var(--on-background)",
            "font-size": "30sp",
            "shape": "var(--shape)",
        },
        "media-emoji-key:pressed": {
            "background": "var(--background-variant)",
            "foreground": "var(--on-background)",
        },
        "media-emoji-subheader": {
            "foreground": "var(--on-surface-variant)",
            "font-size": "12sp",
            "font-weight": "bold",
            "margin": "8dp 4dp",
        },
        "media-emoji-tab": {"foreground": "var(--on-surface-variant)"},
        "media-emoji-tab:focus": {"foreground": "var(--primary)"},
        "media-bottom-row-button": {
            "background": "transparent",
            "foreground": "var(--on-background)",
            "font-size": "15sp",
            "padding": "14dp 0dp",
            "shape": "var(--shape-chip)",
        },
        "media-bottom-row-button:focus": {
            "background": "var(--background-variant)",
            "foreground": "var(--primary)",
        },
    }


def build(base, palette):
    sheet = copy.deepcopy(base)
    sheet["@defines"] = {**palette, **SHAPES}
    # The base "key[code=-201,-202,-203]" font-size rule is superseded by the function key rule.
    sheet.pop("key[code=-201,-202,-203]", None)
    for selector, props in ios_rules().items():
        sheet[selector] = props
    return sheet


def main():
    base_path, out_dir = sys.argv[1], sys.argv[2]
    with open(base_path, encoding="utf-8") as f:
        base = json.load(f)
    for theme_id, palette in PALETTES.items():
        with open(f"{out_dir}/{theme_id}.json", "w", encoding="utf-8", newline="\n") as f:
            json.dump(build(base, palette), f, indent=2, ensure_ascii=False)
            f.write("\n")


if __name__ == "__main__":
    main()
