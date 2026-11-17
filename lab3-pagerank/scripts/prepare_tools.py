#!/usr/bin/env python3
"""Prepare isolated experiment tools, verifying Apache SHA-512 checksums."""
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HOME = Path.home()
DOWNLOADS = HOME / 'hadoop-lab/downloads'
PACKAGES = [
    ('hbase-2.6.7-hadoop3-bin.tar.gz', Path(os.environ['HBASE_HOME']), 'bin/hbase',
     ['https://dlcdn.apache.org/hbase/2.6.7/', 'https://archive.apache.org/dist/hbase/2.6.7/']),
    ('spark-4.0.5-bin-hadoop3.tgz', Path(os.environ['SPARK_HOME']), 'bin/spark-submit',
     ['https://dlcdn.apache.org/spark/spark-4.0.5/', 'https://archive.apache.org/dist/spark/spark-4.0.5/']),
]


def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'lab3-course-experiment/1.0'})
    return urllib.request.urlopen(request, timeout=45)


def expected_hash(filename, urls):
    errors = []
    for base in urls:
        try:
            with fetch(base + filename + '.sha512') as response:
                value = response.read().decode('utf-8')
            parts = re.findall(r'\b[0-9a-fA-F]{8,128}\b', value)
            digest = ''.join(parts).lower()
            if len(digest) != 128:
                raise ValueError('Cannot parse Apache SHA-512 file: ' + value)
            return digest
        except Exception as exc:
            errors.append(str(exc))
    raise RuntimeError('Cannot fetch official checksum for ' + filename + ': ' + '; '.join(errors))


def prepare(filename, destination, executable, urls):
    if (destination / executable).is_file():
        print('REUSE_TOOL', destination, flush=True)
        return
    archive = DOWNLOADS / filename
    if not archive.is_file():
        last_error = None
        for base in urls:
            try:
                print('DOWNLOAD', base + filename, flush=True)
                subprocess.run(['wget', '-c', '--timeout=30', '--tries=2', '-O', str(archive), base + filename], check=True)
                last_error = None
                break
            except Exception as exc:
                last_error = exc
        if last_error:
            raise last_error
    digest = hashlib.sha512()
    with archive.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != expected_hash(filename, urls):
        raise RuntimeError('Checksum mismatch or incomplete download: ' + str(archive) + '. Re-download this installation archive before retrying.')
    print('SHA512_PASS', filename, flush=True)
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(['tar', '-xzf', str(archive), '-C', str(destination), '--strip-components=1'], check=True)
    if not (destination / executable).is_file():
        raise RuntimeError('Extracted executable not found: ' + str(destination / executable))


def xml_config(path, settings):
    root = ET.Element('configuration')
    for key, value in settings.items():
        prop = ET.SubElement(root, 'property')
        ET.SubElement(prop, 'name').text = key
        ET.SubElement(prop, 'value').text = str(value)
    ET.indent(root)
    content = ET.tostring(root, encoding='utf-8', xml_declaration=True)
    if path.is_file() and path.read_bytes() != content:
        shutil.copy2(path, path.with_name(path.name + '.backup-' + str(time.time_ns())))
    path.write_bytes(content)


def main():
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    for package in PACKAGES:
        prepare(*package)
    hbase = Path(os.environ['HBASE_HOME'])
    conf = Path(os.environ['HBASE_CONF_DIR'])
    conf.mkdir(parents=True, exist_ok=True)
    for source in (hbase / 'conf').iterdir():
        if source.is_file() and not (conf / source.name).exists():
            shutil.copy2(source, conf / source.name)
    runtime = HOME / 'hadoop-lab/lab3-runtime'
    for name in ['pids', 'logs', 'zookeeper', 'tmp']:
        (runtime / name).mkdir(parents=True, exist_ok=True)
    fs = subprocess.check_output(['hdfs', 'getconf', '-confKey', 'fs.defaultFS'], text=True).strip()
    if not fs.startswith('hdfs://'):
        raise RuntimeError('Expected the existing HDFS configuration, got: ' + fs)
    xml_config(conf / 'hbase-site.xml', {
        'hbase.rootdir': fs.rstrip('/') + '/user/hadooplab/lab3-hbase',
        'hbase.cluster.distributed': 'true',
        'hbase.zookeeper.quorum': '127.0.0.1',
        'hbase.zookeeper.property.clientPort': '2183',
        'hbase.zookeeper.property.dataDir': str(runtime / 'zookeeper'),
        'hbase.tmp.dir': str(runtime / 'tmp'),
        'hbase.master.port': '16030', 'hbase.master.info.port': '16031',
        'hbase.regionserver.port': '16040', 'hbase.regionserver.info.port': '16041',
        'hbase.wal.provider': 'filesystem',
        'hbase.client.retries.number': '3',
        'hbase.rpc.timeout': '15000', 'hbase.client.operation.timeout': '60000',
        'hbase.regionserver.handler.count': '5',
        'hbase.master.handler.count': '5',
    })
    # Only this experiment's separate configuration is written here.
    (conf / 'hbase-env.sh').write_text('''export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export HBASE_HEAPSIZE="${HBASE_HEAPSIZE:-256}"
export HBASE_OFFHEAPSIZE=64
export HBASE_MANAGES_ZK=true
export HBASE_DISABLE_HADOOP_CLASSPATH_LOOKUP=true
export HBASE_OPTS="-XX:+UseG1GC -XX:ActiveProcessorCount=1"
''', encoding='utf-8')
    print('TOOLS_AND_HBASE_CONFIG_READY', flush=True)


if __name__ == '__main__':
    main()
