import unittest

from release_notes import extract_release_notes


def section(version="1.5.0", suffix=" - 2026-10-27"):
    return f"## v{version}{suffix}\n\n**中文**\n\n### 新增\n\n- 内容\n\n**English**\n\n### Added\n\n- Content\n"


class ReleaseNotesTest(unittest.TestCase):
    def test_preserves_both_languages_and_nested_categories(self):
        wanted = section()
        self.assertEqual(extract_release_notes(wanted + "\n" + section("1.4.1"), "1.5.0"), wanted)

    def test_normalizes_crlf(self):
        self.assertEqual(extract_release_notes(section().replace("\n", "\r\n"), "1.5.0"), section())

    def test_does_not_match_similar_versions_or_mentions(self):
        source = "## Other v1.5.0\nMention v1.5.0\n" + section("1x5x0") + section("1.5.01") + section()
        self.assertEqual(extract_release_notes(source, "1.5.0"), section())

    def test_supports_prerelease_versions(self):
        self.assertEqual(extract_release_notes(section("1.5.0-rc.1"), "1.5.0-rc.1"), section("1.5.0-rc.1"))

    def test_accepts_heading_without_v_prefix(self):
        wanted = section().replace("## v1.5.0", "## 1.5.0")
        self.assertEqual(extract_release_notes(wanted, "1.5.0"), wanted)

    def test_rejects_missing_section(self):
        with self.assertRaises(ValueError):
            extract_release_notes(section("1.4.1"), "1.5.0")

    def test_rejects_duplicate_sections(self):
        with self.assertRaises(ValueError):
            extract_release_notes(section() + section(), "1.5.0")

    def test_rejects_unreleased_section(self):
        for suffix in [" - Unreleased", " - UNRELEASED", " - 待发布"]:
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                extract_release_notes(section(suffix=suffix), "1.5.0")

    def test_rejects_missing_or_reversed_languages(self):
        for notes in ["## v1.5.0\n\n**中文**\n- 内容\n", "## v1.5.0\n**English**\n- Content\n**中文**\n- 内容"]:
            with self.subTest(notes=notes), self.assertRaises(ValueError):
                extract_release_notes(notes, "1.5.0")

    def test_rejects_empty_language_body(self):
        for notes in ["## v1.5.0\n**中文**\n**English**\n- Content", "## v1.5.0\n**中文**\n- 内容\n**English**\n"]:
            with self.subTest(notes=notes), self.assertRaises(ValueError):
                extract_release_notes(notes, "1.5.0")


if __name__ == "__main__":
    unittest.main()
