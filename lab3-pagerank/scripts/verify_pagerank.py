#!/usr/bin/env python3
"""Independently check real MR/Spark output against ten Python PageRank rounds."""
import argparse
import math
from pathlib import Path
import re

DAMPING = 0.85
ITERATIONS = 10
RESULT = re.compile(r"^\(([^,()]+),([0-9]+\.[0-9]{10})\)$")


def read_graph(path):
    graph = {}
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) > 2 or not fields[0].strip():
            raise ValueError(f"Invalid graph line {number}: {line!r}")
        node = fields[0].strip()
        neighbors = {item.strip() for item in fields[1].split(",") if item.strip()} if len(fields) == 2 else set()
        graph.setdefault(node, set()).update(neighbors)
        for target in neighbors:
            graph.setdefault(target, set())
    if not graph:
        raise ValueError("Empty graph")
    return {node: sorted(graph[node]) for node in sorted(graph)}


def reference_states(graph):
    count = len(graph)
    ranks = {node: 1.0 / count for node in graph}
    states = [ranks]
    for _ in range(ITERATIONS):
        dangling = math.fsum(ranks[node] for node, neighbors in graph.items() if not neighbors)
        incoming = {node: [] for node in graph}
        for node, neighbors in graph.items():
            if neighbors:
                for target in neighbors:
                    incoming[target].append(ranks[node] / len(neighbors))
        ranks = {
            node: (1.0 - DAMPING) / count + DAMPING * (math.fsum(incoming[node]) + dangling / count)
            for node in graph
        }
        states.append(ranks)
    return states


def part_files(path):
    path = Path(path)
    if path.is_file():
        return [path]
    files = sorted(path.glob("part-*"))
    if not files:
        raise ValueError(f"No actual result part files at {path}")
    return [item for item in files if item.is_file()]


def read_result(path):
    result = {}
    for file in part_files(path):
        for line in file.read_text(encoding="utf-8").splitlines():
            match = RESULT.fullmatch(line)
            if not match:
                raise ValueError(f"Expected (node,PR with 10 decimals), got {line!r} in {file}")
            node, value = match.groups()
            if node in result:
                raise ValueError(f"Duplicate output node {node}")
            result[node] = float(value)
    return result


def compare(actual, expected, tolerance, label):
    if actual.keys() != expected.keys():
        raise ValueError(f"{label}: node set differs; actual={sorted(actual)}, expected={sorted(expected)}")
    error = max(abs(actual[node] - expected[node]) for node in expected)
    if any(not math.isfinite(value) or value < 0 for value in actual.values()):
        raise ValueError(f"{label}: invalid or negative rank")
    if error > tolerance:
        raise ValueError(f"{label}: maximum error {error:.3g} exceeds {tolerance}")
    if abs(math.fsum(actual.values()) - 1.0) > max(1e-12, len(expected) * tolerance):
        raise ValueError(f"{label}: rank sum is not approximately 1")
    return error


def verify_states(root, graph, expected, label):
    root = Path(root)
    state_dirs = sorted(path.name for path in root.glob("state-*") if path.is_dir())
    required = [f"state-{iteration:02d}" for iteration in range(ITERATIONS + 1)]
    if state_dirs != required:
        raise ValueError(f"{label}: expected initial state plus exactly ten rounds, got {state_dirs}")
    for iteration in range(ITERATIONS + 1):
        actual = {}
        for file in part_files(root / f"state-{iteration:02d}"):
            for line in file.read_text(encoding="utf-8").splitlines():
                fields = line.split("\t")
                if len(fields) != 3:
                    raise ValueError(f"{label}: invalid state row {line!r}")
                node, value, adjacency = fields
                if node in actual:
                    raise ValueError(f"{label}: duplicate state for {node}")
                if node not in graph or adjacency.split(",") != graph[node] and not (not adjacency and not graph[node]):
                    raise ValueError(f"{label}: adjacency changed for {node}")
                actual[node] = float(value)
        compare(actual, expected[iteration], 1e-12, f"{label} round {iteration}")
    metadata = root / "metadata.properties"
    if not metadata.is_file():
        raise ValueError(f"{label}: missing actual runtime metadata")
    properties = dict(line.split("=", 1) for line in metadata.read_text(encoding="utf-8").splitlines() if "=" in line)
    if properties.get("iterations") != "10":
        raise ValueError(f"{label}: iteration metadata mismatch")
    if label == "MapReduce" and properties.get("framework") != "yarn":
        raise ValueError("MapReduce was not run on YARN")
    if label == "Spark" and not (properties.get("master") == "yarn" or properties.get("master", "").startswith("spark://")):
        raise ValueError("Spark was not run on YARN or a standalone cluster")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", required=True)
    parser.add_argument("--mr", required=True, help="Downloaded MR final directory or part file")
    parser.add_argument("--spark", required=True, help="Downloaded Spark final directory or part file")
    parser.add_argument("--mr-states", help="Downloaded MR output root with state-00 through state-10")
    parser.add_argument("--spark-states", help="Downloaded Spark output root with state-00 through state-10")
    args = parser.parse_args()
    graph = read_graph(args.graph)
    states = reference_states(graph)
    mr = read_result(args.mr)
    spark = read_result(args.spark)
    mr_error = compare(mr, states[-1], 5.1e-11, "MapReduce final")
    spark_error = compare(spark, states[-1], 5.1e-11, "Spark final")
    difference = compare(mr, spark, 1e-10, "MapReduce vs Spark")
    if args.mr_states:
        verify_states(args.mr_states, graph, states, "MapReduce")
    if args.spark_states:
        verify_states(args.spark_states, graph, states, "Spark")
    print(f"NODES={len(graph)} ITERATIONS={ITERATIONS} DAMPING={DAMPING}")
    print(f"MR_MAX_ERROR={mr_error:.3g} SPARK_MAX_ERROR={spark_error:.3g} DIFFERENCE={difference:.3g}")
    print(f"MR_SUM={math.fsum(mr.values()):.10f} SPARK_SUM={math.fsum(spark.values()):.10f}")
    for node in sorted(graph, key=lambda item: (-mr[item], item)):
        print(f"({node},{mr[node]:.10f})")
    print("PAGERANK CHECK PASSED")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit(f"PAGERANK CHECK FAILED: {error}") from error
