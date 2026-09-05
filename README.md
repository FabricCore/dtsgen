# DtsGenerator

[Git](https://github.com/FabricCore/dtsgenerator) | [Maven](https://maven.siri.ws/#/releases/ws/siri/dtsgen)

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
| `sources` | jars, `.jmod`s or directories to emit; jars are recursed into |
| `classpathOnly` | read to resolve supertypes and signatures, never emitted |
| `scope.exclude` | package globs to skip; references to them become `any` |
| `scope.opaque` | emitted with no members, severing the reference closure through them |
| `jsdoc` | `params` or `none` |
| `registry` | `full`, or `used` to prune to FQNs found in `scriptRoots` |

Adding a jar is an entry in `sources`, never a code change:

```json
{ "jar": "${JAVA_HOME}/jmods/java.base.jmod" },
{ "jar": "~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar" },
{ "jar": "../Mods/fabric-api-0.159.0+26.2.jar" },
{ "dir": "build/classes/java/main" }
```

`.jmod`s work because they are zips; that is how the JDK gets in. Nested jars are scanned too,
which is what makes a mod jar work: fabric-api carries no classes of its own, only one jar per
module under `META-INF/jars/`. A `dir` may likewise hold jars as well as loose class files, so
`{ "dir": "../Mods" }` takes a whole mods folder -- wholesale, including anything else in it,
which is what `scope.exclude` is for. Globs use `**` to span dots, `*` within one segment.
Output is keyed by fully-qualified name, so jars merge into one `full/` tree and any one of them
can be regenerated alone.

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

The `lib` must reach ES2023 — `esnext` in the example project — since a List and a Java array
are typed against the real `Array`. `skipLibCheck` is **required**, not an optimisation: Java permits overrides and inherited-member
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
- Java arrays are `JavaArray<T>`: an array as far as JS is concerned — `Array.isArray`, an
  index, `map`, `sort` — but a fixed-length one, so `push` and `splice` throw and are absent
- varargs become TS rest parameters, since GraalJS expands them at the call site
- `String`, `CharSequence` and the boxed primitives map to JS primitives, as Graal hands back
- wildcards have no TS equivalent: `? extends T` is `T`, `? super T` and `?` are `any`
- only public members are emitted, matching what `HostAccess.ALL` exposes
- a field and method sharing a name (`Vec3.x`, `x()`) become `number & { (): number }`
- Java interfaces get an explicit value side; without it `Java.type` on one silently returns
  `any`, hidden by `skipLibCheck`. 674 are affected, 282 with statics
- a functional interface parameter is `JavaFn<T>`, which takes a JS function as readily as an
  instance, because GraalJS converts one at the call site and TypeScript has no such conversion
  of its own; the interface itself gains a call signature, since an instance of one is
  executable from JS too. `NoInfer` inside `JavaFn` keeps a lambda's types inferring, so it
  needs TypeScript 5.4 or newer
- a parameter typed as one of the *class's* type variables is wrapped too, which is what makes
  `SomeEvents.EVENT.register(...)` take a lambda: `Event<T>.register(T)` names no interface at
  all. Wrapping regardless is free, since `JavaFn` falls through wherever `T` is not a function.
  A method's own type variable is left bare -- the receiver has already fixed a class variable
  before an argument is checked, whereas `<T> T requireNonNull(T)` infers `T` *from* that
  argument, and TypeScript infers poorly through a conditional type
- a `List` is an array to JS as well, and the emitted interface says so: `length`, `list[0]`,
  and the `Array.prototype` methods, `push` and `splice` included. Where a Java member has the
  same name it wins, at runtime and here — `sort`, `forEach`, `indexOf` and `lastIndexOf` on a
  List are Java's, with Java's signatures
- so a `List<T>` or a `JavaArray<T>` satisfies `readonly T[]`, `ArrayLike<T>` and `Iterable<T>`,
  but not a mutable `T[]`: Java's `sort` takes a required comparator and returns void, which is
  also what it does at runtime, so `list.sort()` would throw. `Java.from(list)` or `[...list]`
  copies into a real array where an annotation insists on one
- whatever Java iterates, JS iterates: `Iterable` and `Iterator` carry `[Symbol.iterator]`, so
  `for..of` and spread type-check on any of them. A `Stream` is the exception — it is not
  iterable, so `.toList()` first
- a parameter also takes the JS value GraalJS converts from: an array for a `List`, `Collection`
  or `Iterable`, an object for a `Map<String, V>`, a two-element array for a `Map.Entry`, a
  `Date` for an `Instant` or any of the `java.time` shapes, a buffer for a `byte[]`. The
  conversion recurses through type arguments and so does the rendering, so
  `Map<String, List<String>>` takes `{ a: ["x"] }`. The one exception is a type variable:
  `List<Runnable>.addAll` will not take an array of functions, because widening `E` to a
  function would cost inference everywhere else
- a `Set` takes none of that, though the conversion looks like it succeeds: a guest object
  satisfies any interface at all through a dynamic proxy, and then every call on it throws
- `Foo.class` is a `Class<Foo>`, on a class and an interface alike, and stays absent on an
  instance — which is what GraalJS does: `class` is a member of the host type, an instance has
  `getClass()` and nothing else. A class declares it as a static; an interface gets it from the
  `Java.type` registry instead, because an interface's statics live in a TS namespace and no
  namespace member may be named `class`, so an interface imported rather than looked up lacks it
- getters stay methods: no `.foo` for `getFoo()`, absent `js.nashorn-compat=true`

Parameter names are real — `setPos(pos: Vec3)`, not `setPos(a0: Vec3)` — from the
`MethodParameters` attribute, falling back to the `LocalVariableTable` by JVM slot — **97.8%**
real names on the workload below. The rest are abstract, native and interface methods, which
have no body to read.

## Measured: Minecraft 26.2 + java.base + fabric-loader

19,270 classes scanned, 9,329 types across 7,303 files (8.0 MB) plus a 4.0 MB registry, in 2.2s.
In tsserver, on a 134-line script using 12 Java types: 4.6s project load (once per session),
52ms recheck, 8ms member completion, 34ms `Java.type("` completion over 9,313 names, 39ms hover,
690 MB RSS. Rechecks run when typing pauses, not per keystroke.

If load or memory becomes a problem, `registry: "used"` prunes to the classes your scripts name
— 19ms rechecks, 330 MB — at the cost of regenerating when you reach for a new class.
