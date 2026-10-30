#!/usr/bin/env python3
"""Write a new local synthetic saved-world fixture; does not contact or modify a server."""
import argparse
import hashlib
import json
from pathlib import Path
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source-root', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
resources = args.source_root / 'src/main/resources'
index = json.loads((resources / 'assets/gtswn/bqquests/index.json').read_text())
quest_path = resources / index['questLines'][0]['entries'][0]['questFile']
quest = json.loads(quest_path.read_text())
assert quest['questIDHigh:4'] == 0x4754574e00000000 and quest['questIDLow:4'] == 1
assert quest['tasks:9']['0:10']['taskID:8'] == 'bq_standard:checkbox'
player = '00000000-0000-007b-0000-0000000001c8'
partial_player = '00000000-0000-007c-0000-0000000001c9'
partial_path = next(resources / entry['questFile'] for entry in index['questLines'][0]['entries']
                    if json.loads((resources / entry['questFile']).read_text())['questIDLow:4'] == 9)
partial_quest = json.loads(partial_path.read_text())
assert partial_quest['tasks:9']['0:10']['taskID:8'] == 'bq_standard:crafting'
assert partial_quest['tasks:9']['0:10']['requiredItems:9']['0:10']['Count:3'] == 2
quest_uuid = str(uuid.UUID(int=(quest['questIDHigh:4'] << 64) | quest['questIDLow:4']))
# Native legacy database list schema. The real bundled quest definition is retained verbatim.
database = {
    'format:8': '3.1.0',
    'build:8': '3.8.89-GTNH',
    'questSettings:10': {},
    'questDatabase:9': {'0:10': quest, '1:10': partial_quest},
    'questLines:9': {},
}
# Native QuestInstance/TaskCheckbox serialization, confirmed by the native .89 fixture.
progress = {'questProgress:9': {'0:10': {
    'questIDHigh:4': quest['questIDHigh:4'],
    'questIDLow:4': quest['questIDLow:4'],
    'completed:9': {'0:10': {'uuid:8': player, 'claimed:1': 1, 'timestamp:4': 124}},
    'tasks:9': {'0:10': {
        'index:3': 0, 'taskID:8': 'bq_standard:checkbox',
        'completeUsers:9': {'0:8': player},
    }},
}}}
progress['questProgress:9']['1:10'] = {
    'questIDHigh:4': partial_quest['questIDHigh:4'], 'questIDLow:4': 9,
    'completed:9': {},
    'tasks:9': {'0:10': {
        'index:3': 0, 'taskID:8': 'bq_standard:crafting', 'completeUsers:9': {},
        'userProgress:9': {'0:10': {'uuid:8': partial_player, 'data:11': [1]}},
    }},
}
args.output_dir.mkdir(parents=True, exist_ok=False)
world = args.output_dir / 'World/betterquesting'
world.mkdir(parents=True)
for name, obj in [('QuestDatabase.json', database), ('QuestProgress.json', progress)]:
    (world / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')
manifest = {
    'purpose': 'Synthetic legacy saved-world progress migration, not player gameplay or authentication',
    'fixture_player_uuid': player,
    'quest_uuid': quest_uuid,
    'partial_only_player_uuid': partial_player,
    'partial_quest_source': str(partial_path),
    'partial_quest_source_sha256': hashlib.sha256(partial_path.read_bytes()).hexdigest(),
    'quest_source': str(quest_path),
    'quest_source_sha256': hashlib.sha256(quest_path.read_bytes()).hexdigest(),
    'files': {str(p.relative_to(args.output_dir)): hashlib.sha256(p.read_bytes()).hexdigest() for p in world.iterdir()},
    'expected_post_native_save': {
        'path': 'World/betterquesting/QuestProgress/' + player + '.json',
        'record': {'questIDHigh:4': quest['questIDHigh:4'], 'questIDLow:4': 1},
        'completion': {'uuid:8': player, 'claimed:1': 1, 'timestamp:4': 124},
        'checkbox_complete_user': player,
        'note': 'Locate records by IDs/UUID, not list ordinal or whole-file hash; native save can include other quests.',
    },
    'expected_partial_only_post_native_save': {
        'path': 'World/betterquesting/QuestProgress/' + partial_player + '.json',
        'record': {'questIDHigh:4': partial_quest['questIDHigh:4'], 'questIDLow:4': 9},
        'no_completion_for_uuid': partial_player,
        'task': {'index:3': 0, 'taskID:8': 'bq_standard:crafting'},
        'user_progress': {'uuid:8': partial_player, 'data:11': [1]},
        'required_count': 2,
    },
    'expected_lifecycle': [
        'Native saved QuestDatabase contains the real GTSWN definition when initial aggregate progress is loaded.',
        'Native legacy load identifies the completion user and queues migration.',
        'Pack defaults omit the custom definition; injector recreates it and restores only recreated owned progress.',
        'Injector identifies the partial-only user from the exact native crafting envelope and queues native persistence.',
        'Normal native save writes both offline UUID files; inspect its owned completion claim and checkbox state.',
        'On subsequent restart, per-player progress is restored again after defaults recreation.',
    ],
}
(args.output_dir / 'fixture-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(manifest, ensure_ascii=False, indent=2))
