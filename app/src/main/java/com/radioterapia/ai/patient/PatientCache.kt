package com.radioterapia.ai.patient

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Mantém registro local dos pacientes que já passaram por simulação neste tablet.
 *
 * Conceitos:
 * - Cada paciente passa por UMA simulação por curso de tratamento.
 * - Se o paciente volta meses/anos depois para reirradiação, é uma NOVA simulação.
 * - O cache conta quantas simulações o paciente já fez (1ª, 2ª = reirradiação, etc).
 */
class PatientCache(context: Context) {

    private val arquivo: File = File(context.filesDir, "pacientes_cache.json")

    /**
     * Versão do schema deste cache. Toda mudança estrutural (formato de chave,
     * campos) incrementa e ganha um passo em [migrar]. Antes de qualquer
     * migração é gravado um backup `.v<N>.bak` ao lado do arquivo — sem isso,
     * uma migração com defeito levaria o cadastro inteiro junto.
     *
     *  1 → original: chave = NOME
     *  2 → E6: chave = "NOME | PRONTUARIO" (resolve homônimos)
     */
    private val SCHEMA_ATUAL = 2
    private val CHAVE_META = "__schema__"

    private val dados: JSONObject = if (arquivo.exists()) {
        try { JSONObject(arquivo.readText()) } catch (e: Exception) { JSONObject() }
    } else JSONObject()

    init { migrar(context) }

    /** Aplica os passos de migração pendentes, com backup por versão. */
    private fun migrar(context: Context) {
        try {
            val versao = dados.optJSONObject(CHAVE_META)?.optInt("versao", 1)
                ?: if (dados.length() == 0) SCHEMA_ATUAL else 1
            if (versao >= SCHEMA_ATUAL) {
                registrarVersao(); return
            }
            // Backup do estado ANTES de qualquer alteração estrutural.
            try {
                if (arquivo.exists())
                    arquivo.copyTo(File(arquivo.parentFile, "pacientes_cache.v$versao.bak"),
                        overwrite = true)
            } catch (_: Exception) { }

            // v1 → v2: nada a reescrever em massa. As chaves antigas (só NOME)
            // continuam válidas e são convertidas para "NOME | PRONTUARIO" pela
            // resolverChave() na primeira vez que o prontuário aparece. Migração
            // preguiçosa evita reprocessar milhares de registros na abertura.
            registrarVersao()
        } catch (_: Exception) { /* cache nunca deve impedir o app de abrir */ }
    }

    /** Chaves de PACIENTE (exclui a entrada de metadados do schema). */
    private fun chavesPacientes(): List<String> =
        dados.keys().asSequence().filter { it != CHAVE_META }.toList()

