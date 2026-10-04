package com.radioterapia.ai.treatment

import android.content.Context
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.security.CredentialStore
import com.radioterapia.ai.util.NomeArquivo
import com.radioterapia.ai.util.StorageLocal
import java.io.File
import java.text.Normalizer
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Localiza e baixa fotos de simulações de um paciente para o módulo Tratamento.
 *
 * Estratégia:
 *  1. Tenta listar do servidor SMB (todos os destinos ativos, primário primeiro)
 *  2. Se servidor falhar, lê do tablet local (se a simulação foi feita aqui)
 *  3. Cache temporário em filesDir/treatment_cache/<prontuario>/
 *
 * Como as simulações se separam depende do layout:
 *   - pasta do paciente "NOME - PRONTUARIO" (local e SAF): TODAS as simulações
 *     dividem a pasta, e o número vem do NOME DO ARQUIVO — marca `NS<n>` no
 *     esquema novo, `_NOVASIMn` no legado, nenhuma na primeira simulação
 *     (ver util/NomeArquivo);
 *   - layout legado do servidor, uma pasta por simulação: o número vem do nome
 *     da pasta ("[NOME] POSICIONAMENTO/", "... NOVA SIMULACAO 1/", ...).
 */
class TreatmentPhotoFetcher(private val context: Context) {

    private val config = AppConfig(context)
    private val credentials = CredentialStore(context)
    private val cacheDir = File(context.filesDir, "treatment_cache").apply { mkdirs() }

    data class FotoInfo(
        val arquivoLocal: File,
        val nomeOriginal: String,
        val tipo: TipoFoto,
        val timestamp: Long
    )

    enum class TipoFoto { ROSTO, ETIQUETA, POSICIONAMENTO, ACESSORIOS, DOCUMENTO, DESCONHECIDO }

