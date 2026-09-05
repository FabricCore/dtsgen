plugins {
    // A library first: `java-library` for consumers that embed the generator, `application`
    // for the CLI that drives it.
    `java-library`
    application
    `maven-publish`
}

// Maven coordinates for consumers: ws.siri:dtsgen:<version>. Bump the version here on a
// release; `./gradlew publishToMavenLocal` is enough to try it from another project.
group = "ws.siri"
version = "0.1.1"

repositories {
    mavenCentral()
}

dependencies {
    // Neither dependency appears in the public API, so neither is forced on consumers'
    // compile classpaths: ASM is how bytecode is read, Gson only parses dtsgen.jsonc.
    implementation(libs.asm)
    implementation(libs.gson)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

// The library jar is `dtsgen.jar`; the runnable bundle below is `dtsgen-cli.jar`.
base.archivesName = "dtsgen"

application {
    mainClass = "ws.siri.dtsgen.cli.Main"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // The config lives at the repo root, not in this subproject, and the CLI resolves both its
    // default `./dtsgen.jsonc` and any relative --args path against the working directory.
    workingDir = rootProject.projectDir
}

tasks.javadoc {
    // The exported package is the documented one; the rest is an implementation detail.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("library") {
            artifactId = "dtsgen"
            from(components["java"])
        }
    }
}

// A single self-contained jar, so the CLI can be run as `java -jar dtsgen-cli.jar config.json`
// without a Gradle-managed classpath. The library jar stays dependency-free for consumers who
// resolve ASM and Gson themselves.
tasks.register<Jar>("fatJar") {
    archiveFileName = "dtsgen-cli.jar"
    manifest { attributes("Main-Class" to "ws.siri.dtsgen.cli.Main") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // A dependency's module descriptor would shadow this module's own; the fat jar runs
        // from the classpath anyway, where descriptors are ignored.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**",
                "module-info.class")
    }
}

publishing {
  repositories {
    maven {
      name = "siriReposilite"
      url = uri("https://maven.siri.ws/releases")
      credentials(PasswordCredentials::class)
      authentication {
        create<BasicAuthentication>("basic")
      }
    }
  }
}

