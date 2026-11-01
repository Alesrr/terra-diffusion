"""Run isolated NeoForge placement checks and optional native village-start maps."""
import argparse
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
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(args.java_home.resolve())
    gradle = root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    for enabled in (True, False):
        name = ('scaled' if enabled else 'legacy') + ('-windows' if args.backend == 'windows' else '')
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
            if not target.exists():
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
            f'structures.scale_spacing={str(enabled).lower()}\n')
        (game / 'eula.txt').write_text('eula=true\n')
        (game / 'server.properties').write_text(
            'level-type=terrain-diffusion-mc:terrain_diffusion\n'
            'level-seed=42\nonline-mode=false\nserver-ip=127.0.0.1\nserver-port=0\n')
        result = game / 'structure-checks.txt'
        result.unlink(missing_ok=True)
        command = [str(gradle), ':neoforge:runServer', '-PuseDml=true' if args.backend == 'windows' else '-PuseCpu=true',
                   '-I', 'tools/structure-tests/structure-tests.gradle',
                   f'-PstructureTestDir={game}', f'-PstructureTestEnabled={str(enabled).lower()}',
                   '--console=plain']
        if (args.maps or args.valid_villages) and enabled:
            command.append('-PstructureTestMaps=true')
        if args.valid_villages and enabled:
            command.append('-PstructureTestValidVillages=true')
        if args.directml_agent:
            command.append(f'-PstructureTestDirectMLAgent={args.directml_agent.resolve()}')
        if args.village_region:
            command.append(f'-PstructureTestVillageRegion={args.village_region}')
        command.append(f'-PstructureTestCoarseEnd={args.coarse_end}')
        with (game / 'gradle.log').open('w', encoding='utf-8') as log:
            subprocess.run(command, cwd=root, env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True, timeout=1800 if args.valid_villages else 300)
        if not result.is_file() or not result.read_text().startswith('PASS:'):
            raise SystemExit(f'No passing result: {game}')
        print(f'{game.name}: {result.read_text().strip()}', flush=True)


if __name__ == '__main__':
    main()
