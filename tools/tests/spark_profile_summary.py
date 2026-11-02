"""Summarise local Spark 1.10 profiles without uploading them.

Requires google.protobuf. Field numbers follow Spark's spark_sampler.proto.
Weights are statistical samples, not exact CPU time or per-mod tick duration.
"""
from pathlib import Path
import argparse
from collections import defaultdict
import gzip
import json

from google.protobuf import descriptor_pb2, descriptor_pool, message_factory


def schema():
    proto = descriptor_pb2.FileDescriptorProto(name='factory_spark.proto', package='spark', syntax='proto3')

    def message(name, fields):
        target = proto.message_type.add(name=name)
        for field_name, number, kind, repeated, nested in fields:
            field = target.field.add(name=field_name, number=number, type=kind, label=3 if repeated else 1)
            if nested:
                field.type_name = '.spark.' + nested
        return target

    message('Metadata', [('start_time', 2, 3, False, None), ('interval', 3, 5, False, None),
                         ('end_time', 11, 3, False, None), ('number_of_ticks', 12, 5, False, None)])
    message('Stack', [('class_name', 3, 9, False, None), ('method_name', 4, 9, False, None),
                      ('method_desc', 7, 9, False, None),
                      ('times', 8, 1, True, None), ('children_refs', 9, 5, True, None)])
    message('Thread', [('name', 1, 9, False, None), ('children', 3, 11, True, 'Stack'),
                       ('times', 4, 1, True, None), ('children_refs', 5, 5, True, None)])
    message('Window', [('ticks', 1, 5, False, None), ('tps', 4, 1, False, None),
                       ('mspt_median', 5, 1, False, None), ('mspt_max', 6, 1, False, None),
                       ('tile_entities', 9, 5, False, None), ('chunks', 10, 5, False, None),
                       ('start_time', 11, 3, False, None), ('end_time', 12, 3, False, None),
                       ('duration', 13, 5, False, None)])
    message('WindowEntry', [('key', 1, 5, False, None), ('value', 2, 11, False, 'Window')
                            ]).options.map_entry = True
    message('Data', [('metadata', 1, 11, False, 'Metadata'), ('threads', 2, 11, True, 'Thread'),
                     ('time_windows', 6, 5, True, None), ('windows', 7, 11, True, 'WindowEntry')])
    pool = descriptor_pool.DescriptorPool()
    pool.Add(proto)
    return message_factory.GetMessageClass(pool.FindMessageTypeByName('spark.Data'))


def category(class_name):
    for prefix, group in [('com.miaokatze.gtswn.common.testworld.', 'GTSWN test fixture'),
                          ('com.miaokatze.gtswn.', 'GTSWN production'),
                          ('com.miaokatze.miaokatzecore.', 'MIAOKATZE Core'),
                          ('appeng.', 'AE2'), ('gregtech.', 'GregTech'),
                          ('gtPlusPlus.', 'GT++'), ('goodgenerator.', 'GoodGenerator'),
                          ('tectech.', 'TecTech'), ('bartworks.', 'BartWorks'),
                          ('com.gtnewhorizon.structurelib.', 'StructureLib'),
                          ('net.minecraft.', 'Minecraft'), ('java.', 'JVM/library'),
                          ('jdk.', 'JVM/library'), ('sun.', 'JVM/library')]:
        if class_name.startswith(prefix):
            return group
    return 'Other mods/libraries'


def stack_rows(thread, window_index=None):
    def weight(node):
        if window_index is None:
            return sum(node.times)
        return node.times[window_index] if window_index < len(node.times) else 0.0
    names = [node.class_name + '.' + node.method_name + node.method_desc for node in thread.children]
    parents = {child: parent for parent, node in enumerate(thread.children) for child in node.children_refs}
    methods = defaultdict(lambda: [0.0, 0.0, 0.0])
    groups = defaultdict(float)
    for index, node in enumerate(thread.children):
        inclusive = weight(node)
        own = inclusive - sum(weight(thread.children[child]) for child in node.children_refs)
        if own < -0.0001:
            raise ValueError('Invalid negative self weight in ' + thread.name)
        name = names[index]
        parent = parents.get(index)
        while parent is not None and names[parent] != name:
            parent = parents.get(parent)
        methods[name][0] += inclusive
        methods[name][1] += max(0, own)
        if parent is None:
            methods[name][2] += inclusive
        groups[category(node.class_name)] += max(0, own)
    rows = [{'method': name, 'inclusiveMs': round(value[0], 3), 'selfMs': round(value[1], 3),
             'outermostInclusiveMs': round(value[2], 3)}
            for name, value in methods.items() if value[0] > 0]
    return rows, groups


