# Trademark policy

The code is free. The name is not.

Section 6 of the [Apache License 2.0](LICENSE) grants no trademark rights, and
this file says what that means in practice — including, deliberately, what you
**may** keep. A policy that only forbids leaves the person forking it guessing,
and guessing costs them more than it protects us.

**The marks:** `Radioterapia.AI`, `PhotoID RT`, and their logos.

**The holder:** Henrique Faria Braga, a natural person. *Radioterapia.AI* is a
trade name and a website, not a legal entity.

---

## Why this exists

This is a tool used in clinical rooms, and it carries a declaration that it is
not validated for clinical use. A fork that keeps our name would inherit the
credibility of that declaration without inheriting the work behind it — and the
people who trusted it would have no way to tell the two apart.

So the requirement is narrow and has one purpose: **a reader must be able to
tell whose build they are running.**

---

## What you must change in a fork

If you distribute a modified version, change these:

| What | Where |
|---|---|
| The displayed app name | `app_name` in `res/values*/strings.xml` |
| The application ID | `applicationId` in `app/build.gradle` — Android requires it anyway, or your build cannot coexist with ours |
| The launcher icon | `res/mipmap-*` |
| Our marks in the interface | `learn_more`, `about_title`, `about_website` in `res/values*/strings.xml` |
| The output folder name | `Documents/Radioterapia.AI/…` — see `Marca.kt` |
| Our logo asset | `res/drawable/` |

Most of that is gathered in **one file** on purpose — see
`app/src/main/java/com/radioterapia/ai/branding/Marca.kt`. If you find something
of ours that is not reachable from there, that is a defect on our side; please
open an issue.

You must also keep [NOTICE](NOTICE) intact, and mark the files you changed.
Those are licence requirements (sections 4(d) and 4(b)), not trademark ones.

## What you may keep

- **The Kotlin package namespace `com.radioterapia.ai`.** You do not have to
  rename a hundred files. A package namespace is a technical identifier that no
  user ever sees; it does not tell anyone who made the product, which is the only
  thing this policy is about.
- **Factual references to the origin.** "Forked from PhotoID RT", "based on
  PhotoID RT by Henrique Faria Braga", "compatible with the PhotoID RT
  positioning sheet" — nominative use, describing what is true, is fine and
  always was.
- **The `NOTICE` and `LICENSE` files as they are.** Keeping them is required;
  it is not a trademark use.

## What is not allowed

- Naming, branding or promoting your build as `PhotoID RT` or
  `Radioterapia.AI`, or as anything close enough to be confused with them.
- Using the logos to identify your build, your organisation or your service.
- Suggesting endorsement, review, certification or validation by the author when
  none happened.
- Registering these marks, or confusingly similar ones, anywhere.

## Asking

Anything not covered here, ask — including permission to use the marks for
something this file did not anticipate. Most reasonable requests get a yes.

Contact via **[radioterapia.ai](https://radioterapia.ai)**.
