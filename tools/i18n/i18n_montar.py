# -*- coding: utf-8 -*-
"""
Monta os res/values-XX/strings.xml a partir do que os tradutores gravaram.

O ESCAPE DO ANDROID E FEITO AQUI, e nao pelo tradutor. Escapar e deterministico:
apostrofo vira \\', & vira &amp;, e as sequencias \\n \\" \\\\ \\uXXXX passam
intactas. Deixar isso com o modelo poria o build na dependencia de ele acertar
seiscentas vezes seguidas em nove idiomas — e o footgun n.6 do projeto e
exatamente apostrofo nao escapado, que so aparece em frances e italiano.

Roda em dois modos:
    python i18n_montar.py --conferir   so valida e relata, nao escreve
    python i18n_montar.py --gravar     valida e, se passar, grava os XML
"""
import re, io, os, json, sys, collections

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
SP = os.path.join(RAIZ, "docs", "i18n")
TRAD = os.path.join(SP, "pendente", "traducoes")

IDIOMAS = ["fr", "de", "it", "pl", "zh", "ja", "ko", "ar", "bn"]
# b6 nasceu depois: e o bloco das strings acrescentadas apos a primeira
# rodada. Bloco novo em vez de refatiar b1..b5, que invalidaria o que ja
# estava traduzido.
BLOCOS = ["b1", "b2", "b3", "b4", "b5", "juridico", "b6", "b7", "b8", "b9"]

PADRAO = re.compile(r'<string name="([^"]+)"[^>]*>(.*?)</string>', re.S)
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")
ESCAPES = re.compile(r"\\(?:n|t|\\|\"|'|u[0-9a-fA-F]{4})")


def fonte():
    """
    As strings que DEVEM existir em todo idioma.

    Fica de fora o que estiver marcado translatable="false": essas existem so no
    idioma base de proposito. O rotulo trilingue da tela de escolha de idioma e
    o caso — ele tem que sair igual em qualquer locale, porque quem ainda vai
    escolher o idioma nao pode depender de ler o padrao. Gera-lo traduzido seria
    escrever o contrario do que ele e, e o lint acusaria ExtraTranslation.
    """
    p = os.path.join(RAIZ, "app", "src", "main", "res", "values", "strings.xml")
    s = io.open(p, encoding="utf-8").read()
    saida = []
    for m in PADRAO.finditer(s):
        atributos = m.group(0)[:m.group(0).index(">")]
        if "translatable" in atributos and "false" in atributos:
            continue
        saida.append((m.group(1), m.group(2)))
    return saida


def escapar(bruto):
    """
    Texto cru -> corpo de <string> valido para o Android.

    CHAR A CHAR, e nao por regex de substituicao: uma barra invertida no texto
    inicia uma sequencia que tem que passar inteira. Percorrendo, quando acho
    uma barra copio ela e o proximo caractere sem olhar — assim um \\' que o
    tradutor tenha escapado por conta propria nao vira \\\\' (escape duplo, que
    aparece na tela do usuario como barra).
    """
    saida = []
    i = 0
    n = len(bruto)
    while i < n:
        c = bruto[i]
        if c == "\\" and i + 1 < n:
            saida.append(c)
            saida.append(bruto[i + 1])
            i += 2
            continue
        if c == "&":
            saida.append("&amp;")
        elif c == "<":
            saida.append("&lt;")
        elif c == ">":
            saida.append("&gt;")
        elif c == "'":
            saida.append("\\'")
        elif c == '"':
            saida.append('\\"')
        elif c == "\n":
            # Quebra de linha real vira a sequencia \n: dentro do XML uma quebra
            # fisica seria engolida como espaco pelo Android.
            saida.append("\\n")
        else:
            saida.append(c)
        i += 1
    return "".join(saida)


