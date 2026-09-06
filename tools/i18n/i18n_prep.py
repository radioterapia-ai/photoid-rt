# -*- coding: utf-8 -*-
"""Prepara o corpus de traducao: audita escapes e divide em blocos."""
import re, io, os, json, collections, sys

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
SP = os.environ.get("PHOTOID_RT_I18N_SAIDA") or os.path.join(raiz_do_projeto(), "_montagem", "i18n")
os.makedirs(SP, exist_ok=True)

PADRAO = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)

def ler(loc):
    p = os.path.join(RAIZ, "app", "src", "main", "res", loc, "strings.xml")
    s = io.open(p, encoding="utf-8").read()
    return [(m.group(1), m.group(2), m.group(3)) for m in PADRAO.finditer(s)]

# ---------------------------------------------------------------- auditoria
print("=== escapes de barra invertida e entidades XML ===")
for loc in ("values", "values-en", "values-es"):
    pares = ler(loc)
    c = collections.Counter()
    for _, _, v in pares:
        for m in re.findall(r"\\(.)", v):
            c["\\" + m] += 1
        for m in re.findall(r"&[a-zA-Z#0-9]+;", v):
            c[m] += 1
    print("  %-12s %s" % (loc, dict(c)))

pt = ler("values")
print()
print("=== atributos alem do name ===")
attrs = collections.Counter(a.strip() for _, a, _ in pt if a.strip())
print("  ", dict(attrs) or "(nenhum)")

# ---------------------------------------------------------------- blocos
# As duas pecas juridicas viajam SOZINHAS: somam 9.300 caracteres, quase um
# terco do corpus, e sao o texto onde uma traducao apressada custa mais caro.
JURIDICAS = {"terms_body", "privacy_body"}

comuns = [(k, v) for k, a, v in pt if k not in JURIDICAS]
juridicas = [(k, v) for k, a, v in pt if k in JURIDICAS]

N_BLOCOS = 5
tam = (len(comuns) + N_BLOCOS - 1) // N_BLOCOS
blocos = {}
for i in range(N_BLOCOS):
    fatia = comuns[i * tam:(i + 1) * tam]
    if fatia:
        blocos["b%d" % (i + 1)] = fatia
blocos["juridico"] = juridicas

print()
print("=== blocos ===")
for nome, itens in blocos.items():
    chars = sum(len(v) for _, v in itens)
    print("  %-10s %3d strings  %6d chars" % (nome, len(itens), chars))
    caminho = os.path.join(SP, "fonte_%s.json" % nome)
    io.open(caminho, "w", encoding="utf-8").write(
        json.dumps([{"k": k, "pt": v} for k, v in itens], ensure_ascii=False, indent=1))

# ---------------------------------------------------------------- glossario
# Termos que se repetem e PRECISAM sair iguais em toda a interface. Extraidos
# por frequencia e depois filtrados a mao: e a lista que faz cinco blocos
# traduzidos por cinco agentes diferentes falarem a mesma lingua.
TERMOS = [
    "simulação", "posicionamento", "paciente", "prontuário", "registro",
    "ficha de posicionamento", "Time-Out", "rubricário", "rubrica", "cargo",
    "etiqueta", "acessório", "acessórios", "rosto", "foto", "fotos",
    "tratamento", "radioterapia", "acelerador", "equipamento", "sítio",
    "médico", "físico", "tecnólogo", "dosimetrista", "enfermagem",
    "clínica", "unidade", "cadastro", "histórico", "configurações",
    "arquivar", "arquivada", "descartar", "finalizar", "carrossel",
    "impressora", "pen-drive", "galeria", "rolo da câmera", "nascimento",
    "sexo", "idade", "data da simulação", "nova simulação", "reirradiação",
    "rascunho", "observações", "conferência", "sala de simulação",
]
io.open(os.path.join(SP, "termos.json"), "w", encoding="utf-8").write(
    json.dumps(TERMOS, ensure_ascii=False, indent=1))
print()
print("termos do glossario:", len(TERMOS))
print("pasta:", SP)
