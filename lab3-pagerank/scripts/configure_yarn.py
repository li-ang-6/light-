#!/usr/bin/env python3
"""Back up and configure this personal VM's existing YARN/MapReduce installation.

This script neither formats HDFS nor starts/stops daemons. Existing experiment
data and unrelated XML properties are retained.
"""
import os
import shutil
import time
from pathlib import Path
import xml.etree.ElementTree as ET


def update_xml(path, settings):
    if not path.is_file():
        raise SystemExit(f"Missing existing configuration: {path}")
    tree = ET.parse(path)
    root = tree.getroot()
    if root.tag != "configuration":
        raise SystemExit(f"Unexpected XML root in {path}; no changes made")
    properties = {}
    for prop in root.findall("property"):
        properties.setdefault(prop.findtext("name"), []).append(prop)
    if all(len(properties.get(key, [])) == 1
           and properties[key][0].findtext("value") == value
           for key, value in settings.items()):
        print(f"Already configured: {path}")
        return
    for key, value in settings.items():
        matches = properties.get(key, [])
        if matches:
            prop = matches[0]
            for duplicate in matches[1:]:
                root.remove(duplicate)
        else:
            prop = ET.SubElement(root, "property")
            ET.SubElement(prop, "name").text = key
        node = prop.find("value")
        if node is None:
            node = ET.SubElement(prop, "value")
        node.text = value
    backup = path.with_name(f"{path.name}.lab3-backup-{time.time_ns()}")
    shutil.copy2(path, backup)
    temporary = path.with_name(f"{path.name}.lab3-{os.getpid()}.tmp")
    tree.write(temporary, encoding="UTF-8", xml_declaration=True)
    temporary.replace(path)
    print(f"Backup: {backup}")
    print(f"Configured: {path}")


def update_yarn_heap(path):
    marker = "# LAB3 YARN daemon heap limits"
    addition = (f"\n{marker}\n"
                "export YARN_RESOURCEMANAGER_HEAPSIZE=256\n"
                "export YARN_NODEMANAGER_HEAPSIZE=256\n")
    text = path.read_text(encoding="utf-8") if path.is_file() else ""
    if addition in text:
        print(f"Already configured: {path}")
        return
    if path.exists():
        backup = path.with_name(f"{path.name}.lab3-backup-{time.time_ns()}")
        shutil.copy2(path, backup)
        print(f"Backup: {backup}")
    with path.open("a", encoding="utf-8", newline="\n") as handle:
        handle.write(addition)
    print(f"Configured: {path}")


def main():
    home = Path(os.environ.get("HADOOP_HOME", ""))
    if not os.environ.get("HADOOP_HOME") or not (home / "bin/hadoop").is_file():
        raise SystemExit("Load the existing Hadoop environment first (HADOOP_HOME).")
    conf = Path(os.environ.get("HADOOP_CONF_DIR", str(home / "etc/hadoop")))
    yarn = conf / "yarn-site.xml"
    mapred = conf / "mapred-site.xml"
    # Validate both files before writing either one.
    for path in (yarn, mapred):
        if not path.is_file() or ET.parse(path).getroot().tag != "configuration":
            raise SystemExit(f"Invalid or missing existing configuration: {path}")
    tree = ET.parse(yarn)
    services = []
    for prop in tree.getroot().findall("property"):
        if prop.findtext("name") == "yarn.nodemanager.aux-services":
            services += [x.strip() for x in (prop.findtext("value") or "").split(",")
                         if x.strip()]
    services = list(dict.fromkeys(services + ["mapreduce_shuffle"]))
    update_xml(yarn, {
        "yarn.nodemanager.aux-services": ",".join(services),
        "yarn.nodemanager.aux-services.mapreduce_shuffle.class":
            "org.apache.hadoop.mapred.ShuffleHandler",
        "yarn.nodemanager.resource.memory-mb": "1536",
        "yarn.scheduler.minimum-allocation-mb": "128",
        "yarn.scheduler.increment-allocation-mb": "128",
        "yarn.scheduler.maximum-allocation-mb": "1536",
        "yarn.nodemanager.resource.cpu-vcores": "2",
        "yarn.scheduler.maximum-allocation-vcores": "2",
        "yarn.nodemanager.vmem-check-enabled": "false",
        "yarn.log-aggregation-enable": "true",
    })
    update_xml(mapred, {
        "mapreduce.framework.name": "yarn",
        "mapreduce.application.classpath":
            f"{home}/share/hadoop/mapreduce/*:{home}/share/hadoop/mapreduce/lib/*",
        "yarn.app.mapreduce.am.env": f"HADOOP_MAPRED_HOME={home}",
        "mapreduce.map.env": f"HADOOP_MAPRED_HOME={home}",
        "mapreduce.reduce.env": f"HADOOP_MAPRED_HOME={home}",
        "yarn.app.mapreduce.am.resource.mb": "512",
        "yarn.app.mapreduce.am.command-opts": "-Xmx256m",
        "mapreduce.map.memory.mb": "512",
        "mapreduce.map.java.opts": "-Xmx256m",
        "mapreduce.reduce.memory.mb": "512",
        "mapreduce.reduce.java.opts": "-Xmx256m",
        "mapreduce.map.speculative": "false",
        "mapreduce.reduce.speculative": "false",
    })
    update_yarn_heap(conf / "yarn-env.sh")
    print("YARN_CONFIG_PASS")
    print("Restart ResourceManager and NodeManager after other YARN jobs finish.")
    print("HDFS configuration and data were not changed.")


if __name__ == "__main__":
    main()