    private fun registrarVersao() {
        try {
            val meta = dados.optJSONObject(CHAVE_META) ?: org.json.JSONObject()
            if (meta.optInt("versao", 0) != SCHEMA_ATUAL) {
                meta.put("versao", SCHEMA_ATUAL)
                meta.put("atualizado_em", System.currentTimeMillis())
                dados.put(CHAVE_META, meta)
                salvar()
            }
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------
    // PRONTUÁRIO É OBRIGATÓRIO nas funções abaixo, de propósito.
    //
    // Tinha default `= ""` e quase todo chamador o omitia. Com prontuário em
    // branco, resolverChave() escolhe entre homônimos "o registro mais
    // completo" — que não é necessariamente o paciente em atendimento. O
    // resultado era contagem de simulação, equipamento e médico assistente
    // gravados ou lidos no paciente errado, sem nenhum sinal.
    //
    // Sem o default, o compilador aponta cada ponto que esqueceu. Quando de
    // fato não há prontuário, passe "" explicitamente — aí é uma decisão
    // visível no código, não um esquecimento.
    // ------------------------------------------------------------------

    fun obterContagemSimulacoes(nome: String, prontuario: String): Int {
        val chave = resolverChave(nome, prontuario)
        return if (dados.has(chave)) {
            val obj = dados.getJSONObject(chave)
            obj.optInt("simulacoes", obj.optInt("sessoes", 0))
        } else 0
    }

    /** Atualiza só a data da última simulação (modo edição — não incrementa a contagem). */
    fun tocarUltimaSimulacao(nome: String, prontuario: String, nascimento: String = "") {
        val chave = resolverChave(nome, prontuario)
        val obj = if (dados.has(chave)) dados.getJSONObject(chave) else JSONObject()
        obj.put("ultima_simulacao", System.currentTimeMillis())
        if (prontuario.isNotBlank()) obj.put("prontuario", prontuario)
        if (nascimento.isNotBlank()) obj.put("nascimento", nascimento)
        dados.put(chave, obj)
        salvar()
    }

    fun obterDadosPaciente(nome: String, prontuario: String = ""): DadosPaciente? {
        val chave = resolverChave(nome, prontuario)
        if (!dados.has(chave)) return null
        val obj = dados.getJSONObject(chave)
        return DadosPaciente(
            nome = chave.substringBefore(" | "),
            prontuario = obj.optString("prontuario", ""),
            nascimento = obj.optString("nascimento", ""),
            simulacoes = obj.optInt("simulacoes", obj.optInt("sessoes", 0)),
            ultimaSimulacao = obj.optLong("ultima_simulacao", obj.optLong("ultima_sessao", 0)),
            sexo = obj.optString("sexo", ""),
            medicoAssistente = obj.optString("medico_assistente", ""),
            equipamento = obj.optString("equipamento", "")
        )
    }

    /** Equipamento habitual do paciente (dropdown da finalização). */
    fun atualizarEquipamento(nome: String, equipamento: String, prontuario: String) {
        if (equipamento.isBlank()) return
        val chave = resolverChave(nome, prontuario)
        // Atualização parcial NÃO cria cadastro: criaria chave paralela vazia.
        if (!dados.has(chave)) return
        val obj = if (dados.has(chave)) dados.getJSONObject(chave) else JSONObject()
        obj.put("equipamento", equipamento)
        dados.put(chave, obj)
        salvar()
    }

    /** Remove o registro pela CHAVE do nome (independe da pasta existir).
     *  Cobre registros órfãos de versões antigas. */
    fun removerPorNome(nome: String) {
        val alvo = chavePadrao(nome)
        var mudou = false
        if (dados.has(alvo)) { dados.remove(alvo); mudou = true }
        // Varre chaves de versões antigas (normalização divergente): compara o
        // nome gravado E a própria chave pela normalização ATUAL.
        val chaves = chavesPacientes()
        for (k in chaves) {
            val obj = dados.optJSONObject(k) ?: continue
            val nomeReg = obj.optString("nome", k)
            // A parte ANTES do " | " é o nome; comparar a chave inteira não
            // funciona porque chavePadrao remove o "|" e cola o prontuário:
            // "MARIA | 123" virava "MARIA 123", que nunca casa com "MARIA".
            // Era o segundo motivo de a exclusão deixar o registro para trás.
            val soNome = k.substringBefore(" | ")
            if (chavePadrao(nomeReg) == alvo || chavePadrao(k) == alvo ||
                chavePadrao(soNome) == alvo) {
                dados.remove(k); mudou = true
            }
        }
        if (mudou) salvar()
    }

    /** Remove TODOS os registros (usado pela limpeza total da base). */
    fun removerTodosRegistros() {
        chavesPacientes().forEach { dados.remove(it) }
        salvar()
    }

    /** Campos opcionais do cadastro (etiqueta virtual personalizada). */
    fun atualizarSexoMedico(nome: String, sexo: String, medicoAssistente: String,
                            prontuario: String) {
        if (sexo.isBlank() && medicoAssistente.isBlank()) return
        val chave = resolverChave(nome, prontuario)
        // Só cria cadastro quando há identificação real (sexo).
        if (!dados.has(chave) && sexo.isBlank()) return
        val obj = if (dados.has(chave)) dados.getJSONObject(chave) else JSONObject()
        if (sexo.isNotBlank()) obj.put("sexo", sexo)
        if (medicoAssistente.isNotBlank()) obj.put("medico_assistente", medicoAssistente)
        dados.put(chave, obj)
        salvar()
    }

    fun registrarSimulacao(nome: String, prontuario: String, nascimento: String = "") {
        val chave = resolverChave(nome, prontuario)
        val obj = if (dados.has(chave)) dados.getJSONObject(chave) else JSONObject()
        val atuais = obj.optInt("simulacoes", obj.optInt("sessoes", 0))
        obj.put("simulacoes", atuais + 1)
        obj.put("ultima_simulacao", System.currentTimeMillis())
        if (prontuario.isNotBlank()) obj.put("prontuario", prontuario)
        if (nascimento.isNotBlank()) obj.put("nascimento", nascimento)
        dados.put(chave, obj)
        salvar()
        if (prontuario.isNotBlank()) consolidar(chavePadrao(nome), chave)
    }

    /**
     * Salva o caminho da foto-rosto da última simulação para uso como thumbnail no
     * histórico (item 12/13 da rodada UI). O arquivo pode deixar de existir depois
     * (limpeza de cache), por isso o adapter verifica existência antes de carregar.
     */
    fun salvarCaminhoFotoRosto(nome: String, caminho: String, prontuario: String = "") {
        val chave = resolverChave(nome, prontuario)
        if (!dados.has(chave)) return
        val obj = dados.getJSONObject(chave)
        obj.put("foto_rosto_path", caminho)
        dados.put(chave, obj)
        salvar()
    }

    /** Migra o registro para um novo nome/prontuário PRESERVANDO contagem de
     *  simulações e datas; remove a chave antiga. Usado na edição de cadastro. */
    fun migrarRegistro(nomeAntigo: String, novoNome: String, novoPront: String, novoNasc: String) {
        // A chave EXISTENTE pode ser a legada (so nome) ou a composta
        // "NOME | PRONTUARIO". Procurar so por chavePadrao era o defeito:
        //
        //   - o registro composto NAO era encontrado, entao `obj` nascia
        //     JSONObject() VAZIO — sem ultima_simulacao (a data ia a 0 e o
        //     paciente despencava para o fim do historico) e sem
        //     foto_rosto_path (a miniatura do rosto sumia da listagem);
        //   - o registro antigo NAO era removido, porque chaveAntiga nunca
        //     batia com a composta: sobrava um cadastro fantasma;
        //   - e o novo era gravado na chave LEGADA, desfazendo a migracao da
        //     E6 e devolvendo o bug de homonimos a cada edicao de cadastro.
        //
        // Os dois sintomas que o tecnico relatou — perder a foto de rosto e a
        // simulacao "descer" na lista — eram o mesmo registro vazio.
        val chaveAntiga = chavesDoNome(nomeAntigo).firstOrNull() ?: chavePadrao(nomeAntigo)
        val obj = if (dados.has(chaveAntiga)) dados.getJSONObject(chaveAntiga) else JSONObject()
        if (novoPront.isNotBlank()) obj.put("prontuario", novoPront)
        if (novoNasc.isNotBlank()) obj.put("nascimento", novoNasc)

        // A chave nova continua COMPOSTA quando ha prontuario.
        val prontFinal = (novoPront.takeIf { it.isNotBlank() }
            ?: obj.optString("prontuario", "")).trim().replace(Regex("[^A-Za-z0-9]"), "")
        val chaveNova = if (prontFinal.isNotBlank()) "${chavePadrao(novoNome)} | $prontFinal"
                        else chavePadrao(novoNome)

        // O caminho da foto e limpo porque a PASTA muda de nome na migracao, e
        // o caminho gravado apontaria para a pasta antiga. Quem reencontra e
        // obterOuEncontrarFotoRosto, na proxima vez que a lista for desenhada.
        obj.put("foto_rosto_path", "")
        dados.put(chaveNova, obj)
        if (chaveAntiga != chaveNova) dados.remove(chaveAntiga)
        salvar()
    }

    /** Como obterCaminhoFotoRosto, mas com FALLBACK: se o registro não tem o
     *  caminho (ex.: paciente importado via FolderSync), varre a pasta do paciente
     *  em PHOTOS procurando a foto de rosto e grava o caminho no registro. */
    fun obterOuEncontrarFotoRosto(context: Context, nome: String,
                                  prontuario: String = ""): String? {
        obterCaminhoFotoRosto(nome, prontuario)?.let { if (File(it).exists()) return it }
        return try {
            val photos = com.radioterapia.ai.util.StorageLocal.photos(context)
            // pastaCasaPaciente entende os tres formatos de pasta em campo
            // ("NOME", "NOME - PRONT", "NOME_PRONT") e o formato legado sem
            // apostrofo. Antes comparava chavePadrao do trecho antes de " - ",
            // que errava quando a pasta nao tinha prontuario no nome.
            val dir = photos.listFiles()?.firstOrNull { d ->
                d.isDirectory && com.radioterapia.ai.util.StorageLocal
                    .pastaCasaPaciente(d.name, nome)
            } ?: return null
            val rosto = dir.listFiles()?.firstOrNull {
                it.isFile && it.name.lowercase().contains("_rosto") &&
                    !it.name.lowercase().endsWith("_original.jpg")
            } ?: return null
            // Grava na chave REAL do registro. Com chavePadrao, o paciente ja
            // migrado para chave composta nunca tinha o caminho gravado de
            // volta: a lista varria o disco de novo a cada rolagem, e apos uma
            // edicao de cadastro a miniatura simplesmente nao voltava.
            val chave = resolverChave(nome, prontuario)
            if (dados.has(chave)) {
                dados.getJSONObject(chave).put("foto_rosto_path", rosto.absolutePath)
                salvar()
            }
            rosto.absolutePath
        } catch (_: Exception) { null }
    }

    fun obterCaminhoFotoRosto(nome: String, prontuario: String = ""): String? {
        val chave = resolverChave(nome, prontuario)
        if (!dados.has(chave)) return null
        val obj = dados.getJSONObject(chave)
        val caminho = obj.optString("foto_rosto_path", "")
        return if (caminho.isBlank()) null else caminho
    }

    /**
     * Remove o registro do paciente. Com prontuário, apaga só o dele; sem
     * prontuário, apaga todos os homônimos.
     *
     * Removia apenas `chavePadrao(nome)` — a chave LEGADA, só com o nome. Desde
     * a E6 o registro vive em `"NOME | PRONTUÁRIO"`, então a exclusão apagava a
     * pasta do disco e deixava o cadastro intacto, com `simulacoes: 1`. Ao
     * recadastrar o paciente no mesmo dia, o app contava a próxima como
     * simulação 2 e anunciava que já existia.
     */
    fun remover(nome: String, prontuario: String = "") {
        val alvos = chavesDoNome(nome, prontuario)
        if (alvos.isEmpty()) return
        alvos.forEach { dados.remove(it) }
        salvar()
    }

    /**
     * Chaves existentes deste paciente: a legada (só nome) e as compostas.
     * Com prontuário informado, restringe às que batem — a legada entra só se
     * não tiver prontuário gravado ou se for o mesmo.
     */
    private fun chavesDoNome(nome: String, prontuario: String = ""): List<String> {
        val base = chavePadrao(nome)
        val pront = prontuario.trim().replace(Regex("[^A-Za-z0-9]"), "")
        val todas = chavesPacientes().filter { it == base || it.startsWith("$base | ") }
        if (pront.isBlank()) return todas
        return todas.filter { k ->
            if (k == base) {
                val p = dados.optJSONObject(k)?.optString("prontuario", "")
                    ?.replace(Regex("[^A-Za-z0-9]"), "").orEmpty()
                p.isBlank() || p == pront
            } else {
                k.substringAfterLast(" | ").replace(Regex("[^A-Za-z0-9]"), "") == pront
            }
        }
    }

    /**
     * O nome já cadastrado sob este prontuário, quando pertence a OUTRO
     * paciente. `null` quando o prontuário é novo, ou é deste mesmo paciente.
     *
     * POR QUE ISTO EXISTE. A chave composta "NOME | PRONTUARIO" resolveu o lado
     * do CADASTRO: duas Marias com prontuários diferentes deixaram de
     * compartilhar registro. O lado da ENTRADA ficou sem proteção nenhuma —
     * [obterContagemSimulacoes] é chaveada por nome E prontuário, então um nome
     * diferente com o MESMO prontuário devolve 0 e passa em silêncio. O
     * CLAUDE.md descrevia este alerta como existente; ele não existia em lugar
     * nenhum do app.
     *
     * A comparação do prontuário ignora pontuação, pela mesma normalização que
     * [chavesDoNome] usa — "12.345-6" e "123456" são o mesmo prontuário. A do
     * nome usa [chavePadrao], para que acento e caixa não inventem divergência.
     *
     * Devolve o nome como está gravado, para que a mensagem possa dizer de quem
     * é o prontuário em vez de só avisar que há conflito.
     */
    fun outroPacienteComProntuario(prontuario: String, nome: String): String? {
        val pront = prontuario.trim().replace(Regex("[^A-Za-z0-9]"), "")
        if (pront.isBlank()) return null
        val base = chavePadrao(nome)
        for (k in chavesPacientes()) {
            val pk = if (k.contains(" | ")) {
                k.substringAfterLast(" | ").replace(Regex("[^A-Za-z0-9]"), "")
            } else {
                dados.optJSONObject(k)?.optString("prontuario", "")
                    ?.replace(Regex("[^A-Za-z0-9]"), "").orEmpty()
            }
            if (pk != pront) continue
            val nomeK = k.substringBefore(" | ")
            if (chavePadrao(nomeK) != base) return nomeK
        }
        return null
    }

    /**
     * Edita um paciente preservando histórico de simulações.
     * Se o nome mudou, transfere o registro para a nova chave.
     * Servidor não é tocado — apenas o cache local.
     */
    fun editarPaciente(nomeAntigo: String, novoNome: String, novoNascimento: String, novoProntuario: String) {
        // A chave EXISTENTE pode ser a legada (só nome) ou a composta. Procurar
        // apenas por chavePadrao fazia a função sair no `return` sem tocar em
        // nada — a edição do cadastro virava um no-op silencioso para todo
        // paciente já migrado.
        val chaveAntiga = chavesDoNome(nomeAntigo).firstOrNull() ?: return

        val obj = dados.getJSONObject(chaveAntiga)
        if (novoNascimento.isNotBlank()) obj.put("nascimento", novoNascimento)
        if (novoProntuario.isNotBlank()) obj.put("prontuario", novoProntuario)
        obj.put("editado_em", System.currentTimeMillis())

        // A chave nova tem que continuar COMPOSTA quando há prontuário. Com
        // `chavePadrao(novoNome)` puro, um registro já migrado era rebaixado
        // para a chave legada a cada edição — desfazendo a migração da E6 e
        // devolvendo o bug de homônimos que ela existia para resolver.
        val prontFinal = (novoProntuario.takeIf { it.isNotBlank() }
            ?: obj.optString("prontuario", "")).trim().replace(Regex("[^A-Za-z0-9]"), "")
        val chaveNova = if (prontFinal.isNotBlank()) "${chavePadrao(novoNome)} | $prontFinal"
                        else chavePadrao(novoNome)

        if (chaveAntiga != chaveNova) {
            dados.remove(chaveAntiga)
            dados.put(chaveNova, obj)
        } else {
            dados.put(chaveAntiga, obj)
        }
        salvar()
    }

    fun listarPacientes(): List<String> = chavesPacientes().sorted()

    fun totalPacientes(): Int = chavesPacientes().size

    // ===== Exportar / Importar base local (CSV de formato FIXO) =====
    // Formato: NOME;PRONTUARIO;NASCIMENTO;SIMULACOES;ULTIMA_SIMULACAO
    // Separador ';' (padrão Excel pt-BR), UTF-8 com BOM, data dd/MM/yyyy HH:mm:ss.

    fun exportarCsv(): String = exportarCsvFiltrado(null)

    /** Exporta o CSV; se [chavesPermitidas] != null, só inclui esses pacientes (chaves normalizadas). */
    fun exportarCsvFiltrado(chavesPermitidas: Set<String>?): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')  // BOM p/ acentos no Excel
        sb.append("NOME;PRONTUARIO;NASCIMENTO;SIMULACOES;ULTIMA_SIMULACAO\r\n")
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        for (chave in listarPacientes()) {
            if (chavesPermitidas != null && chave !in chavesPermitidas) continue
            val obj = dados.getJSONObject(chave)
            val pront = obj.optString("prontuario", "")
            val nasc = obj.optString("nascimento", "")
            val sims = obj.optInt("simulacoes", obj.optInt("sessoes", 0))
            val ultMs = obj.optLong("ultima_simulacao", obj.optLong("ultima_sessao", 0))
            val ult = if (ultMs > 0) fmt.format(java.util.Date(ultMs)) else ""
            val campos = listOf(chave, pront, nasc, sims.toString(), ult).map { escaparCsv(it) }
            sb.append(campos.joinToString(";")).append("\r\n")
        }
        return sb.toString()
    }

    /** Varre PhotoID_RT/PHOTOS e adiciona ao cache pacientes que ainda não existem
     *  (ex.: vindos do servidor via FolderSync). Nome/prontuário vêm do nome da pasta
     *  "NOME - PRONTUARIO"; data = arquivo mais recente. Retorna quantos foram adicionados. */
    fun sincronizarComPastas(context: Context): Int {
        var novos = 0
        try {
            val photos = com.radioterapia.ai.util.StorageLocal.photos(context)
            val dirs = photos.listFiles()?.filter { it.isDirectory } ?: return 0
            for (dir in dirs) {
                val nomePasta = dir.name ?: continue
                // Separa "NOME - PRONTUARIO" (o mesmo padrão usado ao salvar).
                val idx = nomePasta.lastIndexOf(" - ")
                val nome = if (idx > 0) nomePasta.substring(0, idx).trim() else nomePasta.trim()
                val pront = if (idx > 0) nomePasta.substring(idx + 3).trim() else ""
                val chave = chavePadrao(nome)
                if (chave.isBlank() || dados.has(chave)) continue
                val arquivos = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                val ult = arquivos.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
                val nSim = arquivos.count { it.name.endsWith(".pdf", true) }.coerceAtLeast(1)
                val obj = JSONObject()
                obj.put("prontuario", pront)
                obj.put("nascimento", "")
                obj.put("simulacoes", nSim)
                obj.put("ultima_simulacao", ult)
                dados.put(chave, obj)
                novos++
            }
            if (novos > 0) salvar()
        } catch (_: Exception) { /* leitura best-effort */ }
        return novos
    }

    /** Chaves (nomes) cuja última simulação está dentro dos últimos [meses]. */
    fun chavesNoPeriodo(meses: Int): Set<String> {
        if (meses <= 0) return listarPacientes().toSet()
        val cal = java.util.Calendar.getInstance(); cal.add(java.util.Calendar.MONTH, -meses)
        val limite = cal.timeInMillis
        return listarPacientes().filter {
            dados.getJSONObject(it).optLong("ultima_simulacao", dados.getJSONObject(it).optLong("ultima_sessao", 0)) >= limite
        }.toSet()
    }

    /** Importa CSV mesclando só as linhas cuja ULTIMA_SIMULACAO esteja no período. */
    fun importarCsvPeriodo(texto: String, meses: Int): Int {
        if (meses <= 0) return importarCsv(texto)
        val cal = java.util.Calendar.getInstance(); cal.add(java.util.Calendar.MONTH, -meses)
        val limite = cal.timeInMillis
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        val linhas = texto.replace("\uFEFF", "").split(Regex("\r?\n")).filter { it.isNotBlank() }
        if (linhas.isEmpty()) return 0
        val sb = StringBuilder("NOME;PRONTUARIO;NASCIMENTO;SIMULACOES;ULTIMA_SIMULACAO\r\n")
        val inicio = if (linhas[0].uppercase().startsWith("NOME")) 1 else 0
        for (i in inicio until linhas.size) {
            val cols = parseLinhaCsv(linhas[i])
            val ult = cols.getOrNull(4)?.trim().orEmpty()
            val ms = try { if (ult.isBlank()) 0L else fmt.parse(ult)?.time ?: 0L } catch (_: Exception) { 0L }
            if (ms >= limite) sb.append(linhas[i]).append("\r\n")
        }
        return importarCsv(sb.toString())
    }

    /** Remove o registro cujo nome de pasta ("NOME - PRONTUARIO" ou "NOME") corresponda. */
    fun removerPorNomePasta(nomePasta: String) {
        /*
            COMPARA CHAVE NORMALIZADA COM CHAVE NORMALIZADA.

            Esta função comparava o nome da pasta NORMALIZADO contra as chaves
            CRUAS do cadastro, e por isso não removia nada quando a chave era
            composta — que é o formato desde o schema 2:

                pasta  "MARIA DA SILVA - 123456"  -> chavePadrao -> "MARIA DA SILVA 123456"
                chave  "MARIA DA SILVA | 123456"

            Os dois nunca casam, nem no `dados.has(alvo)` nem no `startsWith`.
            O efeito em campo é exatamente o relatado: a pasta some, as fotos
            somem, e o paciente continua na lista com o card e a miniatura —
            aí abrir o resumo diz "paciente não encontrado", porque não há mais
            pasta para varrer.

            É a MESMA armadilha que removerPorNome já documenta três funções
            acima, e que foi corrigida lá: `chavePadrao` remove o "|" e cola o
            prontuário. A correção não veio para esta irmã.

            Agora a comparação acontece dos dois lados normalizados, e pelas
            duas partes da chave — a inteira e só o nome —, porque uma pasta
            sem prontuário no fim tem que continuar removendo o registro dela.
         */
        val alvo = chavePadrao(nomePasta)
        if (dados.has(alvo)) { dados.remove(alvo); salvar(); return }

        val achada = chavesPacientes().firstOrNull { k ->
            val kNorm = chavePadrao(k)
            val soNome = chavePadrao(k.substringBefore(" | "))
            kNorm == alvo ||
                alvo == soNome ||
                alvo.startsWith("$soNome ") ||
                kNorm.startsWith("$alvo ")
        }
        if (achada != null) { dados.remove(achada); salvar() }
    }

    /** Importa/mescla pacientes de um CSV no formato fixo. Retorna quantos foram lidos. */
    /** Diagnóstico de um CSV ANTES de mesclar (prévia para o usuário decidir). */
    data class PreviaCsv(
        val valido: Boolean,
        val linhas: Int,
        val novos: Int,
        val atualizados: Int,
        val ignorados: Int,
        val problema: String = ""
    )

    /**
     * Verifica se o texto é mesmo a base do PhotoID antes de deixar mesclar.
     * Um arquivo com separador errado ou colunas trocadas entrava direto no
     * cadastro e só era percebido depois, com dados corrompidos em campo.
     */
    fun analisarCsv(texto: String): PreviaCsv {
        val linhas = texto.replace("\uFEFF", "").split(Regex("\r?\n")).filter { it.isNotBlank() }
        if (linhas.isEmpty()) return PreviaCsv(false, 0, 0, 0, 0, "arquivo vazio")
        val temCabecalho = linhas[0].uppercase().startsWith("NOME")
        // Sem cabeçalho reconhecível E sem separador esperado = arquivo errado.
        if (!temCabecalho && !linhas[0].contains(";") && !linhas[0].contains(","))
            return PreviaCsv(false, 0, 0, 0, 0, "não parece a base do PhotoID RT")
        val inicio = if (temCabecalho) 1 else 0
        if (linhas.size <= inicio) return PreviaCsv(false, 0, 0, 0, 0, "sem linhas de dados")

        var novos = 0; var atualizados = 0; var ignorados = 0
        for (i in inicio until linhas.size) {
            val cols = parseLinhaCsv(linhas[i])
            val nome = cols.getOrNull(0)?.trim() ?: ""
            // Linha só faz sentido com nome; nascimento, quando vier, tem de ser data.
            val nasc = cols.getOrNull(2)?.trim() ?: ""
            val nascOk = nasc.isBlank() ||
                com.radioterapia.ai.util.DateUtils.nascimentoValido(nasc) ||
                Regex("^\\d{2}[/-]\\d{2}[/-]\\d{4}$").matches(nasc)
            if (nome.isBlank() || chavePadrao(nome).isBlank() || !nascOk) { ignorados++; continue }
            val chave = resolverChave(nome, cols.getOrNull(1)?.trim() ?: "")
            if (dados.has(chave)) atualizados++ else novos++
        }
        val uteis = novos + atualizados
        if (uteis == 0)
            return PreviaCsv(false, linhas.size - inicio, 0, 0, ignorados,
                "nenhuma linha aproveitável (colunas trocadas?)")
        // Mais de 40% de lixo indica separador/colunas errados.
        if (ignorados > uteis * 2 / 3)
            return PreviaCsv(false, linhas.size - inicio, novos, atualizados, ignorados,
                "muitas linhas inválidas — confira o separador e as colunas")
        return PreviaCsv(true, linhas.size - inicio, novos, atualizados, ignorados)
    }

    fun importarCsv(texto: String): Int {
        var conta = 0
        val linhas = texto.replace("\uFEFF", "").split(Regex("\r?\n")).filter { it.isNotBlank() }
        if (linhas.isEmpty()) return 0
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        val inicio = if (linhas[0].uppercase().startsWith("NOME")) 1 else 0
        for (i in inicio until linhas.size) {
            val cols = parseLinhaCsv(linhas[i])
            val nome = cols.getOrNull(0)?.trim() ?: ""
            if (nome.isBlank()) continue
            val chave = chavePadrao(nome)
            if (chave.isBlank()) continue
            val pront = cols.getOrNull(1)?.trim() ?: ""
            val nasc = cols.getOrNull(2)?.trim() ?: ""
            val simsImp = cols.getOrNull(3)?.trim()?.toIntOrNull() ?: 0
            val ultImp = cols.getOrNull(4)?.trim()?.let {
                try { if (it.isBlank()) 0L else fmt.parse(it)?.time ?: 0L } catch (_: Exception) { 0L }
            } ?: 0L
            val obj = if (dados.has(chave)) dados.getJSONObject(chave) else JSONObject()
            // Mescla: maior nº de simulações + data mais recente; preenche campos vazios
            val simsAtual = obj.optInt("simulacoes", obj.optInt("sessoes", 0))
            obj.put("simulacoes", maxOf(simsAtual, simsImp))
            if (ultImp > obj.optLong("ultima_simulacao", 0)) obj.put("ultima_simulacao", ultImp)
            if (pront.isNotBlank()) obj.put("prontuario", pront)
            if (nasc.isNotBlank()) obj.put("nascimento", nasc)
            dados.put(chave, obj)
            conta++
        }
        salvar()
        return conta
    }

    private fun escaparCsv(campo: String): String =
        if (campo.contains(";") || campo.contains("\"") || campo.contains("\n") || campo.contains("\r"))
            "\"" + campo.replace("\"", "\"\"") + "\"" else campo

    private fun parseLinhaCsv(linha: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var emAspas = false
        var i = 0
        while (i < linha.length) {
            val ch = linha[i]
            when {
                ch == '"' -> {
                    if (emAspas && i + 1 < linha.length && linha[i + 1] == '"') { sb.append('"'); i++ }
                    else emAspas = !emAspas
                }
                ch == ';' && !emAspas -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun salvar() {
        try { arquivo.writeText(dados.toString(2)) } catch (e: Exception) { }
    }

    /**
     * Resolve a chave do registro considerando HOMÔNIMOS.
     *
     * Chave nova: "NOME | PRONTUARIO" (quando o prontuário é conhecido).
     * Chave legada: "NOME" — continua sendo lida e é MIGRADA para a composta
     * assim que o prontuário aparece, para não perder histórico.
     *
     * Sem prontuário informado: usa a chave legada se existir; senão, procura
     * chaves compostas do mesmo nome — havendo mais de uma (homônimos reais),
     * devolve a de simulação mais recente, que é a que o operador acabou de usar.
     */
    private fun resolverChave(nome: String, prontuario: String): String {
        val base = chavePadrao(nome)
        val pront = prontuario.trim().replace(Regex("[^A-Za-z0-9]"), "")
        if (pront.isNotBlank()) {
            val composta = "$base | $pront"
            if (dados.has(composta)) return composta
            // Migração: registro legado só com o nome, do MESMO prontuário (ou sem
            // prontuário gravado) vira chave composta.
            if (dados.has(base)) {
                val obj = dados.getJSONObject(base)
                val pAntigo = obj.optString("prontuario", "").replace(Regex("[^A-Za-z0-9]"), "")
                if (pAntigo.isBlank() || pAntigo == pront) {
                    obj.put("prontuario", prontuario.trim())
                    dados.put(composta, obj)
                    dados.remove(base)
                    salvar()
                    return composta
                }
            }
            return composta
        }
        // Sem prontuário: entre a chave legada (só nome) e as compostas, escolhe a
        // MAIS COMPLETA. Sem isto, um registro base criado por atualização parcial
        // sequestrava as leituras e o paciente "perdia" nascimento e sexo.
        val candidatas = chavesPacientes().filter { it == base || it.startsWith("$base | ") }
        if (candidatas.isEmpty()) return base
        if (candidatas.size == 1) return candidatas[0]
        return candidatas.maxByOrNull { k ->
            val o = dados.optJSONObject(k) ?: return@maxByOrNull -1L
            var peso = 0L
            if (o.optString("nascimento", "").isNotBlank()) peso += 8_000_000_000_000L
            if (o.optString("prontuario", "").isNotBlank()) peso += 4_000_000_000_000L
            if (o.optString("sexo", "").isNotBlank()) peso += 2_000_000_000_000L
            peso + o.optLong("ultima_simulacao", 0)
        } ?: candidatas[0]
    }

    /**
     * Mescla o registro legado (só NOME) no composto, eliminando duplicatas
     * herdadas. Chamado ao gravar cadastro completo.
     */
    private fun consolidar(base: String, composta: String) {
        try {
            if (base == composta || !dados.has(base) || !dados.has(composta)) return
            val velho = dados.getJSONObject(base)
            val novo = dados.getJSONObject(composta)
            for (campo in listOf("nascimento", "sexo", "medico_assistente",
                                 "equipamento", "foto_rosto_path")) {
                if (novo.optString(campo, "").isBlank() && velho.optString(campo, "").isNotBlank())
                    novo.put(campo, velho.optString(campo))
            }
            val simsVelho = velho.optInt("simulacoes", velho.optInt("sessoes", 0))
            if (simsVelho > novo.optInt("simulacoes", 0)) novo.put("simulacoes", simsVelho)
            val ultVelho = velho.optLong("ultima_simulacao", 0)
            if (ultVelho > novo.optLong("ultima_simulacao", 0))
                novo.put("ultima_simulacao", ultVelho)
            dados.put(composta, novo)
            dados.remove(base)
            salvar()
        } catch (_: Exception) { }
    }

    /** true se existe mais de um paciente com este nome (prontuários distintos). */
    fun temHomonimos(nome: String): Boolean {
        val base = chavePadrao(nome)
        val n = chavesPacientes().count { it == base || it.startsWith("$base | ") }
        return n > 1
    }

    /** Prontuários já cadastrados com este nome (para desambiguar na UI). */
    fun prontuariosDoNome(nome: String): List<String> {
        val base = chavePadrao(nome)
        return chavesPacientes().asSequence()
            .filter { it == base || it.startsWith("$base | ") }
            .map { dados.getJSONObject(it).optString("prontuario", "") }
            .filter { it.isNotBlank() }.toList()
    }

    /** true se este prontuário já está cadastrado para um NOME diferente. */
    fun prontuarioDeOutroPaciente(prontuario: String, nome: String): String? {
        val p = prontuario.trim().replace(Regex("[^A-Za-z0-9]"), "")
        if (p.isBlank()) return null
        val base = chavePadrao(nome)
        for (k in chavesPacientes()) {
            val obj = dados.optJSONObject(k) ?: continue
            val pk = obj.optString("prontuario", "").replace(Regex("[^A-Za-z0-9]"), "")
            if (pk == p) {
                val nomeK = k.substringBefore(" | ")
                if (nomeK != base) return nomeK
            }
        }
        return null
    }

    private fun chavePadrao(nome: String): String {
        val semAcento = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return semAcento
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase(Locale("pt", "BR"))
    }

    data class DadosPaciente(
        val nome: String,
        val prontuario: String,
        val nascimento: String,
        val simulacoes: Int,
        val ultimaSimulacao: Long,
        val sexo: String = "",
        val medicoAssistente: String = "",
        val equipamento: String = ""
    )
}

