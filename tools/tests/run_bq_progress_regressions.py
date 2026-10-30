#!/usr/bin/env python3
"""Compile the complete injector against caller-supplied MCP-mapped native BQ/MC jars.

No Gradle or downloads. The generated fixtures are explicitly limited to mod identity/logger,
Loader presence, BQ's logger/world-directory holder, network sends and untransformed OreDictionary.
SaveLoadHandler, database, quest/task, NBT conversion and progress loading are native.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    args = parser.parse_args()
    root, work = args.source_root.resolve(), args.work_dir.resolve()
    cp = args.classpath_file.read_text().strip()
    if not cp:
        parser.error('classpath file is empty')
    fixtures, classes, resources = [work / name for name in ('fixtures', 'classes', 'resources')]
    for directory in (fixtures, classes, resources):
        directory.mkdir(parents=True, exist_ok=True)
    seams = {
        'BetterQuesting.java': 'package betterquesting.core;public class BetterQuesting{public static final org.apache.logging.log4j.Logger logger=org.apache.logging.log4j.LogManager.getLogger("BQ-native-progress");}',
        'GTSimpleWirelessNetwork.java': 'package com.miaokatze.gtswn.main;public class GTSimpleWirelessNetwork{public static final org.apache.logging.log4j.Logger LOG=org.apache.logging.log4j.LogManager.getLogger("GTSWN-BQ-progress-regression");}',
        'Tags.java': 'package com.miaokatze.gtswn;public class Tags{public static final String VERSION="test-same";}',
        'Loader.java': 'package cpw.mods.fml.common;public class Loader{public static boolean isModLoaded(String name){return true;}}',
        'BQ_Settings.java': 'package betterquesting.api.storage;public class BQ_Settings{public static java.io.File curWorldDir;}',
        'OreDictionary.java': 'package net.minecraftforge.oredict;public class OreDictionary{public static java.util.ArrayList<net.minecraft.item.ItemStack> getOres(String name){return new java.util.ArrayList<>();}}',
    }
    for name, method in [('NetSettingSync', 'sendSync(Object x)'), ('NetQuestSync', 'quickSync(Object x,boolean a,boolean b)'), ('NetChapterSync', 'sendSync(Object x,Object y)')]:
        seams[name + '.java'] = 'package betterquesting.network.handlers;public class ' + name + '{public static void ' + method + '{}}'
    for name, contents in seams.items():
        (fixtures / name).write_text(contents)
    source_dir = root / 'src/main/java/com/miaokatze/gtswn/crossmod/bq'
    sources = list(fixtures.glob('*.java')) + [source_dir / name for name in ('BqQuestInjector.java', 'BqCompat.java', 'BqStampFile.java')]
    sources.append(Path(__file__).with_name('BqProgressRecoveryRegression.java'))
    (work / 'source-sha256.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest() + ' ' + str(p) for p in sources) + '\n')
    (work / 'classpath-sha256.txt').write_text('\n'.join(hashlib.sha256(Path(p).read_bytes()).hexdigest() + ' ' + p for p in cp.split(os.pathsep) if Path(p).is_file()) + '\n')
    java = args.java_home / 'bin'
    compile_command = [str(java / 'javac'), '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes)] + list(map(str, sources))
    result = subprocess.run(compile_command, capture_output=True, text=True, timeout=60)
    (work / 'compile.log').write_text(result.stdout + result.stderr)
    if result.returncode:
        print(result.stdout + result.stderr)
        return result.returncode
    command = [str(java / 'java'), '-cp', os.pathsep.join((str(resources), str(classes), cp)), 'com.miaokatze.gtswn.crossmod.bq.BqProgressRecoveryRegression', str(work)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    (work / 'tests.log').write_text(result.stdout + result.stderr)
    print(result.stdout + result.stderr)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
