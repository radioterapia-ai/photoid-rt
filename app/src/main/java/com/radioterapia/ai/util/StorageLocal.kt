package com.radioterapia.ai.util

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Centraliza ONDE fica a pasta PhotoID_RT, para que salvamento, leitura
 * (tratamento) e base de dados usem SEMPRE o mesmo lugar.
 *
 * Preferência (uso interno nas clínicas, com "Acesso a todos os arquivos"):
 *  1) RAIZ do armazenamento interno (/storage/emulated/0/PhotoID_RT) — visível
 *     ao lado de DCIM/Download e fácil de sincronizar pelo FolderSync.
 *  2) Fallback: pasta interna do app (Android/data/<pkg>/files/PhotoID_RT),
 *     usada só se a permissão ainda não foi concedida (nada quebra).
 *
 * As fotos também são copiadas para o ROLO da câmera (Pictures/PhotoID_RT) pelo
 * fluxo de salvamento — esta classe cuida da pasta estruturada.
 */
object StorageLocal {

    // Mesmo mapa da rotina VBA "RemoverAcentos_Maiusculas" usada nos outros envios,
    // para que pastas/arquivos sigam exatamente o mesmo padrão (MAIÚSCULAS, sem acento).
    private const val COM_ACENTOS = "ÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑáàâãäéèêëíìîïóòôõöúùûüçñ"
    private const val SEM_ACENTOS = "AAAAAEEEEIIIIOOOOOUUUUCNaaaaaeeeeiiiiooooouuuucn"

    /** Caracteres proibidos em nomes de arquivo/pasta (FAT/exFAT/NTFS/ext4)
     *  + controles. O OCR da etiqueta pode injetar qualquer um deles. */
    private val ILEGAIS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    /** Remove acentos, caracteres ilegais e converte para MAIÚSCULAS
     *  (equivalente à rotina VBA, agora à prova de nomes vindos de OCR). */
    fun removerAcentosMaiusculas(texto: String?): String {
        var t = texto ?: ""
        for (i in COM_ACENTOS.indices) t = t.replace(COM_ACENTOS[i], SEM_ACENTOS[i])
        t = ILEGAIS.replace(t, " ").replace(Regex("\\s+"), " ")
        // Windows/SMB não aceitam ponto ou espaço no fim do nome.
        return t.trim().trimEnd('.', ' ').uppercase()
    }

    /** Espaço livre (MB) na partição onde as fotos são gravadas. */
    fun espacoLivreMb(context: Context): Long =
        try { photos(context).usableSpace / (1024L * 1024L) } catch (_: Exception) { Long.MAX_VALUE }

    /** true se há folga mínima para gravar fotos + PDF sem corromper a simulação. */
    fun temEspacoMinimo(context: Context, minimoMb: Long = 80): Boolean =
        espacoLivreMb(context) >= minimoMb

