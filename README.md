# octopus-wl-tool

White labeling validation tool. It checks project sources or a built distribution against the
white labeling rules (which live in `wl-conf`, never in this repository) and reports forbidden
tokens, file names and file content.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `validation` | `org.octopusden.octopus.tools.wl:validation` | validators and file filters |
| `wl-tool` | `org.octopusden.octopus.tools.wl:wl-tool` | validation entry point and report generation |
| `gradle-scripts` | — | Gradle tasks (`WLValidatorTask`) that consume both artifacts from Maven Central |

## Build

```bash
mvn package
```

CI (`Maven Compile & UT`) builds on JDK 8; the build and the tests also pass on JDK 11 and 21.

## Quality gates

```bash
mvn -P quality package            # ktlint + detekt, with the tests
mvn -P quality generate-test-sources ktlint:format   # fix what ktlint can fix itself
```

`ktlint` reads `.editorconfig` (`intellij_idea` code style, `max_line_length = 140`), `detekt`
reads `detekt.yml` on top of its own defaults. The rules follow
`octopus-base/docs/Octopus Kotlin Style Guide.md`.

Violations that existed when detekt was introduced are in `*/detekt-baseline.xml`; new code must
not add entries there. Regenerate with
`mvn -P quality generate-test-sources detekt:create-baseline`.

The linters are in the `quality` profile, not in the default build: they need a newer JVM than
the JDK 8 the release runs on. CI activates the profile in the `Quality Gates` workflow on JDK 11.

## Release

`Actions` → `Maven Release` → `Run workflow`. It calls
`octopus-base/.github/workflows/common-java-maven-release.yml` in the `public` flow: the version
is calculated from the latest `vX.Y.Z` tag with a patch increment, published to Maven Central,
tagged, and released. Registration in `octopus-release-log` follows from
`Check for artifact and register release`, which polls Central for the `wl-tool` jar.

## Maven Central metadata

Metadata validation is inherited from `octopus-parent` — `maven-enforcer-plugin`, execution
`require-central-metadata`, bound to `validate` — so no local PR validator is required. That rule
only checks the fields are non-empty, so `description`, `url` and `scm` are declared in the root
pom: inherited, they would carry octopus-parent's own description and point at
`github.com/octopusden/octopus-parent` with the module path appended.
