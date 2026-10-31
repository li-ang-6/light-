#!/usr/bin/env python3
"""Download ten English Python tutorial source documents for the experiment.

These are public reStructuredText sources of the official Python 3.14
tutorial, rather than rendered HTML. RST markup and code examples are kept;
the experiment therefore also tokenizes English words in markup and code.
All downloads are checked before any existing document is replaced.
"""

import csv
import hashlib
import io
import os
from pathlib import Path
import sys
import tempfile
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


PROJECT = Path(__file__).resolve().parents[1]
DATA = PROJECT / "data"
BASE_URL = "https://docs.python.org/3.14/_sources/tutorial/"
DOCUMENTS = (
    ("appetite", "Whetting Your Appetite"),
    ("interpreter", "Using the Python Interpreter"),
    ("introduction", "An Informal Introduction to Python"),
    ("controlflow", "More Control Flow Tools"),
    ("datastructures", "Data Structures"),
    ("modules", "Modules"),
    ("inputoutput", "Input and Output"),
    ("errors", "Errors and Exceptions"),
    ("classes", "Classes"),
    ("stdlib", "Brief Tour of the Standard Library"),
)


def fetch_document(url):
    request = Request(url, headers={"User-Agent": "HadoopInvertedIndexLab/1.0"})
    with urlopen(request, timeout=45) as response:
        content_type = response.headers.get("Content-Type", "").lower()
        body = response.read()
    text = body.decode("utf-8", errors="strict")
    beginning = text.lstrip("\ufeff \t\r\n")[:500].lower()
    if "html" in content_type or "<!doctype html" in beginning or "<html" in beginning:
        raise ValueError("server returned HTML instead of the expected RST source")
    if len(text.strip()) < 200:
        raise ValueError("document is empty or unexpectedly short")
    return body


def main():
    # Keep originals untouched until all ten network requests have succeeded.
    downloaded = []
    for index, (slug, title) in enumerate(DOCUMENTS, start=1):
        filename = "document{:02d}.txt".format(index)
        url = BASE_URL + slug + ".rst.txt"
        try:
            body = fetch_document(url)
        except (HTTPError, URLError, TimeoutError, UnicodeError, ValueError, OSError) as exc:
            print("Download failed for {}: {}".format(url, exc), file=sys.stderr)
            print("Existing documents and sources.tsv have not been changed.", file=sys.stderr)
            return 1
        retrieved = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        digest = hashlib.sha256(body).hexdigest()
        downloaded.append((filename, title, url, retrieved, digest, body))
        print("Downloaded {} ({} bytes)".format(filename, len(body)), flush=True)

    manifest = io.StringIO(newline="")
    writer = csv.writer(manifest, delimiter="\t", lineterminator="\n")
    writer.writerow(["filename", "title", "url", "retrieved_utc", "sha256"])
    for record in downloaded:
        writer.writerow(record[:5])

    DATA.mkdir(parents=True, exist_ok=True)
    destination = DATA / "documents"
    destination.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".download-", dir=str(DATA)) as temporary:
        staging = Path(temporary)
        for filename, _, _, _, _, body in downloaded:
            (staging / filename).write_bytes(body)
        (staging / "sources.tsv").write_bytes(manifest.getvalue().encode("utf-8"))
        for filename, _, _, _, _, _ in downloaded:
            os.replace(str(staging / filename), str(destination / filename))
        os.replace(str(staging / "sources.tsv"), str(DATA / "sources.tsv"))
    print("Saved 10 UTF-8 documents to {}".format(destination))
    print("Source URLs, UTC retrieval times and SHA-256: {}".format(DATA / "sources.tsv"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
