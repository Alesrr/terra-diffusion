"""Run isolated NeoForge placement checks and optional native village-start maps."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--models', type=Path, required=True)
    parser.add_argument('--terralith', type=Path, required=True)
    parser.add_argument('--lithostitched', type=Path, required=True)
    parser.add_argument('--maps', action='store_true', help='Also export candidate maps for scales 1-6')
    parser.add_argument('--valid-villages', action='store_true', help='Run native village-start generation in a land-focused region')
    parser.add_argument('--backend', choices=('cpu', 'windows'), default='cpu')
    parser.add_argument('--directml-agent', type=Path, help='Optional instance-local DirectML preload agent')
    parser.add_argument('--village-region', help='Optional native centerX,centerZ,extent for a matched land-region comparison')
    parser.add_argument('--coarse-end', type=int, default=16, help='Extend the coarse backdrop when the chosen region requires it')
    parser.add_argument('--world-smoke', action='store_true', help='Generate one bounded village area, save, then reopen the same world')
    args = parser.parse_args()
    if args.world_smoke and (args.maps or args.valid_villages):
        parser.error('--world-smoke is a separate check; do not combine it with map generation')
    root = Path(__file__).resolve().parents[2]
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(args.java_home.resolve())
    gradle = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    cases = [('world-smoke-scale4', True, 'auto', 'generate'), ('world-smoke-scale4', True, 'auto', 'reload')] if args.world_smoke else [
        ('scaled', True, 'true', ''), ('legacy', False, 'false', ''), ('legacy-auto', False, 'auto', '')]
    for fixture_name, enabled, policy, smoke_phase in cases:
        name = fixture_name + ('-windows' if args.backend == 'windows' else '')
        game = root / 'build' / 'structure-tests' / name
        marker = game / 'owned-structure-test.txt'
        if game.exists() and not marker.is_file():
            raise SystemExit(f'Refusing to use an unowned test directory: {game}')
        game.mkdir(parents=True, exist_ok=True)
        marker.write_text('Disposable Terrain Diffusion structure-placement test\n')
        models = game / 'terrain-diffusion-models'
        models.mkdir(exist_ok=True)
        for source in args.models.resolve().iterdir():
            if source.suffix not in ('.onnx', '.json'):
                continue
            target = models / source.name
            if target.exists():
                if not source.samefile(target):
                    with source.open('rb') as stream:
                        expected = hashlib.file_digest(stream, 'sha256').digest()
                    with target.open('rb') as stream:
                        actual = hashlib.file_digest(stream, 'sha256').digest()
                    if expected != actual:
                        raise SystemExit(f'Fixture input differs from requested model/config: {target}')
            else:
                if source.suffix == '.onnx':
                    try:
                        os.link(source, target)
                    except OSError:
                        shutil.copyfile(source, target)
                else:
                    shutil.copyfile(source, target)
        mods = game / 'mods'
        mods.mkdir(exist_ok=True)
        for source in (args.terralith, args.lithostitched):
            shutil.copyfile(source, mods / source.name)
        config = game / 'config'
        config.mkdir(exist_ok=True)
        (config / 'terrain-diffusion-mc.properties').write_text(
            f'inference.device={"gpu" if args.backend == "windows" else "cpu"}\nvalidate_model=false\n'
            f'structures.scale_spacing={policy}\n')
        (game / 'eula.txt').write_text('eula=true\n')
        if args.world_smoke:
            pack = game / 'world/datapacks/structure_smoke'
            preset_file = pack / 'data/structure_smoke/worldgen/world_preset/scale4.json'
            preset_file.parent.mkdir(parents=True, exist_ok=True)
            (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'pack_format': 48, 'description': 'Owned scale-4 village smoke fixture'}}))
            preset = json.loads((root / 'common/src/main/resources/data/terrain-diffusion-mc/worldgen/world_preset/terrain_diffusion.json').read_text())
            preset['dimensions']['minecraft:overworld']['type'] = 'terrain-diffusion-mc:terrain_diffusion_scale_4'
            preset_file.write_text(json.dumps(preset))
        (game / 'server.properties').write_text(
            f'level-type={"structure_smoke:scale4" if args.world_smoke else "terrain-diffusion-mc:terrain_diffusion"}\n'
            'level-seed=42\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nmax-tick-time=-1\nview-distance=2\nsimulation-distance=2\n'
            + ('initial-enabled-packs=vanilla,file/structure_smoke\n' if args.world_smoke else ''))
        result = game / (f'world-smoke-{smoke_phase}.json' if args.world_smoke else 'structure-checks.txt')
        result.unlink(missing_ok=True)
        command = [str(gradle), ':neoforge:runServer', '-PuseDml=true' if args.backend == 'windows' else '-PuseCpu=true',
                   '-I', 'tools/structure-tests/structure-tests.gradle',
                   f'-PstructureTestDir={game}', f'-PstructureTestEnabled={str(enabled).lower()}',
                   '--console=plain']
        if (args.maps or args.valid_villages) and enabled:
            command.append('-PstructureTestMaps=true')
        if args.valid_villages and enabled:
            command.append('-PstructureTestValidVillages=true')
        if smoke_phase:
            command.append(f'-PstructureTestSmokePhase={smoke_phase}')
        if fixture_name == 'legacy-auto':
            command.append('-PstructureTestLegacySettings=true')
        if args.directml_agent:
            command.append(f'-PstructureTestDirectMLAgent={args.directml_agent.resolve()}')
        if args.village_region:
            command.append(f'-PstructureTestVillageRegion={args.village_region}')
        command.append(f'-PstructureTestCoarseEnd={args.coarse_end}')
        with (game / (f'gradle-{smoke_phase}.log' if smoke_phase else 'gradle.log')).open('w', encoding='utf-8') as log:
            subprocess.run(command, cwd=root, env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=1800 if args.valid_villages or args.world_smoke else 300)
        if not result.is_file() or (not args.world_smoke and not result.read_text().startswith('PASS:')):
            raise SystemExit(f'No passing result: {game}')
        status = f'{smoke_phase} passed' if args.world_smoke else result.read_text().strip()
        print(f'{game.name}: {status}', flush=True)


if __name__ == '__main__':
    main()
