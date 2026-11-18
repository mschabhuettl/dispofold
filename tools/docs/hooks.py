"""MkDocs hook: README.md of the repository is the start page of the site, without a copy in docs/.

Its links are written for the repository root: docs/x.md becomes x.md, other files of the repository (LICENSE,
THIRD_PARTY.md) point to the repository on GitHub.
"""
import os
import re

from mkdocs.structure.files import File

LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)\s]+)\)")


def on_files(files, config):
    readme = os.path.join(os.path.dirname(config.config_file_path), "README.md")
    files.append(File.generated(config, "index.md", abs_src_path=readme))
    return files


def on_page_markdown(markdown, page, config, files):
    if page.file.src_uri != "index.md":
        return markdown

    def target(match):
        path = match.group(1)
        if path.startswith("docs/"):
            return f"]({path[len('docs/'):]})"
        return f"]({config.repo_url.rstrip('/')}/blob/main/{path})"

    return LINK.sub(target, markdown)
