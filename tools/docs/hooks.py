"""MkDocs hook: README.md of the repository is the start page of the site, without a copy in docs/.

Its links are written for the repository root: docs/x.md becomes x.md, other files of the repository (LICENSE,
THIRD_PARTY.md) point to the repository on GitHub.

Plugins (docs/plugins.md): the help pages of each plugin (plugins/<id>/docs/<page>.md) become
anleitung/plugin-<id>-<page>.md, listed under "Anleitung" after the core pages, as in the built-in guide. Plugins
marked testOnly in their plugin.json only with DISPOFOLD_DOCS_TEST_PLUGINS=1 (CI builds them into a throwaway folder).
Their images (plugins/<id>/docs/img/<name>.png, shown as img/<name>.png) become anleitung/plugin-<id>-img/<name>.png.
"""
import json
import os
import re

from mkdocs.structure.files import File

LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)\s]+)\)")


def plugin_pages(config):
    """[(site path, title, source file)] of the plugins' help pages."""
    base = os.path.join(os.path.dirname(config.config_file_path), "plugins")
    test = os.environ.get("DISPOFOLD_DOCS_TEST_PLUGINS") == "1"
    pages = []
    for plugin in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        with open(os.path.join(base, plugin, "plugin.json"), encoding="utf-8") as f:
            if json.load(f).get("testOnly") and not test:
                continue
        docs = os.path.join(base, plugin, "docs")
        for name in sorted(os.listdir(docs)) if os.path.isdir(docs) else []:
            if re.match(r"[a-z0-9-]+\.md$", name):
                path = os.path.join(docs, name)
                with open(path, encoding="utf-8") as f:
                    title = f.readline().strip().removeprefix("# ")
                pages.append((f"anleitung/plugin-{plugin}-{name}", title, path))
    return pages


def on_config(config):
    pages = plugin_pages(config)
    if pages:
        guide = next(item["Anleitung"] for item in config.nav if isinstance(item, dict) and "Anleitung" in item)
        guide.extend({title: path} for path, title, _ in pages)
    return config


def on_files(files, config):
    readme = os.path.join(os.path.dirname(config.config_file_path), "README.md")
    files.append(File.generated(config, "index.md", abs_src_path=readme))
    for path, _, source in plugin_pages(config):
        files.append(File.generated(config, path, abs_src_path=source))
    for path, source in plugin_images(config):
        files.append(File.generated(config, path, abs_src_path=source))
    return files


def plugin_images(config):
    """[(site path, source file)] of the images of the plugins' help pages (docs/img/<name>.png)."""
    images = {}
    for _, _, source in plugin_pages(config):
        img = os.path.join(os.path.dirname(source), "img")
        plugin = os.path.basename(os.path.dirname(os.path.dirname(source)))
        for name in sorted(os.listdir(img)) if os.path.isdir(img) else []:
            if re.match(r"[a-z0-9-]+\.png$", name):
                images[f"anleitung/plugin-{plugin}-img/{name}"] = os.path.join(img, name)
    return sorted(images.items())


def on_page_markdown(markdown, page, config, files):
    plugin = re.match(r"anleitung/plugin-([a-z0-9]+)-[a-z0-9-]+\.md$", page.file.src_uri)
    if plugin:
        # written as if in docs/anleitung/ with the images in img/ next to them
        return markdown.replace("](img/", f"](plugin-{plugin.group(1)}-img/")
    if page.file.src_uri != "index.md":
        return markdown

    def target(match):
        path = match.group(1)
        if path.startswith("docs/"):
            return f"]({path[len('docs/'):]})"
        return f"]({config.repo_url.rstrip('/')}/blob/main/{path})"

    return LINK.sub(target, markdown)
