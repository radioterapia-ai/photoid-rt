package com.radioterapia.ai.csv

import android.content.Context
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.data.AppDatabase
import com.radioterapia.ai.data.PatientEntity
import com.radioterapia.ai.security.CredentialStore
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet

/**
 * Sincroniza o CSV de pacientes do servidor SMB para o Room local.
 *
 * Comportamento:
 *  - Lista a pasta SMB configurada
 *  - Pega o arquivo .csv mais recente (timestamp de modificação)
 *  - Baixa para o tablet
 *  - Parseia com auto-detect de separador
 *  - Aplica mapeamento de colunas
 *  - Substitui completamente a base local (DELETE + INSERT)
 */
class CsvSyncManager(private val context: Context) {

    private val config = AppConfig(context)
    private val credentials = CredentialStore(context)
    private val mapping = CsvMapping(context)

    data class Resultado(
        val sucesso: Boolean,
        val pacientesCarregados: Int = 0,
        val pacientesIgnorados: Int = 0,
        val mensagem: String? = null
    )

    /**
     * Sincronização completa do servidor para o cache local.
     * Roda em coroutine (chame de Dispatchers.IO).
     */
    suspend fun sincronizar(): Resultado {
        if (!mapping.valido()) return Resultado(false, mensagem = "Mapeamento de colunas incompleto (nome/nasc/prontuário obrigatórios)")

        // 1. Obtém o CSV mais recente da pasta local (SAF). Ordem de preferência:
        //    (a) pasta CSV explícita; (b) <base>/PhotoID_RT/DATABASE. O recebimento
        //    da rede fica a cargo do FolderSync.
        val arquivoLocal: File? = when {
            config.pastaCsvUri.isNotBlank() -> {
                try { copiarCsvMaisRecenteLocal(config.pastaCsvUri, navegarDatabase = false) }
                catch (e: Exception) { return Resultado(false, mensagem = "Falha ao ler pasta CSV: ${e.message}") }
            }
            config.pastaFotosUri.isNotBlank() -> {
                try { copiarCsvMaisRecenteLocal(config.pastaFotosUri, navegarDatabase = true) }
                catch (e: Exception) { return Resultado(false, mensagem = "Falha ao ler PhotoID_RT/DATABASE: ${e.message}") }
            }
            else -> {
                // Pasta padrão: PhotoID_RT/DATABASE (raiz do armazenamento ou pasta do app)
                val appDb = com.radioterapia.ai.util.StorageLocal.database(context)
                val csv = appDb.listFiles { _, n -> n.endsWith(".csv", true) }?.maxByOrNull { it.lastModified() }
                if (csv != null) csv
                else {
                    val pasta = config.csvPastaUnc
                    if (pasta.isBlank()) return Resultado(false, mensagem = "Coloque o CSV em PhotoID_RT/DATABASE")
                    val (host, share, subpasta) = decomporUnc(pasta)
                        ?: return Resultado(false, mensagem = "Caminho UNC inválido: $pasta")
                    try { baixarCsvMaisRecente(host, share, subpasta) }
                    catch (e: Exception) { return Resultado(false, mensagem = "Falha SMB: ${e.message}") }
                }
            }
        }
        if (arquivoLocal == null) return Resultado(false, mensagem = "Nenhum .csv encontrado na pasta")

        // 2. Parseia
        val resultado = CsvImporter.parsear(arquivoLocal, config.csvTemCabecalho)
        if (!resultado.sucesso) return Resultado(false, mensagem = "Erro parse: ${resultado.mensagemErro}")

        // 3. Aplica mapeamento e converte para entidades
        val agora = System.currentTimeMillis()
        val pacientes = mutableListOf<PatientEntity>()
        var ignorados = 0

        for (linha in resultado.linhas) {
            try {
                val nome = obterColuna(linha, mapping.colunaNome)?.trim() ?: ""
                val nasc = obterColuna(linha, mapping.colunaNascimento)?.trim() ?: ""
                val pront = obterColuna(linha, mapping.colunaProntuario)?.trim() ?: ""
                if (nome.isBlank() || pront.isBlank()) {
                    ignorados++
                    continue
                }

                val extras = mapping.extrasConfigurados().map { (idx, ex) ->
                    idx to (ex.titulo to (obterColuna(linha, ex.coluna)?.trim() ?: ""))
                }
                val ex1 = extras.find { it.first == 1 }?.second
                val ex2 = extras.find { it.first == 2 }?.second
                val ex3 = extras.find { it.first == 3 }?.second

                pacientes.add(PatientEntity(
                    prontuario = pront,
                    nome = nome,
                    nomeSearch = CsvImporter.normalizarParaBusca(nome),
                    nascimento = nasc,
                    campoExtra1Titulo = ex1?.first?.takeIf { it.isNotBlank() },
                    campoExtra1Valor = ex1?.second?.takeIf { it.isNotBlank() },
                    campoExtra2Titulo = ex2?.first?.takeIf { it.isNotBlank() },
                    campoExtra2Valor = ex2?.second?.takeIf { it.isNotBlank() },
                    campoExtra3Titulo = ex3?.first?.takeIf { it.isNotBlank() },
                    campoExtra3Valor = ex3?.second?.takeIf { it.isNotBlank() },
                    syncTimestamp = agora
                ))
            } catch (e: Exception) {
                ignorados++
            }
        }

        // 4. Persiste no Room (substitui completamente)
        val dao = AppDatabase.get(context).patientDao()
        dao.apagarTudo()
        // Insere em lotes de 500 pra não estourar memória
        pacientes.chunked(500).forEach { dao.inserir(it) }

        config.ultimoSyncCsv = agora
        return Resultado(true, pacientesCarregados = pacientes.size, pacientesIgnorados = ignorados,
            mensagem = "Separador detectado: '${resultado.separadorUsado}'")
    }

