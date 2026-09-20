package com.radioterapia.ai.sync

import android.content.Context
import com.radioterapia.ai.security.CredentialStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Os perfis de sincronização em disco, e as senhas deles fora dele.
 *
 * JSON no `filesDir`, como o `PatientCache` — mesma casa, mesma rotina de
 * backup antes de reescrever. O `filesDir` some na desinstalação, e é onde
 * configuração de aparelho deve morar: perfil de sincronização é do tablet, não
 * do paciente, e não deve sobreviver a uma reinstalação que o técnico fez
 * justamente para limpar o aparelho.
 *
 * A SENHA NÃO ESTÁ NESTE ARQUIVO. Ela vive no [CredentialStore], indexada pelo
 * id do perfil. Apagar o perfil aqui apaga a senha lá — ver [salvarTodos].
 */
class PerfilStore(private val context: Context) {

    private val arquivo: File by lazy {
        File(File(context.filesDir, "sync").apply { mkdirs() }, "perfis.json")
    }

    private val credenciais by lazy { CredentialStore(context) }

    fun listar(): List<PerfilSync> {
        if (!arquivo.exists()) return emptyList()
        return try {
            val raiz = JSONObject(arquivo.readText())
            val arr = raiz.optJSONArray("perfis") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                try { PerfilSync.deJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            // JSON quebrado não derruba a tela de configurações: sem perfil, a
            // sincronização simplesmente não roda, que é o estado de fábrica.
            emptyList()
        }
    }

    fun obter(id: String): PerfilSync? = listar().firstOrNull { it.id == id }

    /**
     * Grava a lista inteira e limpa o que sobrou dos perfis que saíram.
     *
     * Gravar tudo de uma vez, e não perfil a perfil, é o que garante que a poda
     * das senhas e dos índices enxergue a lista final. Salvar um perfil de cada
     * vez deixaria a poda rodando sobre um estado intermediário, e ela apagaria
     * a senha de um perfil que ainda vai ser regravado.
     */
    fun salvarTodos(perfis: List<PerfilSync>) {
        val arr = JSONArray()
        perfis.forEach { arr.put(it.paraJson()) }
        val raiz = JSONObject().apply {
            put("versao", VERSAO_SCHEMA)
            put("perfis", arr)
        }
        // BACKUP ANTES DE REESCREVER, como o PatientCache faz. Gravação
        // interrompida deixa JSON truncado, e JSON truncado é a lista inteira
        // perdida — inclusive os endereços de servidor que alguém da TI do
        // hospital levou uma tarde para descobrir.
        if (arquivo.exists()) {
            try { arquivo.copyTo(File(arquivo.parentFile, "perfis.bak.json"), overwrite = true) }
            catch (_: Exception) { }
        }
        arquivo.writeText(raiz.toString(2))

        val vivos = perfis.map { it.id }.toSet()
        credenciais.podarSenhasOrfas(vivos)
        IndiceEnviados.podar(context, vivos)
    }

    /** Insere ou substitui um perfil, preservando a ordem dos demais. */
    fun salvar(perfil: PerfilSync) {
        val atuais = listar().toMutableList()
        val i = atuais.indexOfFirst { it.id == perfil.id }
        if (i >= 0) atuais[i] = perfil else atuais.add(perfil)
        salvarTodos(atuais)
    }

    fun remover(id: String) {
        salvarTodos(listar().filterNot { it.id == id })
    }

    fun senha(id: String): String = credenciais.obterSenhaPerfil(id)

    fun salvarSenha(id: String, senha: String) = credenciais.salvarSenhaPerfil(id, senha)

    /**
     * Um perfil novo, já com id.
     *
     * O id nasce aqui e não muda mais: ele é a chave da senha no cofre e do
     * índice de enviados em disco. Derivá-lo do nome faria renomear o perfil
     * perder a senha e o histórico — e renomear é a primeira coisa que se faz
     * quando o segundo destino entra.
     */
    fun novo(nome: String, tipo: PerfilSync.Tipo): PerfilSync =
        PerfilSync(id = UUID.randomUUID().toString().take(12), nome = nome, tipo = tipo)

    companion object {
        private const val VERSAO_SCHEMA = 1
    }
}
