# DtsGenerator

> [!IMPORTANT]
> This repo is fully written by Claude. If you found a bug, please open an issue
> instead of opening a PR, I will not be able to review your code and I do not
> trust your code.

Generates TypeScript `.d.ts` declarations from Java bytecode, so `jscore` scripts written in
vanilla JS with JSDoc get completion and type checking against Minecraft, the JDK, and any mod
jar — without importing anything or annotating `Java.type` calls.

```js
const ItemStack = Java.type("net.minecraft.world.item.ItemStack");   // inferred
const stack = ItemStack.EMPTY;

/** @param {net.minecraft.world.item.ItemStack} s */
export function count(s) { return s.getCount(); }
```

Classes are read, never loaded, so a jar needs no classpath and no runnable JVM.

## Running

```bash
./gradlew run                              # uses ./dtsgen.jsonc
./gradlew run --args="other.jsonc"         # any other config

./gradlew fatJar                           # -> app/build/libs/dtsgen-cli.jar
java -jar app/build/libs/dtsgen-cli.jar dtsgen.jsonc
```

The config defaults to `./dtsgen.jsonc`, and paths inside it resolve against its own directory,
so it runs from anywhere. `run` works from the repo root, not the `app` subproject.

## Config

`dtsgen.jsonc` names paths specific to one machine, so it is gitignored; copy the checked-in
`dtsgen.example.jsonc` and edit it. Paths take `${VAR}` from the environment (an unset one is an
error, not an empty string), `~` from your home directory, and anything relative from the config
file's own directory. Comments (`//`, `/* */`, `#`) are allowed; trailing commas are not.

| key | meaning |
|---|---|
| `out` | output root; gets `full/` and `java.d.ts` |
| `sources` | jars, `.jmod`s or directories to emit |
| `classpathOnly` | read to resolve supertypes and signatures, never emitted |
| `scope.exclude` | package globs to skip; references to them become `any` |
| `scope.opaque` | emitted with no members, severing the reference closure through them |
| `jsdoc` | `params` or `none` |
| `registry` | `full`, or `used` to prune to FQNs found in `scriptRoots` |

Adding a jar is an entry in `sources`, never a code change:

```json
{ "jar": "${JAVA_HOME}/jmods/java.base.jmod" },
{ "jar": "~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar" },
{ "dir": "build/classes/java/main" }
```

`.jmod`s work because they are zips; that is how the JDK gets in. Globs use `**` to span dots,
`*` within one segment. Output is keyed by fully-qualified name, so jars merge into one `full/`
tree and any one of them can be regenerated alone.

## Consuming it

Both outputs are needed: `full/<package>/<Class>.d.ts`, one ES module per top-level class with
its nested types folded in, and `java.d.ts`, holding the package-mirroring aliases, the
`Java.type` registry and the `Java` object. `jsconfig.json` needs the registry in `files`, and
`skipLibCheck`:

```json
{
  "compilerOptions": { "skipLibCheck": true, ... },
  "files": ["jscore.d.ts", "dist/java.d.ts"],
  "include": ["**/*.js"],
  "exclude": ["dist"]
}
```

`skipLibCheck` is **required**, not an optimisation: Java permits overrides and inherited-member
conflicts that TypeScript rejects — ~2,000 of them — all harmless inside a `.d.ts`.
`exclude: ["dist"]` keeps the `**/*.js` glob out of the generated tree, which `java.d.ts`
already pulls in.

## As a library

The CLI is a thin front end. `./gradlew publishToMavenLocal` installs `ws.siri:dtsgen:0.1.0`.

```java
GeneratorConfig config = GeneratorConfig.builder()
        .outputDirectory(Path.of("scripts/dist"))
        .addSource(Path.of("libs/minecraft-merged.jar"))
        .scope(GeneratorConfig.Scope.of(List.of(), List.of("sun.**", "jdk.**")))
        .build();

Declarations declarations = new DtsGenerator(config).generate();
GenerationResult result = declarations.writeTo(config.outputDirectory());
```

`GeneratorConfig.fromJson(path)` loads the same `dtsgen.jsonc`; `toBuilder()` derives a variant.
Nothing is rendered until asked for, so the output need not go to disk:

```java
declarations.registry();                                    // the registry file's text
declarations.module("net.minecraft.core.BlockPos");         // one module's text
declarations.writeTo((path, text) -> zip.add(path, text));  // stream every file to a sink
```

A sink gets one file at a time and the text is not retained, so peak memory stays flat over a
7,000-file run. `Declarations` also exposes the counts the CLI prints.

The API is the `ws.siri.dtsgen` package alone — `DtsGenerator`, `GeneratorConfig`,
`Declarations`, `DeclarationSink`, `GenerationResult`, `DtsGenerationException`; the module
descriptor does not export `ws.siri.dtsgen.internal`. A bad config or missing source throws
`DtsGenerationException`, I/O failures `IOException`.

## The mapping

- `long` becomes `number`; values beyond 2^53 lose precision
- Java arrays are `JavaArray<T>`, not `T[]` — host objects with no `map` or `push`
- varargs become TS rest parameters, since GraalJS expands them at the call site
- `String`, `CharSequence` and the boxed primitives map to JS primitives, as Graal hands back
- wildcards have no TS equivalent: `? extends T` is `T`, `? super T` and `?` are `any`
- only public members are emitted, matching what `HostAccess.ALL` exposes
- a field and method sharing a name (`Vec3.x`, `x()`) become `number & { (): number }`
- Java interfaces get an explicit value side; without it `Java.type` on one silently returns
  `any`, hidden by `skipLibCheck`. 674 are affected, 282 with statics
- getters stay methods: no `.foo` for `getFoo()`, absent `js.nashorn-compat=true`

Parameter names are real — `setPos(pos: Vec3)`, not `setPos(a0: Vec3)` — from the
`MethodParameters` attribute, falling back to the `LocalVariableTable` by JVM slot — **97.8%**
real names on the workload below. The rest are abstract, native and interface methods, which
have no body to read.

## Measured: Minecraft 26.2 + java.base + fabric-loader

19,148 classes scanned, 9,313 types across 7,282 files (7.4 MB) plus a 3.9 MB registry, in 2.2s.
In tsserver, on a 134-line script using 12 Java types: 4.6s project load (once per session),
52ms recheck, 8ms member completion, 34ms `Java.type("` completion over 9,313 names, 39ms hover,
690 MB RSS. Rechecks run when typing pauses, not per keystroke.

If load or memory becomes a problem, `registry: "used"` prunes to the classes your scripts name
— 19ms rechecks, 330 MB — at the cost of regenerating when you reach for a new class.
