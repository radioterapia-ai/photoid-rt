# Contributing

This is a small project with a single maintainer, built alongside clinical
practice. Contributions are welcome, and so is a fork that goes its own way.

## Before you write code

Read [CLAUDE.md](CLAUDE.md). It is the permanent context of the project:
product constraints that are settled, storage conventions that would break
installed bases if changed, the validation ritual, and the footguns that have
already cost builds. Most of it is in Portuguese — that is the language the
project was built in.

A few constraints are **settled and not reopened without an explicit request**,
because each was decided once with a reason:

- **Clicks cost.** Users reject extra steps. An optional field does not become
  mandatory, and a confirmation is not added where the answer can be inferred.
- **The app is universal.** It serves any radiation therapy service, not the one
  it came from. No fixed lists, flows or vocabulary belonging to one clinic.
- **Sync is not the app's job.** Photographs and PDFs reach the server through
  FileSync, which is external. No upload queues, no sync indicators.
- **No microphone.** Voice capture was implemented and then removed: the promise
  that audio never leaves the device only holds on Android 13+, and the app runs
  on tablets a clinic buys without version control. Do not reintroduce it unless
  the promise holds on every target version.
- **We are not the authors of the models.** ML Kit does the recognition. Never
  write "our algorithm" or "our AI" — in code, in commits, or in the interface.

## The gate

```
./gradlew check
python docs/scripts_validacao.py     # run from app/src/main
```

`check` runs tests and lint together. Lint treats `MissingTranslation` and
`ExtraTranslation` as **errors**: every user-visible string must exist in all
twelve languages, or the build fails. That is the safety net.

`scripts_validacao.py` runs the structural checks — balanced braces, orphaned
functions and fields, string parity and escaping, `R.id` per settings group
against its layout, and the one that keeps network primitives out of files that
have no business making network calls.

Both must be green. Every check in them was born from a broken build.

## Strings

Every user-visible string goes to `res/values*/strings.xml` in **all twelve**
languages. Apostrophes are escaped (`\'`), `&` becomes `&amp;`, and plural
categories follow CLDR per language — Polish needs `one/few/many/other`,
Japanese only `other`, Arabic all six, and `pt`/`es`/`fr`/`it` need `many`.

Escaping is done when the file is generated, never by hand and never by the
translator. The Portuguese base barely uses apostrophes; Italian alone has 144.

## Commits

Portuguese or English, both fine. Say **why**, not just what — the commit log of
this project is documentation, and it is the reason a decision made months ago
can still be understood. Dates survive; names do not need to.

## Pull requests

Small and focused beats large and complete. Say what you tested and on what — a
real tablet matters more than an emulator here, and "not tested on hardware" is
a fine thing to write.

## Contributor licence

By opening a pull request you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the same terms as the project. Keep the copyright
line as it is; the `NOTICE` file is where attribution is recorded.

## Reporting problems

See the README for what to include in a bug report, and
[SECURITY.md](SECURITY.md) for anything that touches patient data or signing.
