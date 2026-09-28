# -*- coding: utf-8 -*-
"""
Gera o THIRD_PARTY.md a partir do app/build.gradle.

POR QUE GERADO, E NAO ESCRITO
Lista de dependencia escrita a mao envelhece no dia seguinte: alguem sobe uma
versao e o documento continua anunciando a anterior. Aqui as coordenadas e as
VERSOES saem do arquivo de build real.

O QUE NAO DA PARA EXTRAIR, E POR ISSO E DECLARADO
A licenca nao vem confiavelmente do POM sem um plugin de licencas, e acrescentar
plugin ao build para gerar um documento seria pagar caro por pouco. Entao a
licenca mora na tabela LICENCAS abaixo, e o script REPROVA se aparecer
dependencia sem entrada — que e a garantia real: dependencia nova nao passa
despercebida, ela quebra a geracao.

Uso:
    python tools/gerar_third_party.py            # confere e relata
    python tools/gerar_third_party.py --gravar   # regrava THIRD_PARTY.md
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")


def raiz_do_projeto():
    """Acha a raiz pela MARCA (app/build.gradle + docs/), nunca por caminho fixo."""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if (os.path.exists(os.path.join(d, "app", "build.gradle"))
                and os.path.isdir(os.path.join(d, "docs"))):
            return d
        pai = os.path.dirname(d)
        if pai == d:
            raise SystemExit("Nao achei a raiz do projeto (app/build.gradle + docs/).")
        d = pai


RAIZ = raiz_do_projeto()

# grupo:artefato -> (licenca, titular, observacao)
LICENCAS = {
    "androidx.core":            ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.appcompat":       ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.constraintlayout":("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.recyclerview":    ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.viewpager2":      ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.exifinterface":   ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.documentfile":    ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.security":        ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.camera":          ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.room":            ("Apache-2.0", "The Android Open Source Project", ""),
    "androidx.work":            ("Apache-2.0", "The Android Open Source Project",
                                 "fila da sincronizacao, sobrevive a reinicio"),
    "commons-net":              ("Apache-2.0", "The Apache Software Foundation",
                                 "FTP e FTPS na sincronizacao"),
    "com.github.mwiede":        ("Revised BSD (3-Clause)", "Atsuhiko Yamanaka, ymnk e Matthias Wiedemann",
                                 "SFTP. Fork mantido do JSch, que parou em 2018"),
    "com.google.android.material": ("Apache-2.0", "Google LLC", ""),
    "org.jetbrains.kotlinx":    ("Apache-2.0", "JetBrains s.r.o.", ""),
    "com.hierynomus":           ("Apache-2.0", "Jeroen van Erp", "cliente SMB"),
    "com.github.bumptech.glide":("BSD-2-Clause e Apache-2.0", "Google Inc. e Bump Technologies Inc.", ""),
    "com.google.zxing":         ("Apache-2.0", "ZXing Authors", ""),
    "com.opencsv":              ("Apache-2.0", "OpenCSV contributors", "leitura da base de pacientes"),
    "com.journeyapps":          ("Apache-2.0", "Journey Mobile, Inc.", ""),
    "com.google.mlkit":         ("Termos do ML Kit (proprietaria, uso gratuito)", "Google LLC",
                                 "NAO e software livre. Embarcada no APK como biblioteca nativa."),
    "com.google.android.gms":   ("Termos das APIs do Google (proprietaria, uso gratuito)", "Google LLC",
                                 "NAO e software livre. Resolvida pelo Google Play services."),
    "junit":                    ("Eclipse Public License 1.0", "JUnit contributors", "so em teste"),
    "org.json":                 ("JSON License", "JSON.org", "so em teste"),
}

SO_EM_TESTE = ("testImplementation", "androidTestImplementation")
COORD = re.compile(r"""^\s*(\w+)\s+["']([\w.\-]+):([\w.\-]+):([^"']+)["']""")


def variaveis(texto):
    """def camerax_version = "1.3.1"  ->  {camerax_version: 1.3.1}"""
    return dict(re.findall(r'def\s+(\w+)\s*=\s*["\']([^"\']+)["\']', texto))


def coletar():
    texto = io.open(os.path.join(RAIZ, "app", "build.gradle"), encoding="utf-8").read()
    vars_ = variaveis(texto)
    itens, faltando = [], []
    for linha in texto.splitlines():
        m = COORD.match(linha)
        if not m:
            continue
        config, grupo, artefato, versao = m.groups()
        versao = re.sub(r"\$\{?(\w+)\}?", lambda x: vars_.get(x.group(1), x.group(0)), versao).strip()
        chave = next((k for k in LICENCAS if grupo == k or grupo.startswith(k + ".")), None)
        if chave is None:
            faltando.append("%s:%s" % (grupo, artefato))
            continue
        lic, titular, obs = LICENCAS[chave]
        itens.append({
            "grupo": grupo, "artefato": artefato, "versao": versao,
            "licenca": lic, "titular": titular, "obs": obs,
            "teste": config in SO_EM_TESTE,
        })
    return itens, faltando


def montar(itens):
    distribuidos = [i for i in itens if not i["teste"]]
    de_teste = [i for i in itens if i["teste"]]

    linhas = [
        "# Third-party components",
        "",
        "> **Generated from `app/build.gradle` by `tools/gerar_third_party.py`.**",
        "> Do not edit by hand — a hand-written dependency list is out of date the",
        "> day after someone bumps a version. The generator fails if a dependency",
        "> has no declared licence, so a new one cannot slip through unnoticed.",
        "",
        "The authoritative attribution notice is [NOTICE](NOTICE); section 4(d) of",
        "the [Apache License 2.0](LICENSE) requires it to travel with every",
        "redistribution. This file is the detailed version of the same list.",
        "",
        "## Not free software, and shipped inside the APK",
        "",
        "**Google ML Kit** is proprietary. It is not redistributed as source here —",
        "it is resolved as a binary dependency at build time — and the bundled",
        "variants embed a native recognition engine of roughly **57 MB across four",
        "ABIs**, which is most of the APK's size.",
        "",
        "This matters beyond attribution: a combined work that bundles a proprietary",
        "native library is not distributable under a copyleft licence without an",
        "additional permission. It is one reason this project is Apache-2.0.",
        "",
        "**Declared position of the author:** Google publishes ML Kit as a",
        "general-purpose developer SDK. It does not declare it a medical device, does",
        "not intend it for clinical use, and publishes no specific statement either",
        "way. We record the absence rather than attribute a position nobody wrote.",
        "",
        "## Distributed in the APK",
        "",
        "| Component | Version | Licence | Holder |",
        "|---|---|---|---|",
    ]
    for i in sorted(distribuidos, key=lambda x: (x["grupo"], x["artefato"])):
        nome = "`%s:%s`" % (i["grupo"], i["artefato"])
        if i["obs"]:
            nome += "<br><sub>%s</sub>" % i["obs"]
        linhas.append("| %s | %s | %s | %s |" % (nome, i["versao"], i["licenca"], i["titular"]))

    linhas += ["", "## Test only — not distributed", "",
               "| Component | Version | Licence | Holder |", "|---|---|---|---|"]
    for i in sorted(de_teste, key=lambda x: (x["grupo"], x["artefato"])):
        linhas.append("| `%s:%s` | %s | %s | %s |"
                      % (i["grupo"], i["artefato"], i["versao"], i["licenca"], i["titular"]))

    linhas += [
        "",
        "## Toolchain",
        "",
        "Gradle 8.13 · Android Gradle Plugin 8.13.2 · Kotlin 1.9.20 · JDK 21 ·",
        "compileSdk 34 · minSdk 24. The Gradle wrapper JAR is versioned so a clone",
        "builds without installing a matching Gradle by hand.",
        "",
    ]
    return "\n".join(linhas)


itens, faltando = coletar()
print("dependencias lidas: %d (%d distribuidas, %d so em teste)"
      % (len(itens), len([i for i in itens if not i['teste']]),
         len([i for i in itens if i['teste']])))
if faltando:
    for f in sorted(set(faltando)):
        print("SEM LICENCA DECLARADA: " + f)
    raise SystemExit("Acrescente a licenca em LICENCAS antes de gerar.")

conteudo = montar(itens)
destino = os.path.join(RAIZ, "THIRD_PARTY.md")
if "--gravar" in sys.argv:
    io.open(destino, "w", encoding="utf-8", newline="\n").write(conteudo)
    print("gravado: " + destino)
else:
    atual = io.open(destino, encoding="utf-8").read() if os.path.exists(destino) else ""
    print("EM DIA" if atual == conteudo else "DESATUALIZADO — rode com --gravar")