    /** true se podemos gravar na raiz do armazenamento (All files access). */
    fun temAcessoTotal(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true  // < Android 11 usa WRITE_EXTERNAL_STORAGE

    /** Pasta base PhotoID_RT. Ordem: pasta escolhida pelo usuário → raiz do
     *  armazenamento interno → (fallback sem permissão) pasta interna do app. */
    fun base(context: Context): File {
        val custom = try { com.radioterapia.ai.AppConfig(context).pastaBaseCustom } catch (_: Exception) { "" }
        if (custom.isNotBlank()) return File(custom)
        return if (temAcessoTotal()) File(Environment.getExternalStorageDirectory(), "PhotoID_RT")
        else File(context.getExternalFilesDir(null) ?: context.filesDir, "PhotoID_RT")
    }

    fun photos(context: Context): File = File(base(context), "PHOTOS").apply { mkdirs() }
    fun database(context: Context): File = File(base(context), "DATABASE").apply { mkdirs() }

    /** Caminho legível para mostrar ao usuário. Quando o padrão está em uso,
     *  mostra SEMPRE a raiz pretendida (Armazenamento interno/PhotoID_RT) —
     *  a permissão "Acesso a todos os arquivos" é pedida nos pontos de escolha. */
    /**
     * O caminho que a tela mostra — o EFETIVO, nunca o pretendido.
     *
     * Antes esta função devolvia "Armazenamento interno/PhotoID_RT" sempre,
     * inclusive quando [base] estava no fallback privado do app por falta de
     * "Acesso a todos os arquivos". A tela dizia onde as fotos DEVERIAM estar, e
     * quem lesse acreditaria — que é pior do que não dizer nada: sem registro a
     * pessoa procura, com registro errado ela confia.
     *
     * O custo era concreto: alguém desinstala achando que as fotos estão em
     * `/storage/emulated/0/`, e o Android apaga o `getExternalFilesDir()` junto.
     */
    fun caminhoLegivel(context: Context): String = try {
        amigavel(base(context).absolutePath)
    } catch (_: Exception) { "PhotoID_RT" }

    /**
     * As fotos estão na pasta privada do app, que o Android APAGA na
     * desinstalação?
     *
     * A tela não deduz isso do texto do caminho — pergunta aqui. É a informação
     * que muda o comportamento de quem lê, e deduzir por prefixo quebraria no
     * dia em que o caminho mudasse de forma.
     */
    fun emPastaPrivada(context: Context): Boolean = try {
        val custom = try { com.radioterapia.ai.AppConfig(context).pastaBaseCustom }
                     catch (_: Exception) { "" }
        if (custom.isNotBlank()) false else !temAcessoTotal()
    } catch (_: Exception) { false }

    /** Converte /storage/emulated/0/... em "Armazenamento interno/..." (mais claro ao usuário). */
    fun amigavel(caminho: String): String {
        val raiz = Environment.getExternalStorageDirectory().absolutePath
        return if (caminho.startsWith(raiz)) "Armazenamento interno" + caminho.substring(raiz.length)
        else caminho
    }

    /** Abre a tela do sistema para o usuário conceder "Acesso a todos os arquivos". */
    fun pedirAcessoTotal(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val it = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + activity.packageName))
            activity.startActivity(it)
        } catch (_: Exception) {
            try { activity.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Exception) {}
        }
    }

    /** Converte a tree-uri do seletor de pastas (SAF) em caminho absoluto de arquivo. */
    fun treeUriParaCaminho(uri: android.net.Uri): String? = try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val partes = docId.split(":")
        val volume = partes[0]
        val rel = partes.getOrNull(1) ?: ""
        if (volume.equals("primary", true))
            Environment.getExternalStorageDirectory().absolutePath + if (rel.isBlank()) "" else "/$rel"
        else "/storage/$volume" + if (rel.isBlank()) "" else "/$rel"
    } catch (_: Exception) { null }

    /** Cria a estrutura PhotoID_RT/{PHOTOS,DATABASE}. Chamado na inicialização e ao salvar. */
    fun garantirEstrutura(context: Context) {
        try {
            base(context).mkdirs()
            photos(context)
            database(context)
        } catch (_: Exception) { /* não crítico */ }
    }

    /** Normaliza para comparar nomes de pasta (sem acento, MAIÚSCULAS, ignora _/espaços). */
    fun chaveNome(s: String?): String =
        removerAcentosMaiusculas(s).replace("_", " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Chave FROUXA: só letras e dígitos. Existe para reencontrar pastas do
     * formato legado.
     *
     * A criação da pasta já usou uma normalização mais agressiva, que apagava
     * apóstrofo e hífen (`[^A-Za-z0-9 ]`), enquanto [chaveNome] os preserva —
     * então "MARIA D'ARC" gravava a pasta "MARIA DARC" e depois não a
     * encontrava. Pacientes com apóstrofo ou hífen no nome ficavam com a pasta
     * invisível para o app. Hoje a gravação usa [removerAcentosMaiusculas], mas
     * as pastas antigas continuam no disco: esta chave é o que as recupera.
     */
    fun chaveNomeSolta(s: String?): String =
        chaveNome(s).replace(Regex("[^A-Za-z0-9]"), "")

    /**
     * Nome da pasta do paciente: NOME (com espaços) seguido de " - " e o prontuário.
     * Ex.: "Fosse Cdee" + "5667789" -> "FOSSE CDEE - 5667789".
     * Sem prontuário, fica só o nome.
     */
    fun nomePastaPaciente(nome: String, prontuario: String?): String {
        val n = removerAcentosMaiusculas(nome).replace(Regex("\\s+"), " ")
        val p = prontuario?.trim().orEmpty()
        return if (p.isBlank()) n else "$n - $p"
    }

    /**
     * Decide se uma pasta pertence ao paciente. Casa pelo NOME e aceita o sufixo
     * do prontuário no fim — de qualquer formato, numérico ou não —, com
     * qualquer separador (" - ", "_" ou espaço).
     * Assim a busca por nome encontra a pasta tanto no formato novo
     * ("NOME - PRONTUÁRIO") quanto em pastas antigas ("NOME_PRONTUÁRIO" ou só "NOME").
     */
    fun pastaCasaPaciente(nomePasta: String?, nomePaciente: String): Boolean {
        val k = chaveNome(nomePasta)
        val alvo = chaveNome(nomePaciente)
        if (k == alvo) return true

        /*
            O PRONTUÁRIO NÃO PRECISA SER NUMÉRICO.

            As duas regras abaixo exigem que o sufixo depois do nome seja só
            dígitos, e o KDoc acima dizia isso com todas as letras. Só que
            [nomePastaPaciente] grava o prontuário COMO ELE FOI DIGITADO, e
            serviço nenhum é obrigado a usar prontuário numérico — "RT-2024-001"
            e "A1234" são formatos comuns.

            O efeito: a pasta existia, com as fotos dentro, e o módulo de
            Tratamento dizia "paciente não encontrado", porque a varredura não
            reconhecia a pasta como sendo dele. Pelo cadastro o paciente
            continuava lá, com card e miniatura — que é como o defeito aparece
            para quem usa.

            A regra nova casa pela CONVENÇÃO DO NOME DA PASTA, que é
            "NOME - PRONTUARIO": compara o que vem antes do último " - " com o
            nome procurado. É a mesma convenção que EditarPacienteActivity já
            usava para reencontrar a pasta na exclusão — as duas leituras do
            mesmo formato agora concordam.
         */
        if (chaveNome(nomePasta?.substringBeforeLast(" - ")) == alvo) return true

        if (k.startsWith("$alvo ")) {
            val resto = k.removePrefix("$alvo ").trimStart('-', ' ', '_').trim()
            if (resto.isNotEmpty() && resto.all { it.isDigit() }) return true
        }
        // Formato legado (sem apóstrofo/hífen). Ver chaveNomeSolta.
        val kS = chaveNomeSolta(nomePasta)
        val alvoS = chaveNomeSolta(nomePaciente)
        if (alvoS.isBlank()) return false
        if (kS == alvoS) return true
        if (!kS.startsWith(alvoS)) return false
        val restoS = kS.removePrefix(alvoS)
        return restoS.isNotEmpty() && restoS.all { it.isDigit() }
    }

    /** Resolve a pasta LOCAL de uma simulação mesmo quando o nome vindo do
     *  servidor difere da pasta gravada aqui (caixa, acentos, variações):
     *  tenta o nome exato e, se não existir, varre as pastas comparando a
     *  forma normalizada e o sufixo NOVA SIMULACAO correspondente ao número.
     *  Sem isso, Time-Out e observações gravados na finalização "sumiam" nas
     *  rodadas de fotos adicionais. */
    fun resolverPastaSim(context: Context, nomePaciente: String, numSim: Int,
                         nomeSugerido: String, prontuario: String = ""): File {
        val base = photos(context)
        val exato = File(base, nomeSugerido)
        if (exato.exists()) return exato
        val candidatas = (base.listFiles() ?: return exato)
            .filter { it.isDirectory && pastaCandidata(it.name, nomePaciente, numSim) }
        return escolherPasta(candidatas, nomePaciente, prontuario) ?: exato
    }

    /** Pasta pode ser desta simulação? Nome bate por prefixo e o sufixo
     *  NOVA SIMULACAO corresponde ao número pedido. */
    private fun pastaCandidata(nomePasta: String, nomePaciente: String, numSim: Int): Boolean {
        val dn = removerAcentosMaiusculas(nomePasta)
        val sufOk = if (numSim > 1) dn.endsWith("NOVA SIMULACAO ${numSim - 1}")
                    else !dn.contains("NOVA SIMULACAO")
        if (!sufOk) return false
        if (dn.startsWith(removerAcentosMaiusculas(nomePaciente))) return true
        // Pasta do formato legado, sem apóstrofo/hífen. Ver chaveNomeSolta.
        val alvoS = chaveNomeSolta(nomePaciente)
        return alvoS.isNotBlank() && chaveNomeSolta(nomePasta).startsWith(alvoS)
    }

    /**
     * Desempata entre pastas candidatas. Existe porque o casamento por prefixo
     * é ambíguo em dois casos reais:
     *
     *  - HOMÔNIMAS: "MARIA SILVA - 123" e "MARIA SILVA - 456" casam as duas, e
     *    a versão anterior devolvia a primeira que o listFiles() entregasse;
     *  - PREFIXO: o nome "ANA" casa com a pasta "ANA MARIA - 999".
     *
     * O efeito não era só ler dado errado: a pasta resolvida é onde o Time-Out
     * e as observações são GRAVADOS. Escolher errado escreve no prontuário de
     * outra paciente.
     *
     * Por isso, com prontuário informado, uma pasta cujo prontuário DIVERGE
     * nunca é devolvida — na dúvida devolve nada e quem chamou trata a falta.
     */
    private fun escolherPasta(candidatas: List<File>, nomePaciente: String,
                              prontuario: String): File? {
        if (candidatas.isEmpty()) return null
        val pront = somenteAlfanum(prontuario)
        if (pront.isNotBlank()) {
            candidatas.firstOrNull { somenteAlfanum(prontuarioDaPasta(it.name)) == pront }
                ?.let { return it }
            // Pasta antiga, gravada antes do prontuário entrar no nome.
            candidatas.firstOrNull { prontuarioDaPasta(it.name).isBlank() }?.let { return it }
            return null
        }
        // Sem prontuário para desempatar: exige nome EXATO, senão "ANA" adota a
        // pasta de "ANA MARIA".
        val alvo = chaveNome(nomePaciente)
        candidatas.firstOrNull { chaveNome(nomeDaPasta(it.name)) == alvo }?.let { return it }
        return if (candidatas.size == 1) candidatas[0] else null
    }

    /**
     * TODAS as pastas deste paciente: a da simulação inicial e as de
     * reirradiação ("... NOVA SIMULACAO n").
     *
     * POR QUE EXISTE. A exclusão do paciente montava o caminho à mão e, quando
     * ele não existia, casava SÓ POR NOME — com duas homônimas, apagava as
     * fotos da paciente errada. E apagava uma pasta só, deixando as de
     * reirradiação para trás, embora prometesse remover tudo.
     *
     * A regra de divergência é a mesma de [escolherPasta], e por isso vale a
     * pena repetir aqui o que ela significa NESTE uso: com prontuário
     * informado, pasta cujo prontuário diverge não entra na lista. Na dúvida a
     * lista sai VAZIA — quem chama trata a falta. Numa exclusão, não apagar
     * nada é o erro barato; apagar a pasta de outra pessoa não tem volta.
     */
    fun pastasDoPaciente(context: Context, nomePaciente: String,
                         prontuario: String = ""): List<File> =
        pastasDoPaciente(photos(context), nomePaciente, prontuario)

    /**
     * A mesma decisão a partir da pasta PHOTOS já resolvida.
     *
     * Existe separada da versão com [Context] para que o teste alcance
     * exatamente o que falhava — a ESCOLHA entre pastas homônimas —, sem
     * precisar de Android no caminho.
     */
    fun pastasDoPaciente(base: File, nomePaciente: String,
                         prontuario: String = ""): List<File> {
        val todas = (base.listFiles() ?: return emptyList()).filter { it.isDirectory }
        val achadas = mutableListOf<File>()
        // Uma varredura por número de simulação, reaproveitando o desempate já
        // validado: pastaCandidata separa por sufixo, escolherPasta decide
        // entre as homônimas que sobraram.
        var n = 1
        while (n <= LIMITE_NOVA_SIM) {
            val candidatas = todas.filter { pastaCandidata(it.name, nomePaciente, n) }
            val escolhida = escolherPasta(candidatas, nomePaciente, prontuario)
            if (escolhida != null && achadas.none { it.path == escolhida.path }) {
                achadas.add(escolhida)
            }
            n++
        }
        return achadas
    }

    /** Teto da varredura de reirradiação. Alto o bastante para qualquer caso
     *  real e finito o bastante para não varrer o disco à toa. */
    private const val LIMITE_NOVA_SIM = 20

    /** "MARIA SILVA - 123 NOVA SIMULACAO 1" -> "MARIA SILVA". */
    fun nomeDaPasta(nomePasta: String): String =
        nomePasta.replace(Regex("(?i)\\s*NOVA SIMULACAO\\s*\\d+\\s*$"), "")
            .substringBeforeLast(" - ").trim()

    /** "MARIA SILVA - 123 NOVA SIMULACAO 1" -> "123". Vazio se a pasta não traz. */
    fun prontuarioDaPasta(nomePasta: String): String {
        val semSufixo = nomePasta.replace(Regex("(?i)\\s*NOVA SIMULACAO\\s*\\d+\\s*$"), "").trim()
        if (!semSufixo.contains(" - ")) return ""
        return semSufixo.substringAfterLast(" - ").trim()
    }

    private fun somenteAlfanum(s: String) = s.trim().replace(Regex("[^A-Za-z0-9]"), "")
}
