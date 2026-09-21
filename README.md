<img width="1029" height="1029" alt="TDTR_logo" src="https://github.com/user-attachments/assets/9f4bbe46-3c48-441b-b277-7a3bab7e77c8" />

# Terra Diffusion — Terralith compatibility fork

A fork of [xandergos/terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc)
that adds [Terralith](https://modrinth.com/mod/terralith)'s biomes in worldgen, adds
temperature-driven snow depth, carves rivers, and places water lakes.

<img width="1914" height="1029" alt="Screenshot 2026-07-31 120407" src="https://github.com/user-attachments/assets/1019878e-d109-4e94-ad13-d6daf8ba3eee" />
<img width="1914" height="1030" alt="Screenshot 2026-07-31 130154" src="https://github.com/user-attachments/assets/0e8e82ad-c2e5-4873-bf57-95ae1bd0d425" />

Targets **Minecraft 1.21.1**, Fabric and NeoForge.

## What this fork changes

**Terralith biomes generate.** Upstream replaces the biome source and ships its own noise
settings. This fork maps the model's elevation and climate output onto **86** Terralith
surface biomes, and pulls Terralith's surface rules in at world load. Details in
[Terralith compatibility](#terralith-compatibility) below.

**Snow mantle creation.** Vanilla lays one snow layer everywhere it snows. Here the depth comes from the terrain model's own temperature: 1–7 layers between −5 °C and −7.5 °C, then a full snow block plus another 1–7 layers down to −10 °C. Snow on a tree canopy also snows the ground underneath. If [Snow Real Magic](https://modrinth.com/mod/snow-real-magic) is installed, columns where it is keeping a plant alive get filled as well. 

**Rivers.** A dendric-propagating ocean-to-source approach inspired by [TerraFirmaCraft](https://github.com/TerraFirmaCraft/TerraFirmaCraft)'s river generation system. Using the terrain given by the model, rivers are post-processed on top of the region. 

**Lakes** are made similar to rivers, filling depressions elegible for holding water to create deep and vast lakes, or small and contained depending on their area of generation.

**Additional *additions***. I'm a big Create Mod fan, and usually play with [TFMG](https://github.com/DrMango14/Create-The_Factory_Must_Grow). Because `min_y` has been pinned at -192 for deeper oceans, oil wells have been placed at -189. 
The snow has been made to work with another project of mine called [Snowier Snow](null), which adds snow displacement physics for snow blocks and snow layers, alongside compatibility with the physics engine [Sable](https://github.com/ryanhcode/sable), to work with [Create Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project).
For compatibility requests, open a discussion. I'm actively looking for testers/helpers on the project as it gets more complex!   
NOTE THAT THE MODS LISTED ARE NOT REQUIRED AND THEIR EFFECTS WILL ENABLE WHEN THE PAIRED MOD HAS BEEN FOUND!

## Requirements

- Windows with a GPU, or Linux with an NVIDIA GPU. CPU inference works but is very slow. ***AMD only works with windows version, theoretically***
- **2.5** GB VRAM, **3+** GB RAM (you may need to raise Minecraft's memory allocation).
- Minecraft 1.21.1 with either [Fabric](https://fabricmc.net/) plus [Fabric API](https://modrinth.com/mod/fabric-api), or [NeoForge](https://neoforged.net/).
- DEPENDING ON ADVANCE IN DEVELOPMENT, THESE REQUIREMENTS ARE SUBJECT TO CHANGE!

Only relevant to this fork:

- **[Terralith](https://modrinth.com/mod/terralith)** 
- **[Lithostitched](https://modrinth.com/mod/lithostitched)** 
- **[Snow Real Magic](https://modrinth.com/mod/snow-real-magic)** 

## Installing

Grab a jar from [Releases](https://github.com/Alesrr/terra-diffusion-compat/releases) and copy
it into your `mods/` folder.

| Your machine                | Backend      | Download                          | Extra setup                     |
|-----------------------------|--------------|-----------------------------------|---------------------------------|
| Windows with any modern GPU | DirectML     | `-windows+1.21.1.jar` (~7 MB)     | none                            |
| NVIDIA GPU, any system      | CUDA         | `-cuda+1.21.1.jar` (~550 MB)      | [CUDA + cuDNN](CUDA_INSTALL.md) |
| macOS, or no usable GPU     | CPU / CoreML | `-cpu+1.21.1.jar` (~95 MB)        | none                            |

Then:

1. Drop the jar in your `mods/` folder.
2. Launch once while online so the models download (~2.5 GB). This is separate from the jar
   size above and happens on first run regardless of which jar you picked.
3. Create a world, pick the **Terrain Diffusion** world type, and click **Customize** to set
   `World Scale` (see [Per-world settings](#per-world-settings)).

**Using the CUDA build?** Read [CUDA_INSTALL.md](CUDA_INSTALL.md) first.

## Exploring the world

Run `/td-explore` in game and it prints a clickable link (`http://localhost:19801` by default) that opens an interactive map. Click the map on the left for a detailed view; click that for coordinates in the bottom left. You can use filters for narrowing down search (Detail Maps can take up to about 60 seconds to compute!)

## Configuration

`config/terrain-diffusion-mc.properties`, created on first launch:

```properties
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto" (try GPU first then fall back to CPU).
# "gpu" uses DirectML on the -windows build, or CUDA on the -cuda build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to ~1.5-2 GB. Set to false if you have ~2.5+ GB free for slightly
# faster generation.
inference.offload_models=true

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area (hundreds of blocks), so 16–128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128

# Snow lies deeper the colder the ground is, instead of a uniform single layer everywhere.
snow.depth_scaling=true

# Layer cap where snow is covering vegetation instead of bare ground. Keep this equal to
# Snow Real Magic's accumulation.maxLayers. Ignored when Snow Real Magic is not installed.
snow.max_layers_over_vegetation=6

# Place Terralith's biomes when Terralith is installed.
terralith.enabled=true

# Give Terralith's biomes their own surface blocks. See caveates in compatibility!
terralith.inject_surface_rules=true
```

The last four keys are specific to this fork.

### Terralith compatibility

Terralith places biomes by extending the vanilla multi-noise biome parameter list through Lithostitched, and defines their surfaces by overriding `minecraft:worldgen/noise_settings/overworld`. 

### Per-world settings

For Terrain Diffusion worlds, click **Customize** during world creation and set:

- `World Scale` (integer `1..6`)

Saved with the world. It controls:

- how many real-world meters each block represents (`scale=1` → `30 m/block`, `scale=2` →
  `15 m/block`, and so on)
- max world height for newly created worlds (assumes the tallest point is 10000 m)

2 is a good balance. Use 1 for smaller, more compressed worlds. Lower values lean on the GPU
(the model runs more often); higher values lean on the CPU (taller worlds). 
*I don't suggest using scale 5-6 unless you have a 64core cpu or something!*

## Common issues

**A dynamic link library (DLL) initialization routine failed**

Some older Java versions. Update to the latest Java 21 or higher; the [latest Microsoft OpenJDK 21](https://learn.microsoft.com/en-us/java/openjdk/download) is known to work.

**LoadLibrary failed with error 126** *(CUDA build only)*

Usually a bad CUDA or cuDNN install. See [CUDA_INSTALL.md](CUDA_INSTALL.md).

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

Almost always out of memory — the logs should confirm it. The models need about 2.5 GB of RAM,
so allocate enough.

**Terralith is installed but I see no Terralith biomes**

Check the log at world load. The biome source logs how many Terralith biomes it resolved, and it deliberately falls back to the vanilla palette if any expected biome is missing rather than generating a half-broken world. A version mismatch between Terralith and this fork's biome table is the usual cause.

For anything in the base mod, use
[upstream's issue tracker](https://github.com/xandergos/terrain-diffusion-mc/issues). For the
Terralith, snow, river or lake behaviour, open an issue here.
**Check Issues on HOW TO MAKE AN ISSUE for this fork** (Unless it's code-related)

## Building from source

Needs **JDK 21**. Newer JDKs fail in `buildSrc` with `Unsupported class file major version` because of the Gradle version in use.

An internet connection is required during the build to fetch the pinned model manifest metadata from Hugging Face.

The `-windows` build needs `libs/onnxruntime-dml.jar`, which ships in the repo. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build it yourself.

### Build tasks

Use `Windows` when you want the DirectML build. The old `Dml` names still work as aliases.

| What you want                       | Command                          |
|-------------------------------------|----------------------------------|
| Fabric + NeoForge, Windows/DirectML | `./gradlew buildWindows`         |
| Fabric + NeoForge, CUDA             | `./gradlew buildCuda`            |
| Fabric + NeoForge, CPU/CoreML       | `./gradlew buildCpu`             |
| Every loader and every variant      | `./gradlew buildRelease`         |
| Fabric only, Windows/DirectML       | `./gradlew buildFabricWindows`   |
| Fabric only, CUDA                   | `./gradlew buildFabricCuda`      |
| Fabric only, CPU/CoreML             | `./gradlew buildFabricCpu`       |
| Every Fabric variant                | `./gradlew buildFabricAll`       |
| NeoForge only, Windows/DirectML     | `./gradlew buildNeoForgeWindows` |
| NeoForge only, CUDA                 | `./gradlew buildNeoForgeCuda`    |
| NeoForge only, CPU/CoreML           | `./gradlew buildNeoForgeCpu`     |
| Every NeoForge variant              | `./gradlew buildNeoForgeAll`     |

The direct property form still works:

```
./gradlew build -PuseDml=true
./gradlew build -PuseCuda=true
./gradlew build -PuseCpu=true
./gradlew :fabric:build -PuseDml=true
./gradlew :neoforge:build -PuseDml=true
```

Aliases kept for existing scripts:

```
./gradlew buildDml
./gradlew buildFabricDml
./gradlew buildNeoForgeDml
./gradlew buildAll
```

Jars land under each loader module:

```
fabric/build/libs/
neoforge/build/libs/
```

`./gradlew collectReleaseJars` copies the distributable ones into:

```
build/release/fabric/
build/release/neoforge/
```

To cut a full release, run both:

```
./gradlew buildRelease collectReleaseJars
```

That produces the six jars that go on a release, and takes a few minutes most of it spent on the two CUDA jars:

```
build/release/fabric/
  Fabric-terra_diffusion-1.1.0-windows+1.21.1.jar        7 MB
  Fabric-terra_diffusion-1.1.0-cpu+1.21.1.jar           95 MB
  Fabric-terra_diffusion-1.1.0-cuda+1.21.1.jar         561 MB
build/release/neoforge/
  NeoForge-terra_diffusion-1.1.0-windows+1.21.1.jar      7 MB
  NeoForge-terra_diffusion-1.1.0-cpu+1.21.1.jar         93 MB
  NeoForge-terra_diffusion-1.1.0-cuda+1.21.1.jar       547 MB
```

The jar name comes from `archives_base_name` and `mod_version` in `gradle.properties`. Note that `mod_id` is separate and deliberately still `terrain-diffusion-mc` — it is the resource namespace baked into world saves, so renaming it would break existing worlds.

### Building onnxruntime with DirectML
*I don't suggest treading here unless you know your shit!*

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — Windows 10 1803 or newer
- Visual Studio 2017 toolchain — *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same
- Python 3.10+
- CMake 3.28 or higher

Keep both VS toolchains current. Full details in the
[ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the
[DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run everything from the **Developer Command Prompt for VS 2022**.

```
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and put it in `libs/`.

## Credits and licence

Terrain Diffusion and this mod's entire pipeline are by
[xandergos](https://github.com/xandergos). Original repositories:
[terrain-diffusion](https://github.com/xandergos/terrain-diffusion) and
[terrain-diffusion-mc](https://github.com/xandergos/terrain-diffusion-mc).

Terralith by [Stardust Labs](https://modrinth.com/mod/terralith).

MIT, Copyright (c) 2025 Alexander Goslin & Alesrr [LICENSE.txt](LICENSE.txt).

<img width="1736" height="939" alt="Screenshot 2026-08-29 150459" src="https://github.com/user-attachments/assets/601bfe55-e662-4c5a-9589-5b8f445086c8" />
