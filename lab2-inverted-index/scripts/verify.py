#!/usr/bin/env python3
"""独立重算词频，核对 Hadoop 的全部倒排索引，而不是只检查样例行。"""
import argparse
from collections import Counter, defaultdict
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
WORD = re.compile(r"[a-z]+")
POSTINGS = re.compile(r"((?:<[^<>,;\t]+,[1-9][0-9]*>;)+)<total,([1-9][0-9]*)>\.")
ENTRY = re.compile(r"<([^<>,;\t]+),([1-9][0-9]*)>;")


def expected_index(documents, stopword_file):
    stopwords = set()
    for line in stopword_file.read_text(encoding="utf-8").splitlines():
        line = line.strip().lower()
        if line and not line.startswith("#"):
            stopwords.update(line.split())
    files = sorted(documents.glob("*.txt"))
    if not files:
        raise ValueError(f"输入文档目录为空：{documents}")
    index = defaultdict(dict)
    for file in files:
        counts = Counter(
            word for word in WORD.findall(file.read_text(encoding="utf-8").lower())
            if word not in stopwords
        )
        for word, count in counts.items():
            index[word][file.name] = count
    return dict(index), len(files)


def read_actual(path):
    index = {}
    previous_word = ""
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if "\t" not in line:
            raise ValueError(f"第 {number} 行缺少单词与索引之间的制表符")
        word, postings = line.split("\t", 1)
        if not WORD.fullmatch(word):
            raise ValueError(f"第 {number} 行单词格式错误：{word!r}")
        if word <= previous_word:
            raise ValueError(f"第 {number} 行单词重复或未按字母排序：{word}")
        previous_word = word
        match = POSTINGS.fullmatch(postings)
        if not match:
            raise ValueError(f"第 {number} 行倒排列表格式错误")
        counts = {}
        previous_document = ""
        for document, count in ENTRY.findall(match.group(1)):
            if document <= previous_document:
                raise ValueError(f"第 {number} 行文档重复或未按文件名排序")
            previous_document = document
            counts[document] = int(count)
        if sum(counts.values()) != int(match.group(2)):
            raise ValueError(f"第 {number} 行 total 与各文档词频之和不一致：{word}")
        index[word] = counts
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("actual", nargs="?", type=Path,
                        help="Hadoop 实际输出；省略时读取 results/LATEST.txt 中的 actual.txt")
    parser.add_argument("--documents", type=Path, default=ROOT / "data/documents")
    parser.add_argument("--stopwords", type=Path, default=ROOT / "data/stopwords.txt")
    args = parser.parse_args()
    try:
        actual_path = args.actual
        if actual_path is None:
            latest = (ROOT / "results/LATEST.txt").read_text(encoding="utf-8").strip()
            actual_path = Path(latest) / "actual.txt"
        expected, document_count = expected_index(args.documents, args.stopwords)
        actual = read_actual(actual_path)
        missing = sorted(expected.keys() - actual.keys())
        extra = sorted(actual.keys() - expected.keys())
        wrong = sorted(word for word in expected.keys() & actual.keys()
                       if expected[word] != actual[word])
        if missing or extra or wrong:
            raise ValueError(
                f"缺失词 {len(missing)} 个：{missing[:10]}；"
                f"多余词 {len(extra)} 个：{extra[:10]}；"
                f"词频错误 {len(wrong)} 个：{wrong[:10]}"
            )
        total = sum(sum(counts.values()) for counts in expected.values())
        print("CHECK PASS: all words, document counts and totals match")
        print(f"Documents: {document_count}")
        print(f"Indexed words: {len(expected)}")
        print(f"Occurrences after stopword removal: {total}")
        print(f"Actual result: {actual_path.resolve()}")
        return 0
    except (OSError, UnicodeError, ValueError) as error:
        print(f"CHECK FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
