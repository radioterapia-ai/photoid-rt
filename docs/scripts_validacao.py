#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validações do PhotoID RT.

Cada verificação aqui nasceu de um build quebrado. Rodar antes de considerar
qualquer lote de alterações pronto — em especial depois de mover código entre
arquivos, que é a operação que mais quebrou o projeto.

Uso:
    python scripts_validacao.py                      # valida o estado atual
    python scripts_validacao.py --antes /caminho/versao_anterior/app/src/main

Com --antes, ativa também os diffs de função e de campo, que são as
verificações mais valiosas (pegam código engolido por recorte mal feito).

Rodar a partir da pasta app/src/main do projeto.
"""

import glob
import os
import re
import sys
import xml.dom.minidom as minidom

# O terminal do Windows e cp1252 e o relatorio usa ✓ e ✗. Sem esta linha o
# proprio validador morre com UnicodeEncodeError na primeira verificacao que
# PASSA — a falha aparece onde nao ha defeito nenhum.
sys.stdout.reconfigure(encoding="utf-8")
from collections import Counter

ERROS = 0


def falha(msg):
    global ERROS
    ERROS += 1
    print(f"  ✗ {msg}")


def secao(titulo):
    print(f"\n=== {titulo} ===")


# ---------------------------------------------------------------- Kotlin


def chaves_balanceadas():
    secao("Chaves balanceadas")
    ok = True
    for f in glob.glob("../../src/**/*.kt", recursive=True):
        txt = open(f, encoding="utf-8").read()
        if txt.count("{") != txt.count("}"):
            falha(f"{f}: {txt.count('{')} abre / {txt.count('}')} fecha")
            ok = False
    if ok:
        print("  ✓ todos os .kt balanceados")


def _defs_por_arquivo(base, padrao):
    d = {}
    for f in glob.glob(os.path.join(base, "java/**/*.kt"), recursive=True):
        rel = os.path.relpath(f, base)
        txt = open(f, encoding="utf-8").read()
        d[rel] = set(m.group(1) for m in re.finditer(padrao, txt, re.M))
    return d


def diff_funcoes(antes):
    """Função removida de um arquivo mas ainda chamada NELE = engolida por corte."""
    secao("Funções órfãs (por arquivo)")
    v = _defs_por_arquivo(antes, r"\bfun\s+(\w+)\s*\(")
    n = _defs_por_arquivo(".", r"\bfun\s+(\w+)\s*\(")
    achou = False
    for rel, antigas in sorted(v.items()):
        if not os.path.exists(rel):
            falha(f"arquivo sumiu: {rel}")
            achou = True
            continue
        txt = open(rel, encoding="utf-8").read()
        for nome in sorted(antigas - n.get(rel, set())):
            if re.search(r"\b%s\s*\(" % re.escape(nome), txt):
                falha(f"{rel}: '{nome}' removida mas ainda chamada no arquivo")
                achou = True
    if not achou:
        print("  ✓ nenhuma função órfã")


def diff_campos(antes):
    """Mesma ideia para val/var — pega bloco de campos movido para a classe errada."""
    secao("Campos órfãos (por arquivo)")
    padrao = r"^\s*(?:private |protected |internal )?(?:lateinit )?va[lr]\s+(\w+)"
    v = _defs_por_arquivo(antes, padrao)
    n = _defs_por_arquivo(".", padrao)
    achou = False
    for rel, antigos in sorted(v.items()):
        if not os.path.exists(rel):
            continue
        txt = open(rel, encoding="utf-8").read()
        for nome in sorted(antigos - n.get(rel, set())):
            # Ainda declarado como PARÂMETRO de função? Não é órfão.
            # Sem isto, remover uma função com `val caminho` local acusa
            # falso positivo porque outra função tem `caminho: String`.
            if re.search(r"(?<![\w.])%s\s*:\s*[\w<>?.]" % re.escape(nome), txt):
                continue
            usos = 0
            for m in re.finditer(r"(?<![\w.])%s\s*([.=\[)])" % re.escape(nome), txt):
                # ARGUMENTO NOMEADO (`Paciente(nome = x, id = y)`) não é uso
                # de variável: o anterior não-branco é '(' ou ','.
                if m.group(1) == "=":
                    antes_txt = txt[:m.start()].rstrip()
                    if antes_txt and antes_txt[-1] in "(,":
                        continue
                usos += 1
            if usos:
                falha(f"{rel}: campo '{nome}' removido mas ainda usado")
                achou = True
    if not achou:
        print("  ✓ nenhum campo órfão")


def labels_invalidos():
    """try/catch/if/when/for não aceitam return@label."""
    secao("Labels de retorno")
    invalidos = {"try", "catch", "finally", "if", "else", "when", "for", "while", "do"}
    achou = False
    for f in glob.glob("java/**/*.kt", recursive=True):
        txt = open(f, encoding="utf-8").read()
        for m in re.finditer(r"return@(\w+)", txt):
            if m.group(1) in invalidos:
                linha = txt[: m.start()].count("\n") + 1
                falha(f"{f}:{linha}: return@{m.group(1)} — construção não aceita label")
                achou = True
    if not achou:
        print("  ✓ nenhum label inválido")


def return_em_expressao():
    """fun x() = try { ... return ... } não compila."""
    secao("Return em corpo-expressão")
    achou = False
    for f in glob.glob("java/**/*.kt", recursive=True):
        linhas = open(f, encoding="utf-8").read().split("\n")
        for i, l in enumerate(linhas):
            if re.search(r"\bfun\s+\w+\s*\([^)]*\)\s*(:\s*[\w<>?., ]+)?\s*=\s*try\s*\{", l):
                nivel = 1
                for j in range(i + 1, len(linhas)):
                    nivel += linhas[j].count("{") - linhas[j].count("}")
                    if re.search(r"(?<![\w@])return\s", linhas[j]) and "return@" not in linhas[j]:
                        falha(f"{f}:{j+1}: 'return' em corpo-expressão")
                        achou = True
                        break
                    if nivel <= 0:
                        break
    if not achou:
        print("  ✓ nenhum return em corpo-expressão")


def imports_faltando():
    secao("Imports x usos")
    alvos = ["Intent", "Toast", "TextView", "EditText", "Button", "RadioButton",
             "AutoCompleteTextView", "AlertDialog", "Uri", "Bundle", "File", "View",
             "Bitmap", "BitmapFactory", "ImageView", "Spinner", "ViewPager2",
             "ProgressBar", "LinearLayout", "ImageButton", "CoroutineScope",
             "Dispatchers", "withContext", "ActivityResultContracts"]
    achou = False
    for f in glob.glob("java/**/*.kt", recursive=True):
        txt = open(f, encoding="utf-8").read()
        corpo = re.sub(r"^import .*$", "", txt, flags=re.M)
        corpo = re.sub(r"^\s*//.*$", "", corpo, flags=re.M)      # comentário de linha
        corpo = re.sub(r"/\*.*?\*/", "", corpo, flags=re.S)       # comentário de bloco
        corpo = re.sub(r'"(?:[^"\\]|\\.)*"', '""', corpo)         # literais de string
        for s in alvos:
            usado = re.search(r"(?<![\w.])%s\s*[({.<]" % s, corpo)
            tem = re.search(r"^import .*\b%s$" % s, txt, flags=re.M)
            if usado and not tem:
                falha(f"{os.path.basename(f)}: '{s}' usado sem import")
                achou = True
    if not achou:
        print("  ✓ nenhum import faltando")


def view_em_io():
    """Tocar em View dentro de Dispatchers.IO derruba o app."""
    secao("View tocada em Dispatchers.IO")
    achou = False
    alvo = re.compile(r"findViewById|\.setText\(|\.text\s*=|\.visibility\s*=|"
                      r"\.isEnabled\s*=|\.alpha\s*=")
    for f in glob.glob("java/**/*.kt", recursive=True):
        linhas = open(f, encoding="utf-8").read().split("\n")
        dentro, nivel = False, 0
        for i, l in enumerate(linhas, 1):
            if re.search(r"withContext\(Dispatchers\.IO\)|launch\(Dispatchers\.IO\)", l):
                # ignora blocos que abrem e fecham na mesma linha
                if l.count("{") > l.count("}"):
                    dentro, nivel = True, l.count("{") - l.count("}")
                continue
            if dentro:
                nivel += l.count("{") - l.count("}")
                if alvo.search(l) and "runOnUiThread" not in l:
                    falha(f"{os.path.basename(f)}:{i}: {l.strip()[:70]}")
                    achou = True
                if nivel <= 0:
                    dentro = False
    if not achou:
        print("  ✓ nenhuma View tocada em IO")


# ---------------------------------------------------------------- Recursos


def strings_validas():
    secao("Strings (escapes, paridade, placeholders)")
    valido = re.compile(r"\\(?:[nt'\"\\@?]|u[0-9a-fA-F]{4})")
    rx = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)
    rxp = re.compile(r"%(\d+)\$[sdf]|%[sdf]")
    achou = False

    for arq in sorted(glob.glob("res/values*/strings.xml")):
        txt = open(arq, encoding="utf-8").read()
        for m in rx.finditer(txt):
            nome, corpo = m.group(1), m.group(2)
            entre_aspas = corpo.startswith('"') and corpo.endswith('"')
            i = 0
            while i < len(corpo):
                if corpo[i] == "\\":
                    mm = valido.match(corpo, i)
                    if mm:
                        i = mm.end()      # avança sobre o par consumido
                        continue
                    falha(f"{arq}: '{nome}': escape inválido")
                    achou = True
                    break
                if corpo[i] == "'" and not entre_aspas:
                    falha(f"{arq}: '{nome}': apóstrofo sem escape (use \\')")
                    achou = True
                    break
                i += 1
        for m in re.finditer(r"&(?!amp;|lt;|gt;|quot;|apos;|#\d+;|#x[0-9a-fA-F]+;)", txt):
            linha = txt[: m.start()].count("\n") + 1
            falha(f"{arq}:{linha}: '&' sem escape")
            achou = True
            break

    # String com translatable="false" NAO entra na paridade: ela existe so no
    # idioma base de proposito. O rotulo trilingue da tela de escolha de idioma e
    # o caso — ele tem que sair igual em qualquer locale, porque quem ainda vai
    # escolher o idioma nao pode depender de ler o idioma padrao. Exigi-lo
    # traduzido seria exigir o contrario do que ele e.
    rx_fixa = re.compile(
        r'<string\s+name="([^"]+)"[^>]*translatable\s*=\s*"false"', re.I)

    def fixas(p):
        return set(rx_fixa.findall(open(p, encoding="utf-8").read()))

    naoTraduziveis = fixas("res/values/strings.xml")

    def carregar(p):
        d = dict(rx.findall(open(p, encoding="utf-8").read()))
        for k in naoTraduziveis:
            d.pop(k, None)
        return d

    # IDIOMAS DESCOBERTOS, não listados.
    #
    # Esta conferência já esteve amarrada a "en" e "es" escritos no código. Ao
    # passar de 3 para 12 idiomas, ela continuaria PASSANDO enquanto cobria um
    # quarto da superfície — o pior tipo de verificação, a que dá verde sem
    # olhar. Descobrindo pela pasta, um idioma novo entra no ritual no mesmo
    # instante em que entra no app.
    #
    # O filtro pega só qualificador de IDIOMA (`values-fr`, `values-pt-rBR`) e
    # deixa de fora qualificador de configuração (`values-sw600dp`,
    # `values-night`, `values-v21`), que não são traduções.
    rx_lang = re.compile(r"^values-([a-z]{2})(?:-r[A-Z]{2})?$")
    locales = []
    for arq in sorted(glob.glob("res/values-*/strings.xml")):
        pasta = os.path.basename(os.path.dirname(arq))
        m = rx_lang.match(pasta)
        if m:
            locales.append((m.group(1).upper(), arq))

    pt = carregar("res/values/strings.xml")
    traducoes = [(rotulo, carregar(arq)) for rotulo, arq in locales]

    for rotulo, outro in traducoes:
        faltando = sorted(set(pt) - set(outro))
        if faltando:
            falha(f"sem tradução {rotulo}: {faltando[:8]}")
            achou = True
    todas = set()
    for _, outro in traducoes:
        todas |= set(outro)
    extra = sorted(todas - set(pt))
    if extra:
        falha(f"existem fora do PT (idioma base): {extra[:8]}")
        achou = True

    def ph(s):
        return sorted(m.group(0) for m in rxp.finditer(s))

    for k in pt:
        for rotulo, outro in traducoes:
            if k in outro and ph(outro[k]) != ph(pt[k]):
                falha(f"'{k}': placeholders diferentes em {rotulo}")
                achou = True

    # ----- PLURAIS -----
    #
    # Nunca foram conferidos aqui. Com 3 idiomas passavam despercebidos; com 12
    # há 27 conjuntos, e as categorias de quantidade MUDAM por idioma — polonês
    # usa one/few/many/other, chinês só other. Categoria de menos faz a frase
    # sair errada numa faixa de números que ninguém testa.
    rxpl = re.compile(r'<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>', re.S)
    rxit = re.compile(r'<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>', re.S)
    QTD_VALIDAS = {"zero", "one", "two", "few", "many", "other"}

    def plurais(p):
        if not os.path.exists(p):
            return None
        txt = open(p, encoding="utf-8").read()
        return {n: dict(rxit.findall(c)) for n, c in rxpl.findall(txt)}

    pt_pl = plurais("res/values/plurals.xml")
    if pt_pl:
        for rotulo, arq in [("PT", "res/values/strings.xml")] + locales:
            p = os.path.join(os.path.dirname(arq), "plurals.xml")
            atual = plurais(p)
            if atual is None:
                falha(f"{rotulo}: plurals.xml ausente")
                achou = True
                continue
            for nome in pt_pl:
                if nome not in atual:
                    falha(f"{rotulo}: plural '{nome}' ausente")
                    achou = True
                    continue
                cats = set(atual[nome])
                if "other" not in cats:
                    falha(f"{rotulo}/{nome}: falta a categoria obrigatória 'other'")
                    achou = True
                invalida = cats - QTD_VALIDAS
                if invalida:
                    falha(f"{rotulo}/{nome}: quantity inexistente {sorted(invalida)}")
                    achou = True

    if not achou:
        n_pl = len(pt_pl or {})
        print(f"  ✓ {len(pt)} strings + {n_pl} plurais, {len(locales) + 1} idiomas, "
              f"escapes e placeholders OK")


def xml_valido():
    secao("XML")
    achou = False
    for f in glob.glob("res/**/*.xml", recursive=True) + ["AndroidManifest.xml"]:
        try:
            minidom.parse(f)
        except Exception as e:
            falha(f"{f}: {e}")
            achou = True
    if not achou:
        print("  ✓ todos os XML bem formados")


def ids_por_grupo():
    """Nas Configurações, cada grupo só pode usar IDs do layout que declarou."""
    secao("R.id por grupo de configuração x layout")
    arq = "java/com/radioterapia/ai/ui/SettingsActivity.kt"
    if not os.path.exists(arq):
        print("  - SettingsActivity não encontrada, pulando")
        return
    txt = open(arq, encoding="utf-8").read()

    def ids_de(layout, visto=None):
        visto = visto or set()
        if layout in visto:
            return set()
        visto.add(layout)
        try:
            t = open(f"res/layout/{layout}.xml", encoding="utf-8").read()
        except OSError:
            return set()
        s = set(re.findall(r"@\+id/(\w+)", t))
        for inc in re.findall(r'layout="@layout/(\w+)"', t):
            s |= ids_de(inc, visto)
        return s

    achou = False
    for m in re.finditer(r'adicionarGrupo\("[^"]+", R\.string\.(\w+), R\.layout\.(\w+)\)', txt):
        grupo, layout = m.group(1), m.group(2)
        k = txt.find("{", m.end())
        nivel, fim = 0, k
        for e in range(k, len(txt)):
            if txt[e] == "{":
                nivel += 1
            elif txt[e] == "}":
                nivel -= 1
                if nivel == 0:
                    fim = e
                    break
        usados = set(re.findall(r"R\.id\.(\w+)", txt[m.start():fim]))
        fora = usados - ids_de(layout)
        if fora:
            falha(f"{grupo}: usa IDs que não estão em {layout}: {sorted(fora)}")
            achou = True

    grupos = [m.group(1) for m in re.finditer(
        r'adicionarGrupo\("[^"]+", R\.string\.(\w+)', txt)]
    for g, n in Counter(grupos).items():
        if n > 1:
            falha(f"grupo duplicado: {g} ({n}x)")
            achou = True

    if not achou:
        print(f"  ✓ {len(grupos)} grupos, IDs coerentes com os layouts")


def lixo_filesystem():
    secao("Lixo no filesystem")
    lixo = [f for f in glob.glob("**/*", recursive=True)
            if "{" in os.path.basename(f) or "}" in os.path.basename(f)]
    if lixo:
        for f in lixo[:5]:
            falha(f"nome suspeito (brace expansion?): {f}")
    else:
        print("  ✓ nenhum arquivo com nome suspeito")


def texto_fixo_em_kotlin():
    """Frase em português dentro de literal Kotlin em contexto de tela.

    O lint `HardcodedText` só olha XML de layout, então texto fixo no Kotlin
    passava batido pelo portão: a folha de Time-Out do PDF saía em português
    dentro de um documento em espanhol, e diálogos inteiros da identificação
    nunca chegaram a ser traduzidos. Esta verificação fecha esse buraco.

    Fora do escopo de propósito: mensagens do AuditLogger (log interno, lido por
    quem dá suporte), constantes de nome de pasta ("NOVA SIMULACAO"), regex e
    vocabulário clínico canônico.
    """
    secao("Texto de tela fixo no Kotlin")
    ctx = re.compile(r'(Toast\.makeText|\.setMessage|\.setTitle|setPositiveButton'
                     r'|setNegativeButton|setNeutralButton|\.text\s*=|\.hint\s*=)')
    lit = re.compile(r'"((?:[^"\\]|\\.)*)"')
    acento = re.compile(r'[À-ÿ]')
    achados = []
    for cam in glob.glob("java/**/*.kt", recursive=True):
        for i, linha in enumerate(
                open(cam, encoding="utf-8").read().splitlines(), 1):
            nu = linha.strip()
            if nu.startswith("//") or nu.startswith("*") or "AuditLogger" in linha:
                continue
            if not ctx.search(linha):
                continue
            for m in lit.finditer(linha):
                s = m.group(1)
                if len(s) > 3 and acento.search(s) and "R.string" not in linha:
                    achados.append(f"{cam}:{i}  {s[:60]}")
    if achados:
        for a in achados[:12]:
            falha(f"texto fixo (deveria estar em strings.xml): {a}")
    else:
        print("  ✓ nenhum texto de tela fixo no Kotlin")


def primitivas_de_rede():
    """Primitiva de rede fora dos arquivos autorizados.

    O app fotografa paciente. Ele TEM permissão de INTERNET, porque precisa
    falar com a impressora e com o servidor de arquivos da clínica — e o
    problema nunca foi o que ele faz, e sim o que nada impedia: uma biblioteca
    acrescentada amanhã sairia para a rede sem alterar o manifest, sem alterar o
    build e sem ninguém decidir.

    Esta verificação transforma "confie na revisão" em "o build quebra".
    Acrescentar rede num arquivo novo passa a exigir uma decisão explícita:
    incluir o arquivo na lista abaixo, o que aparece no diff e vai a revisão.
    """
    secao("Primitivas de rede")

    # Cada arquivo aqui tem função de rede NOMEADA e revisada.
    autorizados = {
        "print/PrinterClient.kt",          # impressora: JetDirect 9100, IPP 631
        "smb/SmbClient.kt",                # servidor de arquivos da clínica
        "csv/CsvSyncManager.kt",           # lê a base de pacientes via SmbClient
        "treatment/TreatmentPhotoFetcher.kt",  # lê fotos da pasta do servidor

        # MOTOR DE SINCRONIZAÇÃO (v4.0), um adaptador por protocolo.
        #
        # A lista dobrou de tamanho, e essa é exatamente a decisão que esta
        # verificação existe para tornar visível. O app passou a LEVAR arquivo
        # de paciente ao servidor por conta própria, em vez de depender de um
        # aplicativo externo — é a mudança material desta versão, e é por isso
        # que os termos sobem de versão e pedem novo aceite.
        #
        # Os cinco só rodam com o interruptor mestre LIGADO, que nasce
        # desligado. Com ele desligado nenhum destes arquivos é instanciado.
        "sync/destino/DestinoSmb.kt",      # smbj: servidor de arquivos do hospital
        "sync/destino/DestinoWebDav.kt",   # HttpURLConnection: PUT e MKCOL
        "sync/destino/DestinoFtp.kt",      # commons-net: serviço que só tem FTP
        "sync/destino/DestinoSftp.kt",     # jsch: SSH
        # DestinoSaf.kt NÃO entra: ele fala com o provedor de documentos do
        # próprio Android, não com a rede. Quem sai para a internet ali é o app
        # da nuvem que a clínica já instalou, com a conta dela.
    }
    padrao = re.compile(
        r"\b(Socket\s*\(|ServerSocket|HttpURLConnection|HttpsURLConnection"
        r"|URLConnection|openConnection|InetSocketAddress|InetAddress"
        r"|DatagramSocket|OkHttpClient|Retrofit|WebView)\b")

    achados = []
    for cam in glob.glob("java/**/*.kt", recursive=True):
        rel = cam.replace("\\", "/").replace("java/com/radioterapia/ai/", "")
        if rel in autorizados:
            continue
        for i, linha in enumerate(open(cam, encoding="utf-8").read().splitlines(), 1):
            nu = linha.strip()
            if nu.startswith("//") or nu.startswith("*"):
                continue
            m = padrao.search(linha)
            if m:
                achados.append(f"{rel}:{i}  {m.group(1)}")

    if achados:
        for a in achados[:12]:
            falha(f"rede fora dos arquivos autorizados: {a}")
        print("  → se o uso for legítimo, inclua o arquivo em `autorizados`,")
        print("    nesta função, para que a decisão apareça no diff.")
    else:
        print(f"  ✓ rede confinada aos {len(autorizados)} arquivos autorizados")


# ---------------------------------------------------------------- main


def main():
    antes = None
    if "--antes" in sys.argv:
        antes = sys.argv[sys.argv.index("--antes") + 1]

    if not os.path.exists("java/com/radioterapia/ai"):
        print("Rode a partir da pasta app/src/main do projeto.")
        sys.exit(2)

    chaves_balanceadas()
    if antes:
        diff_funcoes(antes)
        diff_campos(antes)
    else:
        print("\n(diffs de função e campo pulados — passe --antes <versao_anterior>)")
    labels_invalidos()
    return_em_expressao()
    imports_faltando()
    view_em_io()
    strings_validas()
    texto_fixo_em_kotlin()
    primitivas_de_rede()
    xml_valido()
    ids_por_grupo()
    lixo_filesystem()

    print()
    if ERROS:
        print(f"RESULTADO: {ERROS} problema(s) encontrado(s).")
        sys.exit(1)
    print("RESULTADO: tudo certo.")


if __name__ == "__main__":
    main()