# ------------------------------------------------------------------ autoteste
def autoteste():
    casos = [
        ("l'application", "l\\'application"),
        ("d'un\\npatient", "d\\'un\\npatient"),
        ("A & B", "A &amp; B"),
        ("%1$s de %2$d", "%1$s de %2$d"),
        ("seta \\u2192 fim", "seta \\u2192 fim"),
        ('diz "ola"', 'diz \\"ola\\"'),
        ("ja \\'escapado", "ja \\'escapado"),
        ("a\nb", "a\\nb"),
        ("x < y > z", "x &lt; y &gt; z"),
    ]
    falhas = 0
    for entrada, esperado in casos:
        obtido = escapar(entrada)
        if obtido != esperado:
            print("  AUTOTESTE FALHOU: %r -> %r (esperado %r)" % (entrada, obtido, esperado))
            falhas += 1
    print("autoteste do escape: %d casos, %d falhas" % (len(casos), falhas))
    return falhas == 0


# ------------------------------------------------------------------ carga
def carregar(lang):
    """Junta os 6 blocos de um idioma. Devolve (dict, lista de problemas)."""
    fora = []
    pares = {}
    for bloco in BLOCOS:
        p = os.path.join(TRAD, "%s__%s.json" % (lang, bloco))
        if not os.path.exists(p):
            fora.append("bloco AUSENTE: %s" % bloco)
            continue
        txt = io.open(p, encoding="utf-8").read().strip()
        # Cerca de markdown as vezes escapa do modelo; tira antes de parsear.
        if txt.startswith("```"):
            txt = re.sub(r"^```[a-z]*\s*", "", txt)
            txt = re.sub(r"\s*```$", "", txt)
        try:
            dados = json.loads(txt)
        except Exception as e:
            fora.append("bloco %s: JSON invalido (%s)" % (bloco, e))
            continue
        if not isinstance(dados, list):
            fora.append("bloco %s: nao e uma lista" % bloco)
            continue
        for item in dados:
            if not isinstance(item, dict) or "k" not in item or "v" not in item:
                fora.append("bloco %s: entrada malformada" % bloco)
                continue
            pares[item["k"]] = item["v"]
    return pares, fora


# ------------------------------------------------------------------ conferencia
def conferir(lang, pares, base):
    """Devolve (erros, avisos). Erro impede a gravacao; aviso so relata."""
    erros, avisos = [], []
    chavesBase = [k for k, _ in base]
    mapaBase = dict(base)

    faltando = [k for k in chavesBase if k not in pares]
    sobrando = [k for k in pares if k not in mapaBase]
    if faltando:
        erros.append("%d chave(s) faltando: %s" % (len(faltando), ", ".join(faltando[:6])))
    if sobrando:
        erros.append("%d chave(s) a mais: %s" % (len(sobrando), ", ".join(sobrando[:6])))

    for k in chavesBase:
        if k not in pares:
            continue
        orig, trad = mapaBase[k], pares[k]
        if not trad.strip():
            erros.append("%s: traducao vazia" % k)
            continue

        # Placeholders: mesmo conjunto, mesma contagem. A ORDEM pode mudar.
        po = collections.Counter(PLACEHOLDER.findall(orig))
        pt_ = collections.Counter(PLACEHOLDER.findall(trad))
        if po != pt_:
            erros.append("%s: placeholder mudou (%s -> %s)" %
                         (k, dict(po) or "-", dict(pt_) or "-"))

        # Quebras de paragrafo: perder um \n junta dois paragrafos no texto legal.
        if orig.count("\\n") != trad.count("\\n"):
            erros.append("%s: \\n mudou de %d para %d" %
                         (k, orig.count("\\n"), trad.count("\\n")))

        # Escape ja aplicado pelo tradutor apesar da instrucao.
        if "&amp;" in trad or "&lt;" in trad or "&gt;" in trad or "&quot;" in trad:
            erros.append("%s: veio com entidade XML (escape duplo)" % k)

        # Barra invertida solta: seria escape invalido no XML do Android.
        for m in re.finditer(r"\\(.)", trad):
            if m.group(0) not in ("\\n", "\\t", "\\\\", '\\"', "\\'") and not \
               re.match(r"\\u[0-9a-fA-F]{4}", trad[m.start():m.start() + 6]):
                erros.append("%s: escape desconhecido %r" % (k, m.group(0)))
                break

        # Rotulo curto que inchou: candidato a estourar o botao.
        if len(orig) <= 25 and len(trad) > max(len(orig) * 2.2, len(orig) + 14):
            avisos.append("%s: rotulo curto inchou (%d -> %d) %r" %
                          (k, len(orig), len(trad), trad[:40]))

        # Nao traduzido: identico a origem em portugues, com letra acentuada.
        if trad == orig and len(orig) > 12 and re.search(r"[áàâãéêíóôõúçÁ]", orig):
            avisos.append("%s: identico ao portugues (nao traduzido?)" % k)

    return erros, avisos


