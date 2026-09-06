# -*- coding: utf-8 -*-
"""
Confere UM bloco traduzido contra a fonte, sem esperar o idioma inteiro.

Existe para pegar erro de formato na primeira vez, e nao na vigesima. O
montador so confere quando os sete blocos do idioma estao la; um engano de
placeholder repetido em vinte arquivos custa vinte correcoes.

Uso: python conferir_bloco.py <lang>__<bloco>.json [...]
"""
import io, os, re, sys, json, collections

def raiz_do_projeto():
    """
    Acha a raiz subindo ate a MARCA, e nao por caminho fixo.

    Marca e uma condicao verificavel no destino: a pasta que tem `app/build.gradle`
    e `docs/` lado a lado. Codigo que localiza pasta por caminho fixo quebra na
    primeira reorganizacao; por marca, sobrevive — e caminho absoluto tambem nao
    pode ir para um repositorio publico.
    """
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if (os.path.exists(os.path.join(d, "app", "build.gradle"))
                and os.path.isdir(os.path.join(d, "docs"))):
            return d
        pai = os.path.dirname(d)
        if pai == d:
            raise SystemExit("Nao achei a raiz do projeto (app/build.gradle + docs/).")
        d = pai

sys.stdout.reconfigure(encoding="utf-8")

RAIZ = raiz_do_projeto()
FONTE = os.path.join(RAIZ, "docs", "i18n", "fonte")
TRAD = os.path.join(RAIZ, "docs", "i18n", "pendente", "traducoes")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

total = 0
for nome in sys.argv[1:]:
    lang, bloco = os.path.basename(nome).replace(".json", "").split("__")
    fonte = {d["k"]: d["pt"] for d in
             json.load(io.open(os.path.join(FONTE, "fonte_%s.json" % bloco), encoding="utf-8"))}
    caminho = os.path.join(TRAD, "%s__%s.json" % (lang, bloco))
    if not os.path.exists(caminho):
        print("[ERRO] %-14s arquivo ausente" % nome); total += 1; continue
    try:
        dados = json.load(io.open(caminho, encoding="utf-8"))
    except Exception as e:
        print("[ERRO] %-14s JSON invalido: %s" % (nome, e)); total += 1; continue

    trad = {d["k"]: d["v"] for d in dados if isinstance(d, dict) and "k" in d and "v" in d}
    erros = []
    falta = [k for k in fonte if k not in trad]
    sobra = [k for k in trad if k not in fonte]
    if falta: erros.append("faltam %d: %s" % (len(falta), falta[:5]))
    if sobra: erros.append("sobram %d: %s" % (len(sobra), sobra[:5]))
    if len(dados) != len(fonte):
        erros.append("%d entradas para %d chaves" % (len(dados), len(fonte)))

    for k, orig in fonte.items():
        v = trad.get(k)
        if v is None: continue
        if not v.strip():
            erros.append("%s: vazio" % k); continue
        po = collections.Counter(PLACEHOLDER.findall(orig))
        pv = collections.Counter(PLACEHOLDER.findall(v))
        if po != pv:
            erros.append("%s: placeholder %s -> %s" % (k, dict(po) or "-", dict(pv) or "-"))
        if orig.count("\\n") != v.count("\\n"):
            erros.append("%s: \\n %d -> %d" % (k, orig.count("\\n"), v.count("\\n")))
        if orig.count("\\\\") != v.count("\\\\"):
            erros.append("%s: barras %d -> %d" % (k, orig.count("\\\\"), v.count("\\\\")))
        if "&amp;" in v or "&lt;" in v or "&gt;" in v:
            erros.append("%s: entidade XML (escape duplo)" % k)

    marca = "OK  " if not erros else "ERRO"
    print("[%s] %-16s %3d/%d chaves, %d problema(s)" % (marca, nome, len(trad), len(fonte), len(erros)))
    for e in erros[:6]:
        print("       %s" % e)
    total += len(erros)

print()
print("problemas no total: %d" % total)
sys.exit(1 if total else 0)
