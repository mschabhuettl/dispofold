"""MkDocs hook: README.md of the repository is the start page of the site, without a copy in docs/.

Its links are written for the repository root: docs/x.md becomes x.md, other files of the repository (LICENSE,
THIRD_PARTY.md) point to the repository on GitHub.

Plugins (docs/plugins.md): the help pages of each plugin (plugins/<id>/docs/<page>.md) become
anleitung/plugin-<id>-<page>.md, listed under "Anleitung" after the core pages in the order of the plugin's help list,
as in the built-in guide. Plugins
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
        order = help_order(os.path.join(base, plugin))
        names = sorted(os.listdir(docs)) if os.path.isdir(docs) else []
        for name in sorted(names, key=lambda n: (order.get(n[:-3], len(order)), n)):
            if re.match(r"[a-z0-9-]+\.md$", name):
                path = os.path.join(docs, name)
                with open(path, encoding="utf-8") as f:
                    title = f.readline().strip().removeprefix("# ")
                pages.append((f"anleitung/plugin-{plugin}-{name}", title, path))
    return pages


def help_order(plugin_dir):
    """{page: position} of the plugin's help list in its frontend (frontend/index.js, `help: [{ page: '…' }, …]`), the
    order of the built-in guide; pages it does not list come after them, by name."""
    path = os.path.join(plugin_dir, "frontend", "index.js")
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as f:
        listed = re.search(r"\bhelp:\s*\[(.*?)\]", f.read(), re.S)
    pages = re.findall(r"\bpage:\s*'([a-z0-9-]+)'", listed.group(1)) if listed else []
    return {page: i for i, page in enumerate(pages)}


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
