"""Builds the documentation site with Zensical (mkdocs.yml) into site/ or the given folder, strict: a missing page,
anchor, image or other file fails.

    pip install -r tools/docs/requirements.txt && python tools/docs/build.py [output folder]

Zensical runs no MkDocs hooks, so this script assembles the pages in a temporary folder first:
- README.md of the repository is the start page. Its links are written for the repository root: docs/x.md becomes
  x.md, other files of the repository (LICENSE, THIRD_PARTY.md) point to the repository on GitHub.
- Plugins (docs/plugins.md): the help pages of each plugin (plugins/<id>/docs/<page>.md) become
  anleitung/plugin-<id>-<page>.md, listed under "Anleitung" after the core pages in the order of the plugin's help
  list, as in the built-in guide. Plugins marked testOnly in their plugin.json only with DISPOFOLD_DOCS_TEST_PLUGINS=1
  (CI builds them into a throwaway folder). Their images (plugins/<id>/docs/img/<name>.png, shown as img/<name>.png)
  become anleitung/plugin-<id>-img/<name>.png.
Zensical checks links to pages and anchors but not images and other files, nor pages missing from the navigation; so
every page must be in the navigation, and after the build every relative src and href of the built pages must name a
file of the site.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)\s]+)\)")


def plugin_pages():
    """[(site path, title, source file)] of the plugins' help pages."""
    base = os.path.join(ROOT, "plugins")
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


def start_page(repo_url):
    """README.md with its links rewritten for the site."""
    with open(os.path.join(ROOT, "README.md"), encoding="utf-8") as f:
        markdown = f.read()

    def target(match):
        path = match.group(1)
        if path.startswith("docs/"):
            return f"]({path[len('docs/'):]})"
        return f"]({repo_url.rstrip('/')}/blob/main/{path})"

    return LINK.sub(target, markdown)


def stage(work):
    """The pages in work/docs and a configuration work/mkdocs.yml that inherits the repository's, with the plugins'
    pages in the navigation; returns the site_url."""
    with open(os.path.join(ROOT, "mkdocs.yml"), encoding="utf-8") as f:
        config = yaml.load(f, Loader=yaml.Loader)  # our own file; its toc slugify is a Python object
    docs = os.path.join(work, "docs")
    shutil.copytree(os.path.join(ROOT, config["docs_dir"]), docs)
    with open(os.path.join(docs, "index.md"), "w", encoding="utf-8") as f:
        f.write(start_page(config["repo_url"]))
    nav = config["nav"]
    guide = next(item["Anleitung"] for item in nav if isinstance(item, dict) and "Anleitung" in item)
    for path, title, source in plugin_pages():
        plugin = re.match(r"anleitung/plugin-([a-z0-9]+)-", path).group(1)
        with open(source, encoding="utf-8") as f:
            # written as if in docs/anleitung/ with the images in img/ next to them
            markdown = f.read().replace("](img/", f"](plugin-{plugin}-img/")
        with open(os.path.join(docs, path), "w", encoding="utf-8") as f:
            f.write(markdown)
        guide.append({title: path})
        img = os.path.join(os.path.dirname(source), "img")
        for name in sorted(os.listdir(img)) if os.path.isdir(img) else []:
            if re.match(r"[a-z0-9-]+\.png$", name):
                os.makedirs(os.path.join(docs, f"anleitung/plugin-{plugin}-img"), exist_ok=True)
                shutil.copyfile(os.path.join(img, name), os.path.join(docs, f"anleitung/plugin-{plugin}-img/{name}"))
    with open(os.path.join(work, "mkdocs.yml"), "w", encoding="utf-8") as f:
        inherit = os.path.relpath(os.path.join(ROOT, "mkdocs.yml"), work)
        yaml.safe_dump({"INHERIT": inherit, "docs_dir": "docs", "site_dir": "site", "nav": nav}, f, allow_unicode=True)
    return config["site_url"]


def inside(path, folder):
    return os.path.commonpath([path, folder]) == folder


def nav_paths(nav):
    """The pages of a navigation (mkdocs.yml)."""
    for item in nav:
        for value in item.values() if isinstance(item, dict) else [item]:
            yield from nav_paths(value) if isinstance(value, list) else [value]


def omitted_pages(work):
    """Pages of work/docs that the navigation of work/mkdocs.yml does not list."""
    with open(os.path.join(work, "mkdocs.yml"), encoding="utf-8") as f:
        listed = set(nav_paths(yaml.safe_load(f)["nav"]))
    docs = os.path.join(work, "docs")
    pages = {os.path.relpath(os.path.join(folder, name), docs).replace(os.sep, "/")
             for folder, _, names in os.walk(docs) for name in names if name.endswith(".md")}
    return sorted(pages - listed)


class Refs(HTMLParser):
    """The src and href attributes of an HTML page."""

    def __init__(self):
        super().__init__()
        self.refs = []

    def handle_starttag(self, tag, attrs):
        self.refs += [value for name, value in attrs if name in ("src", "href") and value]


def missing_files(site, base):
    """src and href of the built pages within the site (relative, or absolute under the path of site_url, as on the
    error page) that name no file of the site, as 'page: reference'."""
    missing = []
    for folder, _, names in os.walk(site):
        for name in names:
            if not name.endswith(".html"):
                continue
            page = os.path.join(folder, name)
            parser = Refs()
            with open(page, encoding="utf-8") as f:
                parser.feed(f.read())
            for ref in parser.refs:
                url = urlsplit(ref)
                if url.scheme or url.netloc or not url.path:
                    continue
                if url.path.startswith("/"):
                    if not url.path.startswith(base):
                        missing.append(f"{os.path.relpath(page, site)}: {ref}")
                        continue
                    target = os.path.join(site, unquote(url.path[len(base):]))
                else:
                    target = os.path.join(folder, unquote(url.path))
                target = os.path.normpath(target)
                if url.path.endswith("/") or os.path.isdir(target):
                    target = os.path.join(target, "index.html")
                if not inside(target, site) or not os.path.isfile(target):
                    missing.append(f"{os.path.relpath(page, site)}: {ref}")
    return missing


def main():
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "site"))
    # the output folder is replaced: never the repository or a folder of it other than site/
    if inside(ROOT, out) or (inside(out, ROOT) and out != os.path.join(ROOT, "site")):
        sys.exit(f"Documentation site: {out} is not a folder for the site (site/ or a folder outside the repository)")
    with tempfile.TemporaryDirectory() as work:
        if inside(work, out):
            sys.exit(f"Documentation site: {out} holds the temporary folder of the build")
        base = urlsplit(stage(work)).path.rstrip("/") + "/"
        omitted = omitted_pages(work)
        if omitted:
            sys.exit("Documentation site: pages missing from the navigation (mkdocs.yml)\n" + "\n".join(omitted))
        config = os.path.join(work, "mkdocs.yml")
        if subprocess.run([sys.executable, "-m", "zensical", "build", "--strict", "-f", config]).returncode:
            sys.exit("Documentation site: the build failed")
        missing = missing_files(os.path.join(work, "site"), base)
        if missing:
            sys.exit("Documentation site: links to missing files\n" + "\n".join(missing))
        shutil.rmtree(out, ignore_errors=True)
        shutil.copytree(os.path.join(work, "site"), out)
    print(f"Documentation site in {out}")


if __name__ == "__main__":
    main()
