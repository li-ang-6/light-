#!/usr/bin/env python3
"""为已有的单机实验 Hadoop 配置 YARN；不修改 HDFS，不格式化数据。"""
import os
import shutil
import time
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    home = os.environ.get("HADOOP_HOME")
    if not home:
        raise SystemExit("请先设置 HADOOP_HOME")
    conf = Path(os.environ.get("HADOOP_CONF_DIR", str(Path(home) / "etc/hadoop")))
    path = conf / "yarn-site.xml"
    if not path.is_file():
        raise SystemExit(f"找不到配置文件：{path}")
    tree = ET.parse(path)
    root = tree.getroot()
    if root.tag != "configuration":
        raise SystemExit("配置文件根节点不是 configuration，未做修改")
    properties = {p.findtext("name"): p for p in root.findall("property")}
    services = properties.get("yarn.nodemanager.aux-services")
    names = [] if services is None else (services.findtext("value") or "").split(",")
    names = [name.strip() for name in names if name.strip()]
    if "mapreduce_shuffle" not in names:
        names.append("mapreduce_shuffle")
    settings = {
        "yarn.nodemanager.aux-services": ",".join(names),
        "yarn.nodemanager.resource.memory-mb": "1536",
        "yarn.scheduler.minimum-allocation-mb": "512",
        "yarn.scheduler.maximum-allocation-mb": "1536",
        "yarn.nodemanager.resource.cpu-vcores": "2",
        "yarn.scheduler.maximum-allocation-vcores": "2",
        "yarn.nodemanager.vmem-check-enabled": "false",
    }
    if all(properties.get(k) is not None and properties[k].findtext("value") == v
           for k, v in settings.items()):
        print("YARN 配置已符合本实验设置，无需修改。")
        return
    for key, value in settings.items():
        prop = properties.get(key)
        if prop is None:
            prop = ET.SubElement(root, "property")
            ET.SubElement(prop, "name").text = key
        value_node = prop.find("value")
        if value_node is None:
            value_node = ET.SubElement(prop, "value")
        value_node.text = value
    backup = path.with_name(path.name + f".lab2-backup-{time.time_ns()}")
    shutil.copy2(path, backup)
    temporary = path.with_name(path.name + f".lab2-{os.getpid()}.tmp")
    tree.write(temporary, encoding="UTF-8", xml_declaration=True)
    temporary.replace(path)
    print(f"原配置备份：{backup}")
    print(f"已配置：{path}")
    print("请重启 ResourceManager 和 NodeManager；HDFS 无需重启。")


if __name__ == "__main__":
    main()
