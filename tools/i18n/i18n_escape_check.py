# -*- coding: utf-8 -*-
"""Confere que nenhum caractere que o Android exige escapado ficou solto."""
import re, io, os, sys, glob

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

RAIZ = os.path.join(raiz_do_projeto(), "app", "src", "main", "res")
RX = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
RXPL = re.compile(r'<item quantity="[^"]+">(.*?)</item>', re.S)
# Aspas duplas soltas o Android TOLERA (so mudam o parse se a string inteira
# estiver entre elas). O apostrofo e que e obrigatorio, e & < > pelo XML.
# Os arquivos PT/EN/ES ja em campo tem 8 aspas soltas cada e compilam.
PROBLEMA = {"'", "&", "<", ">"}

total_falhas = 0
for pasta in sorted(glob.glob(os.path.join(RAIZ, "values*"))):
    nome = os.path.basename(pasta)
    for arq in ("strings.xml", "plurals.xml"):
        p = os.path.join(pasta, arq)
        if not os.path.exists(p):
            continue
        s = io.open(p, encoding="utf-8").read()
        itens = RX.findall(s) if arq == "strings.xml" else \
                [("(plural)", c) for c in RXPL.findall(s)]
        soltos, escapados = [], 0
        for chave, corpo in itens:
            i = 0
            while i < len(corpo):
                c = corpo[i]
                if c == "\\":
                    if i + 1 < len(corpo) and corpo[i + 1] == "'":
                        escapados += 1
                    i += 2
                    continue
                if c == "&":
                    # entidade valida nao e problema
                    if re.match(r"&(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);", corpo[i:]):
                        i += 1
                        continue
                    soltos.append((chave, c))
                elif c in PROBLEMA:
                    soltos.append((chave, c))
                i += 1
        marca = "OK " if not soltos else "ERRO"
        if soltos or escapados:
            print("[%s] %-14s %-12s %3d itens, %4d apostrofo(s) escapado(s), %d solto(s)"
                  % (marca, nome, arq, len(itens), escapados, len(soltos)))
        if soltos:
            total_falhas += len(soltos)
            for k, c in soltos[:5]:
                print("        %s: %r sem escape" % (k, c))

print()
print("caracteres soltos no total: %d" % total_falhas)
