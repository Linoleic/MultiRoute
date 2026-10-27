"""Extract one complete bilingual Changelog section for a release."""

import argparse
from pathlib import Path
import re


def extract_release_notes(changelog: str, version: str) -> str:
    lines = changelog.splitlines()
    sections = []
    for start, line in enumerate(lines):
        heading = re.fullmatch(r"##[ \t]+([^ \t]+)(?:[ \t]+.*)?", line)
        if heading is None or heading[1] not in (version, "v" + version):
            continue
        end = next((i for i in range(start + 1, len(lines)) if re.match(r"^##(?:[ \t]|$)", lines[i])), len(lines))
        sections.append(lines[start:end])
    if len(sections) != 1:
        raise ValueError(f"Expected one Changelog section for {version}, found {len(sections)}")
    section = sections[0]
    if "unreleased" in section[0].casefold() or "待发布" in section[0]:
        raise ValueError(f"Changelog section for {version} is still marked unreleased")
    body = "\n".join(section[1:])
    chinese, separator, english = body.partition("**English**")
    if "**中文**" not in chinese or not separator:
        raise ValueError("Release notes must contain Chinese followed by English")
    if not chinese.split("**中文**", 1)[1].strip() or not english.strip():
        raise ValueError("Both release-note languages must be non-empty")
    return "\n".join(section).rstrip() + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("changelog", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        notes = extract_release_notes(args.changelog.read_text(encoding="utf-8"), args.version)
    except ValueError as error:
        parser.exit(1, f"Release notes error: {error}\n")
    args.output.write_text(notes, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
