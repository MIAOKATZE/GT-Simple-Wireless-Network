"""GTSWN TCP and quantum security remain project-owned profiles."""
CONFIG = ('config/gtswn/gtswn.cfg', 'testworld', 'enableAgentTestHarness', 19871)
CLIENT_RUNNER = None
CLIENT_WORKSPACE = 'miaotest-client-qa'
CLIENT_METADATA = ('client-launch.json', 'host-helper-compile.json')
CLIENT_TOPOLOGY = 'dedicated-localhost-TCP'
CLIENT_JVMS = 2
CLIENT_SOURCES = ('tools/testlab/client_qa/GtswnQuantumClient.java',)
CLIENT_JVM = ('-Dmiaotest.host.controller=miaotest.host.GtswnQuantumClient',)


def client_mode(args, config, preparing, blockers):
    mode = 'quantum.safety_client' if args.case == 'quantum.safety_client' else 'device.paged_client'
    if args.case not in ('all', mode):
        blockers.append('Native client adapter profile is ' + mode + '; other cases use their declared lane')
    return mode


def client_details(mode):
    if mode != 'quantum.safety_client':
        return {}
    return {'businessActor': {'level2': False, 'level4': False, 'evidence': 'native server permission checks required'},
            'controller': 'server console only; real production C2S disc29/5/7 evidence required'}


def server_preparation(args, preparing, blockers):
    import host
    import preparation
    item = host.registered(args.registry, 'gtswn')
    try:
        host.require_java26(item['server_java'])
    except RuntimeError as error:
        blockers.append(str(error))
    host.server_world(item)
    entries = preparation.changes(item['_path'], 'gtswn') if preparing else []
    host.inventory(item['_path'] / 'mods')
    if not host.configuration(item['_path'], 'gtswn')['enabled'] and not preparing:
        blockers.append('GTSWN dedicated side testlab config disabled; --prepare covers both sides')
    if (item['_path'] / '.testlab-host/lease.json').exists() or host.recovery_pending(item):
        blockers.append('GTSWN dedicated side leased or recovery pending')
    return entries
