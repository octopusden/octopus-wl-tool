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
mvn -P quality package                               # ktlint, detekt, tests, coverage floor
mvn -P quality generate-test-sources ktlint:format   # fix what ktlint can fix itself
```

`ktlint` reads `.editorconfig` (`intellij_idea` code style, `max_line_length = 140`), `detekt`
reads `detekt.yml` on top of its own defaults, and `jacoco` fails the build under **70%** line
coverage per module — the floor `gradle-quality-plugin` uses for the Gradle repositories. The
rules follow `octopus-base/docs/Octopus Kotlin Style Guide.md`.

Violations that existed when detekt was introduced are in `*/detekt-baseline.xml`; new code must
not add entries there. Regenerate with
`mvn -P quality generate-test-sources detekt:create-baseline`.

### Mutation testing

```bash
mvn -P quality org.pitest:pitest-maven:mutationCoverage   # report in */target/pit-reports
```

On demand only — not bound to a phase, not part of `gate/merge`. It is scoped to the validators,
the filters and the source scanner, where the logic is predicates and thresholds; report
rendering, config holders and IO glue are left out. The mutator set is the PIT defaults minus
`VOID_METHOD_CALLS`, which in Kotlin mostly removes compiler-generated calls (`Intrinsics` null
checks, `closeFinally` in `use` blocks) and logging, and produces mutants nothing can kill.

Current score: `validation` 73% killed / 75% test strength, `wl-tool` 67% / 78%. Read it as a
map of untested behaviour, not as a number to raise.

All of this lives in the `quality` profile, not in the default build: the plugins need a newer
JVM than the JDK 8 the release runs on, so CI activates the profile in its own JDK 11 job.

## CI

All three call reusable workflows from `octopus-base`, pinned to `v3.0.0`.

`Merge Gate` runs on every pull request and aggregates the three gates into one check,
`gate/merge` — the only check branch protection needs to require:

| Job | What it runs |
|---|---|
| `build` | `Maven Compile & UT`: `mvn package` on JDK 8 |
| `quality` | `Quality Gates`: `mvn package -P quality` on JDK 11 |
| `security` | `Security Reports`: CodeQL and Trivy (dependency-check is off - it is Gradle bound) |

The three are `workflow_call` workflows, so they run once per pull request, through the gate.
`Security Reports` additionally runs nightly.

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