def summarise(path):
    raw = path.read_bytes()
    if raw.startswith(b'\x1f\x8b'):
        raw = gzip.decompress(raw)
    data = schema().FromString(raw)
    meta = data.metadata
    result = {'scope': 'Statistical stack weights in milliseconds; inclusive rows overlap and are not additive. '
                       'Self categories assign leaf stack frames, not causal mod ownership. '
                       'No whole-tick MSPT is inferred from these weights. method_desc distinguishes signatures when '
                       'present; otherwise overloads aggregate by class and name. outermostInclusiveMs counts the '
                       'highest occurrence of the same method key on each call path, avoiding nested double counting.',
              'stackNodesWithSignature': sum(bool(node.method_desc) for thread in data.threads for node in thread.children),
              'windowStatisticsScope': 'Spark recorder snapshots: MSPT values can retain rolling last-minute peaks '
                                       'across adjacent buckets; they are not bucket-exclusive maxima. '
                                       'Per-window stack weights use the recorded bucket indices.',
              'startTimeMs': meta.start_time, 'endTimeMs': meta.end_time,
              'durationMs': meta.end_time - meta.start_time, 'intervalMicros': meta.interval,
              'recordedTicks': meta.number_of_ticks, 'threads': [], 'serverWindows': []}
    for thread in data.threads:
        sample_weight = sum(thread.times)
        rows, groups = stack_rows(thread)
        result['threads'].append({'name': thread.name, 'sampleWeightMs': sample_weight,
                                  'selfCategoriesMs': dict(sorted(groups.items(), key=lambda pair: -pair[1])),
                                  'topInclusive': sorted(rows, key=lambda row: -row['inclusiveMs'])[:50],
                                  'topOutermost': sorted(rows, key=lambda row: -row['outermostInclusiveMs'])[:50],
                                  'topSelf': sorted(rows, key=lambda row: -row['selfMs'])[:40],
                                  'gtswn': sorted([row for row in rows if row['method'].startswith(
                                      'com.miaokatze.gtswn.')], key=lambda row: -row['outermostInclusiveMs'])[:60]})
        if thread.name == 'Server thread':
            for index, key in enumerate(data.time_windows):
                window = data.windows[key]
                def weight(node):
                    return node.times[index] if index < len(node.times) else 0.0
                rows, _ = stack_rows(thread, index)
                result['serverWindows'].append({'windowId': key, 'startTimeMs': window.start_time,
                    'endTimeMs': window.end_time, 'ticks': window.ticks, 'tps': window.tps,
                    'msptMedian': window.mspt_median, 'msptMax': window.mspt_max,
                    'chunks': window.chunks, 'tileEntities': window.tile_entities,
                    'sampleWeightMs': weight(thread),
                    'topInclusive': sorted(rows, key=lambda row: -row['inclusiveMs'])[:30],
                    'topOutermost': sorted(rows, key=lambda row: -row['outermostInclusiveMs'])[:30],
                    'topSelf': sorted(rows, key=lambda row: -row['selfMs'])[:20],
                    'gtswn': sorted([row for row in rows if row['method'].startswith(
                        'com.miaokatze.gtswn.')], key=lambda row: -row['outermostInclusiveMs'])[:25]})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', type=Path)
    args = parser.parse_args()
    summary = summarise(args.profile)
    output = args.profile.with_suffix('.summary.json')
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    server = [thread for thread in summary['threads'] if thread['name'] == 'Server thread']
    print(json.dumps({'output': str(output), 'durationMs': summary['durationMs'],
                      'recordedTicks': summary['recordedTicks'], 'server': server}, ensure_ascii=False, indent=2))
