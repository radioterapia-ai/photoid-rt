# -*- coding: utf-8 -*-
"""
Monta os res/values-XX/plurals.xml.

Separado do montador de strings porque sao dois arquivos de recurso diferentes,
e porque a regra aqui e outra: o que valida um plural nao e a contagem de
chaves, e sim QUAIS categorias de quantidade o idioma usa. Reaproveita o escape
do outro script — escapar e a mesma coisa nos dois, e ter duas copias seria
garantir que uma delas envelheca.
"""
import io, os, json, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from i18n_montar import escapar, RAIZ, SP, IDIOMAS
sys.stdout.reconfigure(encoding="utf-8")

PLU = os.path.join(SP, "pendente", "plurais")
NOMES = ["patient_count_plural", "treatment_count_plural", "simulations_count_plural"]

# Ordem canonica do CLDR. Emitir fora de ordem funciona, mas o arquivo fica
# ilegivel para quem for conferir a mao depois.
ORDEM = ["zero", "one", "two", "few", "many", "other"]

# Categorias que cada idioma usa no CLDR. Escrita aqui para o script PODER
# discordar do que o tradutor gravou: categoria a mais o Android trata como
# recurso morto, categoria a menos faz a frase sair errada numa faixa de
# numeros que ninguem testa — e nos dois casos o defeito e silencioso.
CLDR = {
    "fr": {"one", "many", "other"},
    "de": {"one", "other"},
    "it": {"one", "many", "other"},
    "pl": {"one", "few", "many", "other"},
    "zh": {"other"},
    "ja": {"other"},
    "ko": {"other"},
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "bn": {"one", "other"},
}
# "many" no frances e no italiano so pega numeros muito grandes (milhoes) e o
# Android nao a exige. Aceita-se com ou sem ela.
OPCIONAIS = {"fr": {"many"}, "it": {"many"}}

CABECALHO = """<?xml version="1.0" encoding="utf-8"?>
<!--
    Plurais - %s

    As CATEGORIAS de quantidade sao por idioma, definidas pelo CLDR, e nao
    acompanham o portugues. Este arquivo usa: %s.

    Categoria de menos faz a frase sair errada numa faixa de numeros; categoria
    de mais vira recurso morto. Conferir no CLDR antes de editar.
-->
<resources>
"""


def carregar(lang):
    p = os.path.join(PLU, "%s.json" % lang)
    if not os.path.exists(p):
        return None, ["arquivo ausente"]
    txt = io.open(p, encoding="utf-8").read().strip()
    if txt.startswith("```"):
        txt = txt.split("\n", 1)[1].rsplit("```", 1)[0]
    try:
        d = json.loads(txt)
    except Exception as e:
        return None, ["JSON invalido: %s" % e]
    return d, []


def conferir(lang, d):
    erros, avisos = [], []
    esperado = CLDR[lang]
    opcional = OPCIONAIS.get(lang, set())
    for nome in NOMES:
        if nome not in d:
            erros.append("%s: plural ausente" % nome)
            continue
        cats = set(d[nome].keys())
        if "other" not in cats:
            erros.append("%s: falta a categoria obrigatoria 'other'" % nome)
        invalidas = cats - set(ORDEM)
        if invalidas:
            erros.append("%s: categoria inexistente %s" % (nome, sorted(invalidas)))
        sobra = cats - esperado
        if sobra:
            erros.append("%s: categoria que %s nao usa: %s" % (nome, lang, sorted(sobra)))
        falta = (esperado - opcional) - cats
        if falta:
            erros.append("%s: falta categoria do CLDR: %s" % (nome, sorted(falta)))
        for cat, txt in d[nome].items():
            if not txt.strip():
                erros.append("%s/%s: vazio" % (nome, cat))
            # "1" fixo numa categoria que pega mais de um numero produz
            # "1 paciente" quando o valor e 21.
            if cat not in ("one", "zero", "two") and "%d" not in txt:
                avisos.append("%s/%s: sem %%d (%r)" % (nome, cat, txt[:40]))
    return erros, avisos


def gravar(lang, d):
    cats_usadas = sorted(
        {c for nome in NOMES for c in d.get(nome, {})},
        key=lambda c: ORDEM.index(c) if c in ORDEM else 99)
    linhas = [CABECALHO % (lang, ", ".join(cats_usadas))]
    for nome in NOMES:
        linhas.append('    <plurals name="%s">\n' % nome)
        itens = d[nome]
        for cat in ORDEM:
            if cat in itens:
                linhas.append('        <item quantity="%s">%s</item>\n'
                              % (cat, escapar(itens[cat].strip())))
        linhas.append("    </plurals>\n")
    linhas.append("</resources>\n")
    pasta = os.path.join(RAIZ, "app", "src", "main", "res", "values-%s" % lang)
    os.makedirs(pasta, exist_ok=True)
    io.open(os.path.join(pasta, "plurals.xml"), "w", encoding="utf-8", newline="\n").write(
        "".join(linhas))


def main():
    modo = sys.argv[1] if len(sys.argv) > 1 else "--conferir"
    prontos = {}
    for lang in IDIOMAS:
        d, fora = carregar(lang)
        erros, avisos = (fora, [])
        if d:
            e2, avisos = conferir(lang, d)
            erros = fora + e2
        marca = "OK " if not erros else "ERRO"
        cats = sorted({c for n in NOMES for c in (d or {}).get(n, {})},
                      key=lambda c: ORDEM.index(c) if c in ORDEM else 99) if d else []
        print("[%s] %-3s categorias: %-32s %d erro(s), %d aviso(s)"
              % (marca, lang, ",".join(cats) or "-", len(erros), len(avisos)))
        for x in erros[:6]:
            print("        ERRO  %s" % x)
        for x in avisos[:4]:
            print("        aviso %s" % x)
        if not erros and d:
            prontos[lang] = d
    print()
    print("prontos: %s" % (", ".join(sorted(prontos)) or "(nenhum)"))
    if modo == "--gravar":
        for lang, d in prontos.items():
            gravar(lang, d)
        print("gravados %d plurals.xml" % len(prontos))
    return 0


if __name__ == "__main__":
    sys.exit(main())