# ------------------------------------------------------------------ gravacao
CABECALHO = """<?xml version="1.0" encoding="utf-8"?>
<!--
    %s

    Gerado a partir de res/values/strings.xml (pt-BR), que e a fonte.
    Toda chave daqui existe la: o lint trata MissingTranslation e
    ExtraTranslation como ERRO, entao divergencia para o build.

    O escape do Android (\\' \\" &amp;) foi aplicado na geracao. Ao editar
    a mao, lembre que apostrofo precisa de barra invertida.
-->
<resources>
"""

NOMES = {
    "fr": "Frances (fr)", "de": "Alemao (de)", "it": "Italiano (it)",
    "pl": "Polones (pl)", "zh": "Chines simplificado (zh)", "ja": "Japones (ja)",
    "ko": "Coreano (ko)", "ar": "Arabe (ar) - idioma RTL", "bn": "Bengali (bn)",
}


def gravar(lang, pares, base):
    linhas = [CABECALHO % NOMES[lang]]
    for k, _ in base:
        linhas.append('    <string name="%s">%s</string>\n' % (k, escapar(pares[k].strip())))
    linhas.append("</resources>\n")
    pasta = os.path.join(RAIZ, "app", "src", "main", "res", "values-%s" % lang)
    os.makedirs(pasta, exist_ok=True)
    io.open(os.path.join(pasta, "strings.xml"), "w", encoding="utf-8", newline="\n").write(
        "".join(linhas))


# ------------------------------------------------------------------ principal
def main():
    modo = sys.argv[1] if len(sys.argv) > 1 else "--conferir"
    if not autoteste():
        print("ABORTADO: o escape falhou no proprio autoteste.")
        return 1
    base = fonte()
    print("fonte: %d strings" % len(base))
    print()

    prontos, problemas = {}, {}
    for lang in IDIOMAS:
        pares, fora = carregar(lang)
        erros, avisos = ([], [])
        if pares:
            erros, avisos = conferir(lang, pares, base)
        erros = fora + erros
        marca = "OK " if not erros else "ERRO"
        print("[%s] %-3s %3d/%d strings, %d erro(s), %d aviso(s)" %
              (marca, lang, len(pares), len(base), len(erros), len(avisos)))
        for e in erros[:8]:
            print("        ERRO  %s" % e)
        for a in avisos[:5]:
            print("        aviso %s" % a)
        if len(avisos) > 5:
            print("        aviso (+%d)" % (len(avisos) - 5))
        if erros:
            problemas[lang] = erros
        else:
            prontos[lang] = pares

    print()
    print("prontos: %s" % (", ".join(sorted(prontos)) or "(nenhum)"))
    print("com erro: %s" % (", ".join(sorted(problemas)) or "(nenhum)"))

    if modo == "--gravar":
        for lang, pares in prontos.items():
            gravar(lang, pares, base)
        print()
        print("gravados %d arquivo(s) values-XX/strings.xml" % len(prontos))
    return 0


if __name__ == "__main__":
    sys.exit(main())
