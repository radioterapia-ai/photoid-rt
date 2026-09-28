package com.radioterapia.ai.sync.destino

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilSync
import java.io.File

/**
 * Destino SAF — qualquer nuvem que já esteja instalada no tablet.
 *
 * POR QUE ISTO VALE MAIS QUE INTEGRAR CADA NUVEM
 * Integrar o Google Drive significa registrar o app no console do Google, pedir
 * verificação de escopo sensível, e guardar um segredo de cliente — que em app
 * distribuído por APK está, na prática, publicado. Integrar o OneDrive
 * significa a mesma coisa de novo, no portal da Microsoft. E ainda assim a
 * conta seria a da clínica, com credencial digitada no nosso formulário.
 *
 * O seletor do Android resolve isso sem nada disso: o técnico escolhe a pasta
 * no app da nuvem que a clínica já usa e já tem logada, e o sistema devolve uma
 * permissão persistente para AQUELA pasta. Nenhuma credencial passa por aqui,
 * nenhum escopo é pedido, e o app não sabe — nem precisa saber — de que serviço
 * a pasta é.
 *
 * O PREÇO, e ele é real: o provedor pode revogar a permissão (limpeza de dados,
 * troca de conta, reinstalação do app da nuvem), e não há aviso. O erro aparece
 * como "não foi possível gravar", e a resposta é reescolher a pasta. Está dito
 * na tradução de erro abaixo.
 */
class DestinoSaf(
    private val context: Context,
    private val perfil: PerfilSync,
) : DestinoSync {

    private fun raiz(): DocumentFile? =
        try { DocumentFile.fromTreeUri(context, Uri.parse(perfil.safUri)) }
        catch (_: Exception) { null }

    /**
     * Desce (criando o que faltar) até a pasta do caminho.
     *
     * `findFile` por nome é O(n) no número de filhos em muitos provedores — ele
     * lista a pasta inteira. Numa varredura com milhares de fotos, refazer esse
     * caminho por arquivo seria lento a ponto de a sincronização nunca terminar.
     * Por isso o cache abaixo: dentro de uma varredura, cada pasta é resolvida
     * uma vez só.
     */
    private val cachePastas = HashMap<String, DocumentFile?>()

    private fun pasta(caminho: String, criar: Boolean): DocumentFile? {
        val chave = caminho.trim('/', '\\')
        cachePastas[chave]?.let { return it }
        var atual = raiz() ?: return null
        for (parte in chave.split('/', '\\').filter { it.isNotBlank() }) {
            val existente = atual.findFile(parte)
            atual = when {
                existente != null && existente.isDirectory -> existente
                existente != null -> return null      // há um ARQUIVO com esse nome
                criar -> atual.createDirectory(parte) ?: return null
                else -> return null
            }
        }
        cachePastas[chave] = atual
        return atual
    }

    override fun testar(log: LogConexao): Boolean = try {
        testarInterno(log)
    } catch (e: Exception) {
        log.excecao("SAF", e)
        false
    }

    private fun testarInterno(log: LogConexao): Boolean {
        val r = raiz()
        if (r == null || !r.exists()) {
            log.falha("SAF: a pasta escolhida não está mais acessível")
            log.passo("Escolha a pasta de novo. O app da nuvem pode ter sido reinstalado, " +
                    "ou a conta trocada — nos dois casos a permissão que o sistema deu cai.")
            return false
        }
        log.ok("SAF: pasta acessível — '${r.name ?: perfil.safUri}'")
        if (!r.canWrite()) {
            log.falha("SAF: a permissão concedida é somente de leitura")
            return false
        }
        val destino = pasta(perfil.caminhoRemoto, criar = true)
        if (destino == null) {
            log.falha("SAF: não foi possível criar '${perfil.caminhoRemoto}'")
            return false
        }
        log.passo("SAF: gravando arquivo de prova")
        val prova = destino.createFile("application/octet-stream",
            ".photoid_teste_${System.currentTimeMillis()}")
        if (prova == null) {
            log.falha("SAF: o provedor recusou criar o arquivo")
            return false
        }
        log.ok("SAF: gravação confirmada")
        if (prova.delete()) log.ok("SAF: arquivo de prova removido")
        else log.passo("SAF: não foi possível remover o arquivo de prova. Ele fica lá, vazio.")
        return true
    }

    override fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
                        log: LogConexao?): DestinoSync.Resultado {
        return try {
            val alvoPasta = pasta(
                listOf(perfil.caminhoRemoto, caminhoRelativo).filter { it.isNotBlank() }
                    .joinToString("/"), criar = true)
                ?: return DestinoSync.Resultado.recusado(
                    "pasta de destino inacessível — reescolha a pasta")

            // SUBSTITUIR, e não acumular. Sem apagar o anterior, o provedor cria
            // "NOME (1).jpg" e a pasta do paciente enche de duplicatas a cada
            // reenvio. Este é o único lugar do motor que apaga algo — e o que
            // ele apaga é a versão anterior DESTE MESMO arquivo, que estamos
            // sobrescrevendo no mesmo ato.
            alvoPasta.findFile(nomeRemoto)?.let { if (it.isFile) it.delete() }

            val novo = alvoPasta.createFile(tipoMime(nomeRemoto), nomeRemoto)
                ?: return DestinoSync.Resultado.falha("o provedor recusou criar o arquivo")
            context.contentResolver.openOutputStream(novo.uri)?.use { saida ->
                local.inputStream().use { entrada -> entrada.copyTo(saida, 64 * 1024) }
            } ?: return DestinoSync.Resultado.falha("não foi possível abrir o arquivo para escrita")
            DestinoSync.Resultado.OK
        } catch (e: SecurityException) {
            log?.excecao("SAF", e)
            DestinoSync.Resultado.recusado("permissão revogada — reescolha a pasta")
        } catch (e: Exception) {
            log?.excecao("SAF", e)
            DestinoSync.Resultado.falha("${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}")
        }
    }

    private fun tipoMime(nome: String): String = when {
        nome.endsWith(".jpg", true) || nome.endsWith(".jpeg", true) -> "image/jpeg"
        nome.endsWith(".png", true) -> "image/png"
        nome.endsWith(".pdf", true) -> "application/pdf"
        nome.endsWith(".csv", true) -> "text/csv"
        nome.endsWith(".json", true) -> "application/json"
        else -> "application/octet-stream"
    }

    override fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long = try {
        pasta(listOf(perfil.caminhoRemoto, caminhoRelativo).filter { it.isNotBlank() }
            .joinToString("/"), criar = false)
            ?.findFile(nomeRemoto)?.length() ?: -1L
    } catch (_: Exception) { -1L }

    override fun fechar() {
        cachePastas.clear()
    }
}