    private fun obterColuna(linha: List<String>, numero1Indexed: Int): String? {
        if (numero1Indexed <= 0) return null
        val idx = numero1Indexed - 1
        return if (idx < linha.size) linha[idx] else null
    }

    /**
     * Lê o .csv mais recente de uma pasta local (SAF/tree URI) e copia para o
     * cache para parse. Retorna null se não houver .csv.
     */
    private fun copiarCsvMaisRecenteLocal(uriStr: String, navegarDatabase: Boolean): File? {
        var pasta = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriStr))
            ?: throw Exception("Pasta inválida")
        if (navegarDatabase) {
            pasta = pasta.findFile("PhotoID_RT")?.findFile("DATABASE")
                ?: throw Exception("PhotoID_RT/DATABASE não encontrada (crie e sincronize o CSV)")
        }
        val csv = pasta.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".csv", ignoreCase = true) == true) }
            .maxByOrNull { it.lastModified() }
            ?: return null
        val destino = File(context.cacheDir, "csv_local_${System.currentTimeMillis()}.csv")
        context.contentResolver.openInputStream(csv.uri)?.use { entrada ->
            destino.outputStream().use { entrada.copyTo(it) }
        } ?: throw Exception("Não foi possível abrir o CSV")
        return destino
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

    /**
     * Lista a pasta SMB e baixa o .csv com timestamp mais recente.
     */
    private fun baixarCsvMaisRecente(host: String, share: String, subpasta: String): File? {
        val cfg = SmbConfig.builder().withTimeout(15_000, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val smbClient = SMBClient(cfg)

        smbClient.connect(host).use { conn ->
            val auth = AuthenticationContext(
                config.smbUsuario,
                credentials.obterSenha().toCharArray(),
                config.smbDominio
            )
            val session: Session = conn.authenticate(auth)
            val diskShare = session.connectShare(share) as DiskShare

            // Lista arquivos na pasta
            val arquivosCsv = diskShare.list(subpasta).filter { fi ->
                val nome = fi.fileName
                nome.lowercase().endsWith(".csv") && !fi.fileName.startsWith(".")
            }

            if (arquivosCsv.isEmpty()) return null

            // Pega o mais recente pelo lastWriteTime
            val maisRecente = arquivosCsv.maxByOrNull { it.lastWriteTime.toDate().time } ?: return null
            val nomeRemoto = maisRecente.fileName
            val caminhoRemoto = if (subpasta.isEmpty()) nomeRemoto else "$subpasta\\$nomeRemoto"

            // Baixa
            val destino = File(context.filesDir, "csv_baixado.csv")
            if (destino.exists()) destino.delete()

            diskShare.openFile(
                caminhoRemoto,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { remote ->
                destino.outputStream().use { saida ->
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
            return destino
        }
    }
}
