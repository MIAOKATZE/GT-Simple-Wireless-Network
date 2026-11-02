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

    message('Metadata', [('start_time', 2, 3, False, None), ('interval', 3, 5, False, None),
                         ('end_time', 11, 3, False, None), ('number_of_ticks', 12, 5, False, None)])
    message('Stack', [('class_name', 3, 9, False, None), ('method_name', 4, 9, False, None),
                      ('times', 8, 1, True, None), ('children_refs', 9, 5, True, None)])
    message('Thread', [('name', 1, 9, False, None), ('children', 3, 11, True, 'Stack'),
                       ('times', 4, 1, True, None), ('children_refs', 5, 5, True, None)])
    message('Data', [('metadata', 1, 11, False, 'Metadata'), ('threads', 2, 11, True, 'Thread')])
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


def summarise(path):
    raw = path.read_bytes()
    if raw.startswith(b'\x1f\x8b'):
        raw = gzip.decompress(raw)
    data = schema().FromString(raw)
    meta = data.metadata
    result = {'scope': 'Statistical stack weights in milliseconds; inclusive rows overlap and are not additive. '
                       'Self categories assign leaf stack frames, not causal mod ownership. '
                       'No whole-tick MSPT is inferred from these weights.',
              'startTimeMs': meta.start_time, 'endTimeMs': meta.end_time,
              'durationMs': meta.end_time - meta.start_time, 'intervalMicros': meta.interval,
              'recordedTicks': meta.number_of_ticks, 'threads': []}
    for thread in data.threads:
        methods = defaultdict(lambda: [0.0, 0.0])
        groups = defaultdict(float)
        sample_weight = sum(thread.times)
        for node in thread.children:
            inclusive = sum(node.times)
            own = inclusive - sum(sum(thread.children[index].times) for index in node.children_refs)
            if own < -0.0001:
                raise ValueError('Invalid negative self weight in ' + thread.name)
            methods[node.class_name + '.' + node.method_name][0] += inclusive
            methods[node.class_name + '.' + node.method_name][1] += max(0, own)
            groups[category(node.class_name)] += max(0, own)
        rows = [{'method': name, 'inclusiveMs': round(values[0], 3), 'selfMs': round(values[1], 3)}
                for name, values in methods.items()]
        result['threads'].append({'name': thread.name, 'sampleWeightMs': sample_weight,
                                  'selfCategoriesMs': dict(sorted(groups.items(), key=lambda pair: -pair[1])),
                                  'topInclusive': sorted(rows, key=lambda row: -row['inclusiveMs'])[:50],
                                  'topSelf': sorted(rows, key=lambda row: -row['selfMs'])[:40],
                                  'gtswn': sorted([row for row in rows if row['method'].startswith(
                                      'com.miaokatze.gtswn.')], key=lambda row: -row['inclusiveMs'])[:60]})
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