    data class Simulacao(
        val nomePaciente: String,
        val nomePastaCompleto: String,
        val numeroSimulacao: Int,            // 1 = primeira; 2+ = nova simulação N-1
        val timestampPrincipal: Long,        // timestamp da foto mais recente
        val fotos: List<FotoInfo>,
        val origem: Origem,
        val arquivoPdfLocal: File? = null    // PDF da folha (local), se disponível
    ) {
        // A MAIS RECENTE vence. Rosto e etiqueta são únicos na ficha, mas a pasta
        // pode guardar dois: a pasta escolhida pelo usuário (SAF) não arquiva o
        // anterior ao refazer, e pastas gravadas por versões anteriores podem
        // ter duplicatas. A primeira da listagem seria arbitrária, e poderia ser
        // justamente a que foi refeita.
        val rosto: FotoInfo? get() = fotos.filter { it.tipo == TipoFoto.ROSTO }.maxByOrNull { it.timestamp }
        val etiqueta: FotoInfo? get() = fotos.filter { it.tipo == TipoFoto.ETIQUETA }.maxByOrNull { it.timestamp }
        val acessorios: FotoInfo? get() = fotos.firstOrNull { it.tipo == TipoFoto.ACESSORIOS }
        val acessoriosLista: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.ACESSORIOS }.sortedBy { it.timestamp }
        val posicionamentos: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.POSICIONAMENTO }.sortedBy { it.timestamp }
        /** Impressos escaneados (laudos/anotações). Ficam FORA do PDF. */
        val documentos: List<FotoInfo> get() = fotos.filter { it.tipo == TipoFoto.DOCUMENTO }.sortedBy { it.timestamp }
    }

    enum class Origem { SERVIDOR, LOCAL_TABLET }

    /**
     * Pastas do PHOTOS que casam com o nome do paciente, e quais delas podem
     * ser lidas como dele. Ver [escolherPastasPaciente].
     *
     * @property candidatas as que casam pelo nome (StorageLocal.pastaCasaPaciente).
     * @property aceitas as que podem ser deste paciente.
     * @property ambigua sem prontuário para desempatar, as candidatas são de mais
     *   de um paciente. Leitura pode mostrar todas, desde que a tela diga de
     *   qual pasta cada uma vem; gravação recusa.
     */
    internal data class EscolhaPastas(
        val candidatas: List<String>,
        val aceitas: List<String>,
        val ambigua: Boolean
    )

    /** Onde gravar o que é deste paciente. Ver [decidirPasta]. */
    internal sealed class DecisaoPasta {
        /** A pasta existe e é com certeza a deste paciente. */
        data class Existente(val nomePasta: String) : DecisaoPasta()
        /** Nenhuma pasta casa com o nome: quem grava pode criar a do paciente. */
        object Nenhuma : DecisaoPasta()
        /** Há pasta com o nome, mas nenhuma é com certeza a deste paciente. */
        object Incerta : DecisaoPasta()
    }

    /**
     * Lista todas as simulações encontradas para um paciente, ordenadas da mais recente para a mais antiga.
     * @param nomePaciente nome como será procurado nas pastas (será normalizado)
     * @param prontuario quando informado, só entram as pastas deste prontuário —
     *   e a pasta antiga, sem prontuário no nome, se nenhuma homônima trouxer
     *   outro. Vazio, entram todas as que casam pelo nome, e cada simulação traz
     *   em [Simulacao.nomePastaCompleto] a pasta de onde veio.
     */
    suspend fun buscarSimulacoes(nomePaciente: String, prontuario: String = ""): List<Simulacao> {
        val nomeNorm = normalizarNome(nomePaciente)

        // Tenta servidor
        val resultadosServidor = try { buscarNoServidor(nomeNorm) }
                                  catch (_: Exception) { emptyList() }
        if (resultadosServidor.isNotEmpty()) return resultadosServidor

        // Fallback local
        return buscarLocal(nomeNorm, prontuario)
    }

    private fun buscarNoServidor(nomeNorm: String): List<Simulacao> {
        val destinos = config.obterDestinosAtivos()
        if (destinos.isEmpty()) return emptyList()
        if (config.smbUsuario.isBlank() || credentials.obterSenha().isBlank()) return emptyList()

        // Vamos no destino primário (primeiro). Se falhar, tenta backups.
        for (destino in destinos) {
            try {
                val (host, share, subpastaBase) = decomporUnc(destino.caminhoUNC)
                    ?: continue
                val cfg = SmbConfig.builder().withTimeout(15_000, TimeUnit.MILLISECONDS).build()
                val smbClient = SMBClient(cfg)

                smbClient.connect(host).use { conn ->
                    val auth = AuthenticationContext(
                        config.smbUsuario,
                        credentials.obterSenha().toCharArray(),
                        config.smbDominio
                    )
                    val session = conn.authenticate(auth)
                    val diskShare = session.connectShare(share) as DiskShare

                    val pastaBase = subpastaBase
                    val todasPastas = try {
                        diskShare.list(pastaBase).filter {
                            it.fileAttributes.toLong() and 0x10L != 0L && // directory
                            !it.fileName.startsWith(".")
                        }
                    } catch (_: Exception) { return@use emptyList<Simulacao>() }

                    val pastasPaciente = todasPastas.filter { fi ->
                        val n = normalizarNome(fi.fileName)
                        n.startsWith("$nomeNorm POSICIONAMENTO")
                    }

                    val resultado = mutableListOf<Simulacao>()
                    for (pasta in pastasPaciente) {
                        val numSim = extrairNumeroSimulacao(pasta.fileName)
                        val caminhoFotos = if (pastaBase.isEmpty()) pasta.fileName
                                            else "$pastaBase\\${pasta.fileName}"

                        val arquivos = try {
                            diskShare.list(caminhoFotos).filter {
                                !it.fileName.startsWith(".") &&
                                !it.fileName.endsWith(".pdf", ignoreCase = true) &&
                                (it.fileName.lowercase().endsWith(".jpg") ||
                                 it.fileName.lowercase().endsWith(".jpeg")) &&
                                !ehOriginal(it.fileName)
                            }
                        } catch (_: Exception) { continue }

                        val fotos = mutableListOf<FotoInfo>()
                        val cachePasta = File(cacheDir, "${nomeNorm.replace(' ', '_')}_$numSim").apply { mkdirs() }

                        for (arq in arquivos) {
                            val tipo = identificarTipo(arq.fileName)
                            val cachedFile = File(cachePasta, arq.fileName)
                            if (!cachedFile.exists() || cachedFile.length() == 0L) {
                                // Baixa
                                val caminhoArq = "$caminhoFotos\\${arq.fileName}"
                                try {
                                    diskShare.openFile(
                                        caminhoArq,
                                        EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                                        null,
                                        SMB2ShareAccess.ALL,
                                        SMB2CreateDisposition.FILE_OPEN,
                                        null
                                    ).use { remote ->
                                        cachedFile.outputStream().use { saida ->
                                            val buffer = ByteArray(64 * 1024)
                                            var offset = 0L
                                            while (true) {
                                                val lidos = remote.read(buffer, offset)
                                                if (lidos <= 0) break
                                                saida.write(buffer, 0, lidos)
                                                offset += lidos
                                            }
                                        }
                                    }
                                } catch (_: Exception) { continue }
                            }
                            fotos.add(FotoInfo(
                                arquivoLocal = cachedFile,
                                nomeOriginal = arq.fileName,
                                tipo = tipo,
                                timestamp = arq.lastWriteTime.toDate().time
                            ))
                        }

                        if (fotos.isNotEmpty()) {
                            // Tenta localizar/baixar o PDF da folha desta simulação
                            var pdfLocal: File? = null
                            try {
                                // GUARDA: o MAIS RECENTE, e não o primeiro da
                                // listagem. O envio nunca apaga no servidor, então a pasta
                                // guarda todas as versões da ficha, e a ordem da
                                // listagem (alfabética) poria o nome legado à
                                // frente do nome novo para sempre. Entre os PDFs,
                                // ficam os que têm a marca desta simulação; se
                                // nenhum tiver (pasta antiga, sem marca nos
                                // arquivos), vale a pasta inteira, que já é desta
                                // simulação.
                                val pdfsRemotos = diskShare.list(caminhoFotos).filter {
                                    it.fileName.endsWith(".pdf", ignoreCase = true) &&
                                    !it.fileName.startsWith(".")
                                }
                                val pdfRemoto = pdfsRemotos
                                    .filter { numeroSimDoArquivo(it.fileName) == numSim }
                                    .ifEmpty { pdfsRemotos }
                                    .maxByOrNull { it.lastWriteTime.toDate().time }
                                if (pdfRemoto != null) {
                                    val cachedPdf = File(cachePasta, pdfRemoto.fileName)
                                    if (!cachedPdf.exists() || cachedPdf.length() == 0L) {
                                        diskShare.openFile(
                                            "$caminhoFotos\\${pdfRemoto.fileName}",
                                            EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                                            null, SMB2ShareAccess.ALL,
                                            SMB2CreateDisposition.FILE_OPEN, null
                                        ).use { remote ->
                                            cachedPdf.outputStream().use { saida ->
                                                val buffer = ByteArray(64 * 1024)
                                                var offset = 0L
                                                while (true) {
                                                    val lidos = remote.read(buffer, offset)
                                                    if (lidos <= 0) break
                                                    saida.write(buffer, 0, lidos)
                                                    offset += lidos
                                                }
                                            }
                                        }
                                    }
                                    if (cachedPdf.exists() && cachedPdf.length() > 0L) pdfLocal = cachedPdf
                                }
                            } catch (_: Exception) { /* PDF é opcional */ }

                            resultado.add(Simulacao(
                                nomePaciente = nomeNorm,
                                nomePastaCompleto = pasta.fileName,
                                numeroSimulacao = numSim,
                                timestampPrincipal = fotos.maxOf { it.timestamp },
                                fotos = fotos,
                                origem = Origem.SERVIDOR,
                                arquivoPdfLocal = pdfLocal
                            ))
                        }
                    }
                    return resultado.sortedByDescending { it.timestampPrincipal }
                }
            } catch (_: Exception) { continue }
        }
        return emptyList()
    }

    /** Fallback: lê fotos da pasta Pictures local (se a simulação foi feita neste tablet). */
    private fun buscarLocal(nomeNorm: String, prontuario: String): List<Simulacao> {
        // Se há pasta SAF configurada (fluxo novo, item local), varre por lá.
        if (config.pastaFotosUri.isNotBlank()) {
            val saf = buscarLocalSaf(nomeNorm, prontuario)
            if (saf.isNotEmpty()) return saf
            // se nada na SAF, ainda tenta os caminhos abaixo
        }

        // Pasta padrão PhotoID_RT/PHOTOS/<PACIENTE> (raiz do armazenamento ou pasta do app)
        val appPhotos = com.radioterapia.ai.util.StorageLocal.photos(context)
        if (appPhotos.exists() && appPhotos.isDirectory) {
            // GUARDA: as pastas vêm da regra de escolha, nunca da primeira que o
            // listFiles() entregar. "MARIA DA SILVA - 1001" e "MARIA DA SILVA -
            // 2002" casam as duas pelo nome, a ordem da listagem depende do
            // sistema de arquivos, e a primeira pode ser a da homônima — com as
            // fotos, a ficha e os alertas do Time-Out dela.
            val todas = appPhotos.listFiles { f -> f.isDirectory }?.toList().orEmpty()
            val aceitas = escolherPastasPaciente(todas.map { it.name }, nomeNorm, prontuario).aceitas
            val doPaciente = todas.filter { it.name in aceitas }.flatMap { pastaPac ->
                val arquivos = pastaPac.listFiles { _, name ->
                    (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) &&
                        !ehOriginal(name)
                }?.toList() ?: emptyList()
                if (arquivos.isEmpty()) return@flatMap emptyList<Simulacao>()
                // SEPARA POR SIMULACAO. Antes tudo voltava como UMA simulacao
                // de numero 1, e o efeito nao era so o seletor da tela do
                // paciente nunca aparecer: o carrossel misturava as fotos da
                // simulacao original com as da reirradiacao, e o Time-Out
                // lido era sempre o da primeira. As fotos SEMPRE foram
                // distinguiveis — a reirradiacao leva a marca no nome,
                // "NS<n>" no esquema novo e "_NOVASIMn" no legado —, so
                // ninguem estava olhando.
                val pdfs = pastaPac.listFiles { _, name -> name.endsWith(".pdf", true) }
                    ?.toList().orEmpty()
                arquivos
                    .groupBy { numeroSimDoArquivo(it.name) }
                    .map { (numSim, doGrupo) ->
                        val fotos = doGrupo.map { arq ->
                            FotoInfo(arquivoLocal = arq, nomeOriginal = arq.name,
                                tipo = identificarTipo(arq.name),
                                timestamp = arq.lastModified())
                        }
                        // O PDF tem a MESMA marca no nome. Pegar o mais
                        // recente da pasta entregaria a ficha da reirradiacao
                        // a quem abriu a simulacao original.
                        val pdfLocal = pdfs
                            .filter { numeroSimDoArquivo(it.name) == numSim }
                            .maxByOrNull { it.lastModified() }
                        Simulacao(
                            nomePaciente = nomeNorm,
                            nomePastaCompleto = pastaPac.name,
                            numeroSimulacao = numSim,
                            timestampPrincipal = fotos.maxOfOrNull { it.timestamp } ?: 0L,
                            fotos = fotos, origem = Origem.LOCAL_TABLET,
                            arquivoPdfLocal = pdfLocal)
                    }
            }
            if (doPaciente.isNotEmpty()) return doPaciente.sortedByDescending { it.timestampPrincipal }
        }

        val pastaLocal = config.pastaBaseLocal.trimEnd('/')
        val baseDir = try {
            @Suppress("DEPRECATION")
            val raiz = android.os.Environment.getExternalStorageDirectory()
            File(raiz, pastaLocal)
        } catch (_: Exception) { return emptyList() }

        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()

        val pastas = baseDir.listFiles { f -> f.isDirectory && normalizarNome(f.name).startsWith("$nomeNorm POSICIONAMENTO") }
            ?: return emptyList()

        return pastas.map { pasta ->
            val numSim = extrairNumeroSimulacao(pasta.name)
            val arquivos = pasta.listFiles { _, name ->
                (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) &&
                    !ehOriginal(name)
            }?.toList() ?: emptyList()

            val fotos = arquivos.map { arq ->
                FotoInfo(
                    arquivoLocal = arq,
                    nomeOriginal = arq.name,
                    tipo = identificarTipo(arq.name),
                    timestamp = arq.lastModified()
                )
            }

            val pdfLocal = pasta.listFiles { _, name -> name.endsWith(".pdf", true) }
                ?.maxByOrNull { it.lastModified() }

            Simulacao(
                nomePaciente = nomeNorm,
                nomePastaCompleto = pasta.name,
                numeroSimulacao = numSim,
                timestampPrincipal = fotos.maxOfOrNull { it.timestamp } ?: 0L,
                fotos = fotos,
                origem = Origem.LOCAL_TABLET,
                arquivoPdfLocal = pdfLocal
            )
        }.filter { it.fotos.isNotEmpty() }
         .sortedByDescending { it.timestampPrincipal }
    }

    /**
     * Varre a pasta SAF (DocumentFile) escolhida pelo usuário. As subpastas dos
     * pacientes contêm as fotos (.jpg) e o PDF. Como o restante do app usa File,
     * copia os arquivos da simulação para o cache e monta as Simulacoes.
     *
     * SEPARA POR SIMULAÇÃO, como [buscarLocal]: todas dividem a pasta do
     * paciente, e o número vem do nome do arquivo. Devolver tudo como simulação
     * 1 misturaria no carrossel as fotos da original com as da reirradiação, e
     * a ficha da mais recente iria para quem abriu a outra.
     *
     * As pastas lidas são as da regra de escolha ([escolherPastasPaciente]):
     * com prontuário, a pasta de uma homônima de outro prontuário não entra.
     */
    private fun buscarLocalSaf(nomeNorm: String, prontuario: String): List<Simulacao> {
        return try {
            val base = androidx.documentfile.provider.DocumentFile.fromTreeUri(
                context, android.net.Uri.parse(config.pastaFotosUri)) ?: return emptyList()
            // Navega <base>/PhotoID_RT/PHOTOS
            val photos = base.findFile("PhotoID_RT")?.findFile("PHOTOS") ?: return emptyList()
            val todas = photos.listFiles().filter { it.isDirectory }
            val aceitas = escolherPastasPaciente(todas.mapNotNull { it.name }, nomeNorm, prontuario).aceitas
            val pastas = todas.filter { doc -> doc.name?.let { it in aceitas } == true }
            pastas.flatMap { pasta ->
                val cachePasta = File(cacheDir, "saf_${(pasta.name ?: "x").replace(' ', '_')}").apply { mkdirs() }
                val fotos = mutableListOf<FotoInfo>()
                val pdfDocs = mutableListOf<androidx.documentfile.provider.DocumentFile>()
                pasta.listFiles().forEach { doc ->
                    val nome = doc.name ?: return@forEach
                    if (nome.startsWith(".")) return@forEach
                    val low = nome.lowercase()
                    when {
                        low.endsWith(".jpg") || low.endsWith(".jpeg") -> {
                            if (ehOriginal(nome)) return@forEach
                            val destino = File(cachePasta, nome)
                            // Foto que não pôde ser renovada ainda entra com a
                            // cópia anterior: no carrossel, foto velha é melhor
                            // que foto faltando.
                            copiarSeMudou(doc, destino)
                            if (destino.exists() && destino.length() > 0L) fotos.add(FotoInfo(
                                arquivoLocal = destino, nomeOriginal = nome,
                                tipo = identificarTipo(nome), timestamp = doc.lastModified()))
                        }
                        // Só registra aqui; a cópia para o cache acontece depois,
                        // apenas para o PDF escolhido de cada simulação.
                        low.endsWith(".pdf") -> pdfDocs.add(doc)
                    }
                }
                fotos.groupBy { numeroSimDoArquivo(it.nomeOriginal) }
                    .map { (numSim, doGrupo) ->
                        // O PDF MAIS RECENTE com a marca DESTA simulação (a folha
                        // é cumulativa, só a versão mais nova vale).
                        val pdfDoc = pdfDocs
                            .filter { numeroSimDoArquivo(it.name ?: "") == numSim }
                            .maxByOrNull { it.lastModified() }
                        // GUARDA: ficha só se a cópia é a do documento atual.
                        // "Salvar alterações" regrava a ficha corrigida com o
                        // MESMO nome; uma cópia antiga entregaria à reimpressão
                        // o Time-Out de antes da correção (alergia, sítio,
                        // médico). Sem conseguir renovar, nenhuma ficha.
                        val pdfLocal = pdfDoc?.let { doc ->
                            val destino = File(cachePasta, doc.name ?: "ficha_$numSim.pdf")
                            if (copiarSeMudou(doc, destino)) destino
                            else { destino.delete(); null }
                        }
                        Simulacao(
                            nomePaciente = nomeNorm,
                            nomePastaCompleto = pasta.name ?: "",
                            numeroSimulacao = numSim,
                            timestampPrincipal = doGrupo.maxOfOrNull { it.timestamp } ?: 0L,
                            fotos = doGrupo,
                            origem = Origem.LOCAL_TABLET,
                            arquivoPdfLocal = pdfLocal
                        )
                    }
            }.sortedByDescending { it.timestampPrincipal }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Garante que [destino] é a cópia do documento como ele está agora.
     *
     * O cache é chaveado pelo NOME do arquivo, e um nome não basta: a ficha
     * corrigida em "Salvar alterações" volta à pasta com o nome de antes, e a
     * cópia antiga seria servida para sempre. Por isso a cópia leva a data de
     * modificação do documento, e é refeita quando tamanho ou data deixam de
     * bater (ver [copiaEmDia]).
     *
     * A cópia nova é escrita ao lado, com nome só desta chamada, e só então
     * toma o lugar da anterior: uma leitura interrompida não deixa arquivo pela
     * metade no cache, e duas telas buscando o mesmo paciente ao mesmo tempo
     * não escrevem no mesmo arquivo.
     *
     * @return `true` quando [destino] ficou igual ao documento atual.
     */
    private fun copiarSeMudou(doc: androidx.documentfile.provider.DocumentFile, destino: File): Boolean {
        val tamanho = doc.length()
        val modificado = doc.lastModified()
        if (copiaEmDia(destino.exists(), destino.length(), destino.lastModified(), tamanho, modificado)) {
            return true
        }
        val parcial = File(destino.parentFile, "${destino.name}.${System.nanoTime()}.parcial")
        return try {
            val copiou = context.contentResolver.openInputStream(doc.uri)?.use { inp ->
                parcial.outputStream().use { inp.copyTo(it) }
                true
            } ?: false
            // rename substitui o destino de uma vez; apagar antes só se ele recusar.
            val trocou = copiou && parcial.length() > 0L &&
                (parcial.renameTo(destino) || (destino.delete() && parcial.renameTo(destino)))
            if (trocou) {
                if (modificado > 0L) destino.setLastModified(modificado)
            } else {
                parcial.delete()
            }
            trocou
        } catch (_: Exception) {
            parcial.delete()
            false
        }
    }

    /**
     * Numero da simulacao a partir do NOME DO ARQUIVO.
     *
     * A reirradiacao leva a marca no nome — "NS<n>" no esquema novo,
     * "_NOVASIMn" no legado — e a primeira simulacao nao leva nenhuma. Entao
     * NS1 e _NOVASIM1 sao a simulacao 2, e ausencia da marca e a simulacao 1.
     * A regra mora em NomeArquivo, a mesma que os gravadores usam.
     */
    private fun numeroSimDoArquivo(nomeArquivo: String): Int =
        NomeArquivo.numeroSimulacao(nomeArquivo)

    /**
     * Arquivo é a cópia ORIGINAL (quadro cheio, sem recorte)?
     *
     * Os originais ficam na MESMA pasta do paciente, para o sincronizador levar os
     * dois. Só que toda varredura de fotos daqui alimenta o carrossel do
     * Tratamento e a regeneração do PDF — sem este filtro, cada foto apareceria
     * DUAS vezes no carrossel e entraria duplicada na folha impressa.
     */
    private fun ehOriginal(nomeArquivo: String): Boolean =
        NomeArquivo.ehOriginal(nomeArquivo)

    /**
     * Tipo da foto pelo nome, nos dois esquemas. O nome novo traz o código do
     * tipo; o legado é classificado pelas mesmas palavras de sempre, na mesma
     * ordem, para que nenhuma foto já em campo mude de lugar na ficha.
     */
    private fun identificarTipo(nomeArquivo: String): TipoFoto =
        when (NomeArquivo.tipo(nomeArquivo)) {
            NomeArquivo.Tipo.ROSTO -> TipoFoto.ROSTO
            NomeArquivo.Tipo.ETIQUETA -> TipoFoto.ETIQUETA
            NomeArquivo.Tipo.POSICIONAMENTO -> TipoFoto.POSICIONAMENTO
            NomeArquivo.Tipo.ACESSORIOS -> TipoFoto.ACESSORIOS
            NomeArquivo.Tipo.IMPRESSO -> TipoFoto.DOCUMENTO
            else -> TipoFoto.DESCONHECIDO
        }

    /** Extrai número da simulação do nome da pasta. */
    private fun extrairNumeroSimulacao(nomePasta: String): Int {
        val m = Regex("NOVA\\s+SIMULACAO\\s+(\\d+)", RegexOption.IGNORE_CASE).find(nomePasta)
        val novaSim = m?.groupValues?.get(1)?.toIntOrNull()
        return if (novaSim != null) novaSim + 1 else 1
    }

    private fun decomporUnc(unc: String): Triple<String, String, String>? {
        val limpo = unc.removePrefix("\\\\").removePrefix("//")
        val partes = limpo.split(Regex("[\\\\/]")).filter { it.isNotEmpty() }
        if (partes.size < 2) return null
        val host = partes[0]
        val share = partes[1]
        val subpasta = partes.drop(2).joinToString("\\")
        return Triple(host, share, subpasta)
    }

    private fun normalizarNome(nome: String): String {
        val s = Normalizer.normalize(nome, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return s.replace(Regex("[^A-Za-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ").trim().uppercase(Locale.getDefault())
    }

    companion object {
        /**
         * A cópia em cache ainda é a do documento de origem?
         *
         * Cópia ausente ou vazia nunca está em dia. Tamanho e data da origem só
         * contam quando o provedor os informa — o SAF devolve 0 quando não
         * sabe —, e sem nenhum dos dois vale a cópia que existe. A data é
         * comparada em segundos, porque o sistema de arquivos pode guardar a da
         * cópia sem os milissegundos; uma ficha regravada difere da anterior em
         * muito mais que isso, e quase sempre no tamanho também.
         *
         * Sem `Context`: a regra se testa na JVM.
         */
        fun copiaEmDia(existe: Boolean, tamanhoCopia: Long, modificadoCopia: Long,
                       tamanhoOrigem: Long, modificadoOrigem: Long): Boolean {
            if (!existe || tamanhoCopia <= 0L) return false
            if (tamanhoOrigem > 0L && tamanhoCopia != tamanhoOrigem) return false
            if (modificadoOrigem > 0L && modificadoCopia / 1000 != modificadoOrigem / 1000) return false
            return true
        }

        /** "MARIA - 123 NOVA SIMULACAO 1": o sufixo de reirradiação não é prontuário. */
        private val SUFIXO_NOVA_SIM = Regex("(?i)\\s*NOVA SIMULACAO\\s*\\d+\\s*$")

        /** Prontuário comparável: só letras e dígitos, em maiúsculas. "rt-1001" e "RT1001" são o mesmo. */
        internal fun prontuarioComparavel(prontuario: String?): String =
            (prontuario ?: "").replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.ROOT)

        /**
         * O prontuário que a pasta traz no nome, lido em relação ao nome do
         * paciente.
         *
         * A convenção é "NOME - PRONTUARIO" (StorageLocal.nomePastaPaciente), e o
         * sufixo "NOVA SIMULACAO n" não faz parte do prontuário. As pastas antigas
         * "NOME_123" e "NOME 123", que StorageLocal.pastaCasaPaciente também
         * aceita, trazem o prontuário colado ao nome, e ele conta do mesmo jeito:
         * lidas como "sem prontuário", a pasta de outro paciente passaria por
         * pasta antiga deste.
         *
         * Vazio quando a pasta é só o nome — inclusive nome com " - " por dentro,
         * como "ANA - MARIA", que não é nome seguido de prontuário.
         */
        internal fun prontuarioDaPastaDoPaciente(nomePasta: String, nomePaciente: String): String {
            val semSufixo = nomePasta.replace(SUFIXO_NOVA_SIM, "").trim()
            val alvo = StorageLocal.chaveNome(nomePaciente)
            val alvoSolto = StorageLocal.chaveNomeSolta(nomePaciente)
            val chave = StorageLocal.chaveNome(semSufixo)
            val chaveSolta = StorageLocal.chaveNomeSolta(semSufixo)
            if (chave == alvo || (alvoSolto.isNotBlank() && chaveSolta == alvoSolto)) return ""
            val doFormato = StorageLocal.prontuarioDaPasta(nomePasta)
            if (doFormato.isNotBlank()) return doFormato
            if (alvo.isNotBlank() && chave.startsWith("$alvo ")) {
                return chave.removePrefix("$alvo ").trimStart('-', ' ', '_').trim()
            }
            if (alvoSolto.isNotBlank() && chaveSolta.startsWith(alvoSolto)) {
                return chaveSolta.removePrefix(alvoSolto)
            }
            return ""
        }

        /**
         * Quais pastas, entre as que casam pelo nome, podem ser lidas como deste
         * paciente.
         *
         * Existe porque o casamento por nome não separa homônimas: "MARIA DA
         * SILVA - 1001" e "MARIA DA SILVA - 2002" casam as duas, e pegar a
         * primeira da listagem leria — e gravaria — na pasta da outra.
         *
         * Com prontuário:
         *  - entra a pasta cujo prontuário, comparado só por letras e dígitos, é
         *    o pedido;
         *  - a pasta antiga, sem prontuário no nome, entra só quando nenhuma
         *    candidata traz outro prontuário. Havendo homônima de outro
         *    prontuário, a pasta antiga pode ser de qualquer uma das duas;
         *  - pasta de prontuário divergente nunca entra.
         *
         * Sem prontuário não há como desempatar: entram todas as candidatas, e a
         * escolha sai marcada como ambígua quando elas são de mais de um paciente
         * — prontuários diferentes, ou uma pasta com prontuário ao lado de outra
         * sem. A leitura pode mostrá-las, cada uma com a sua pasta; quem grava
         * recusa ([decidirPasta]).
         *
         * Sem Context: a regra se testa na JVM.
         */
        internal fun escolherPastasPaciente(nomesPastas: Collection<String>, nomePaciente: String,
                                            prontuario: String): EscolhaPastas {
            val candidatas = nomesPastas
                .filter { StorageLocal.pastaCasaPaciente(it, nomePaciente) }
                .distinct()
            val prontuarioDe = candidatas.associateWith {
                prontuarioComparavel(prontuarioDaPastaDoPaciente(it, nomePaciente))
            }
            val pedido = prontuarioComparavel(prontuario)
            if (pedido.isNotBlank()) {
                val exatas = candidatas.filter { prontuarioDe[it] == pedido }
                val haDivergente = candidatas.any {
                    val p = prontuarioDe[it].orEmpty()
                    p.isNotBlank() && p != pedido
                }
                val antigas = if (haDivergente) emptyList<String>()
                              else candidatas.filter { prontuarioDe[it].isNullOrBlank() }
                return EscolhaPastas(candidatas, exatas + antigas, ambigua = false)
            }
            val pacientes = candidatas.map { prontuarioDe[it].orEmpty() }.toSet()
            return EscolhaPastas(candidatas, candidatas, ambigua = pacientes.size > 1)
        }

        /**
         * A pasta deste paciente onde se grava, entre as pastas do PHOTOS.
         *
         * Na ordem:
         *  1. ambígua (sem prontuário, pastas de mais de um paciente; ou sem
         *     prontuário e com outro registro do mesmo nome no cadastro):
         *     incerta, mesmo com a pasta aberta conhecida — sem prontuário nada
         *     confirma que a simulação aberta é a deste cadastro. Uma pasta só
         *     no tablet não desempata: a homônima pode ter a pasta aqui e este
         *     paciente nenhuma;
         *  2. a pasta da simulação aberta, quando existe aqui: ela, se a regra a
         *     aceita; incerta, se não (prontuário divergente) — nunca outra pasta
         *     no lugar dela;
         *  3. nenhuma pasta casa com o nome: nenhuma, e quem grava pode criar a
         *     do paciente;
         *  4. entre as aceitas, a de nome canônico (NOME - PRONTUARIO), ou a única
         *     aceita; senão incerta. Pasta de outro prontuário nunca é devolvida,
         *     e havendo só ela também é incerta: o que se gravaria seria de outra
         *     paciente ou de uma simulação que não se pode atribuir.
         *
         * @param nomesPastas subpastas do PHOTOS (ou as pastas das simulações lidas).
         * @param pastaAberta pasta da simulação aberta; vazia quando não há.
         * @param homonimosNoCadastro o cadastro tem mais de um registro com este
         *   nome (PatientCache.temHomonimos). Só pesa sem prontuário.
         */
        internal fun decidirPasta(nomesPastas: Collection<String>, nomePaciente: String,
                                  prontuario: String, pastaAberta: String,
                                  homonimosNoCadastro: Boolean = false): DecisaoPasta {
            val escolha = escolherPastasPaciente(nomesPastas, nomePaciente, prontuario)
            if (escolha.ambigua) return DecisaoPasta.Incerta
            if (homonimosNoCadastro && prontuarioComparavel(prontuario).isBlank()) return DecisaoPasta.Incerta
            if (pastaAberta.isNotBlank() && pastaAberta in nomesPastas) {
                return if (pastaAberta in escolha.aceitas) DecisaoPasta.Existente(pastaAberta)
                       else DecisaoPasta.Incerta
            }
            if (escolha.candidatas.isEmpty()) return DecisaoPasta.Nenhuma
            val aceitas = escolha.aceitas
            if (aceitas.isEmpty()) return DecisaoPasta.Incerta
            val prontuarioAlvo = prontuario.ifBlank { prontuarioDaPastaDoPaciente(aceitas[0], nomePaciente) }
            val canonica = StorageLocal.chaveNome(StorageLocal.nomePastaPaciente(nomePaciente, prontuarioAlvo))
            aceitas.firstOrNull { StorageLocal.chaveNome(it) == canonica }
                ?.let { return DecisaoPasta.Existente(it) }
            return if (aceitas.size == 1) DecisaoPasta.Existente(aceitas[0]) else DecisaoPasta.Incerta
        }

        /**
         * As simulações que se podem atribuir a este paciente sem escolher entre
         * homônimas. Sem prontuário e com pastas de mais de um paciente, nenhuma:
         * quem lê "a mais recente" levaria a ficha da outra para a impressora.
         * Sem prontuário e com outro registro do mesmo nome no cadastro
         * ([homonimosNoCadastro]), nenhuma pasta da convenção é atribuída
         * também: a única pasta no tablet pode ser a da homônima.
         * Com prontuário, saem as de pasta que a regra não aceita. Simulação de
         * pasta que não segue a convenção "NOME - PRONTUARIO" (layout antigo do
         * servidor) não tem como ser conferida aqui e passa como veio.
         */
        internal fun simulacoesInequivocas(sims: List<Simulacao>, nomePaciente: String,
                                           prontuario: String,
                                           homonimosNoCadastro: Boolean = false): List<Simulacao> {
            val escolha = escolherPastasPaciente(sims.map { it.nomePastaCompleto }, nomePaciente, prontuario)
            if (escolha.ambigua) return emptyList()
            val semDesempate = homonimosNoCadastro && prontuarioComparavel(prontuario).isBlank()
            return sims.filter {
                it.nomePastaCompleto !in escolha.candidatas ||
                    (!semDesempate && it.nomePastaCompleto in escolha.aceitas)
            }
        }

        /**
         * A pasta de registros (Time-Out e observações, no PHOTOS do app) que
         * StorageLocal.resolverPastaSim devolveu serve a este paciente?
         *
         * O resolvedor tenta primeiro o nome exato e, sem ele, desempata entre
         * as pastas parecidas por uma regra mais fraca que [escolherPastasPaciente]:
         * sem prontuário fica com a primeira homônima de nome exato, e com
         * prontuário aceita a pasta antiga mesmo ao lado de uma homônima de
         * outro prontuário. O Time-Out lido ali sairia impresso na ficha deste
         * paciente, e a observação seria gravada na pasta da outra.
         *
         * Serve quando é a pasta já confirmada para esta tela ([pastaConfirmada],
         * mesmo nome), ou quando a regra a aceita sem ambiguidade. Pasta que nem
         * casa pelo nome ("ANA MARIA - 9" para "ANA") não serve.
         *
         * @param nomesPastas subpastas do PHOTOS do app.
         */
        internal fun pastaDeRegistrosServe(pastaResolvida: String, pastaConfirmada: String?,
                                           nomesPastas: Collection<String>, nomePaciente: String,
                                           prontuario: String,
                                           homonimosNoCadastro: Boolean = false): Boolean {
            if (!pastaConfirmada.isNullOrBlank() && pastaResolvida == pastaConfirmada) return true
            if (homonimosNoCadastro && prontuarioComparavel(prontuario).isBlank()) return false
            val escolha = escolherPastasPaciente(nomesPastas, nomePaciente, prontuario)
            return !escolha.ambigua && pastaResolvida in escolha.aceitas
        }

        /**
         * A simulação de número [numSim] numa das [pastas], pela ordem dada, sem
         * alternativa. Casar só pelo número, ou cair na mais recente, pega a
         * simulação de uma homônima quando as duas pastas aparecem na busca — e
         * quem chama grava nela.
         */
        internal fun simulacaoExata(sims: List<Simulacao>, numSim: Int, vararg pastas: String?): Simulacao? =
            pastas.asSequence()
                .filterNotNull()
                .filter { it.isNotBlank() }
                .mapNotNull { p -> sims.find { it.nomePastaCompleto == p && it.numeroSimulacao == numSim } }
                .firstOrNull()

        /**
         * O cadastro devolvido pela busca serve para o cabeçalho deste paciente?
         *
         * Com prontuário pedido, só o registro de mesmo prontuário, comparado
         * por letras e dígitos — ou sem prontuário gravado, porque a chave da
         * busca já era nome + prontuário. Sem prontuário pedido, a busca escolhe
         * entre homônimas o registro mais completo, e ele só serve se também não
         * tiver prontuário e não houver homônima. Na dúvida não serve, e
         * nascimento, sexo e médico ficam em branco: em branco o técnico vê e
         * completa; o de outra paciente sai impresso no Time-Out.
         */
        internal fun cadastroServeAoPaciente(prontuarioPedido: String, prontuarioDoCadastro: String,
                                             haHomonimos: Boolean): Boolean {
            val pedido = prontuarioComparavel(prontuarioPedido)
            val doCadastro = prontuarioComparavel(prontuarioDoCadastro)
            return if (pedido.isNotBlank()) doCadastro.isBlank() || doCadastro == pedido
                   else doCadastro.isBlank() && !haHomonimos
        }
    }
}
