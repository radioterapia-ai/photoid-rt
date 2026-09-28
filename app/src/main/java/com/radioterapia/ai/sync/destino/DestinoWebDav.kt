package com.radioterapia.ai.sync.destino

import android.util.Base64
import com.radioterapia.ai.sync.DestinoSync
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilSync
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Destino WebDAV — NAS de prateleira, Nextcloud, IIS com WebDAV ligado.
 *
 * SEM BIBLIOTECA, de propósito. WebDAV é HTTP com dois verbos a mais: `PUT`
 * grava o arquivo e `MKCOL` cria a pasta. Tudo o que falta o
 * `HttpURLConnection` da plataforma já faz. Trazer uma biblioteca de WebDAV
 * custaria peso no APK e uma dependência a auditar, para não ganhar nada.
 *
 * A MESMA INJEÇÃO DIRETA do SMB, pela mesma razão: tenta o `PUT` no caminho
 * final e só cria a cadeia de pastas se o servidor responder 404 ou 409. O 409
 * é o WebDAV dizendo, especificamente, "a coleção pai não existe" — é o código
 * que a RFC 4918 reserva para isso.
 */
class DestinoWebDav(
    private val perfil: PerfilSync,
    private val senha: String,
) : DestinoSync {

    private val autorizacao: String? by lazy {
        if (perfil.usuario.isBlank()) null
        else "Basic " + Base64.encodeToString(
            "${perfil.usuario.trim()}:$senha".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    /** Junta a URL base ao caminho, sem barra dupla e sem barra faltando. */
    private fun url(vararg partes: String): String {
        val base = perfil.urlBase.trim().trimEnd('/')
        val resto = partes.filter { it.isNotBlank() }
            .joinToString("/") { it.trim('/').replace('\\', '/') }
        // Espaço e acento em nome de pasta de paciente são a regra, não a
        // exceção. Sem codificar, o servidor devolve 400 e a mensagem fala de
        // sintaxe de requisição, não do nome do paciente.
        val codificado = resto.split("/").joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return if (codificado.isEmpty()) base else "$base/$codificado"
    }

    private fun abrir(endereco: String, metodo: String): HttpURLConnection {
        val c = URL(endereco).openConnection() as HttpURLConnection
        c.requestMethod = metodo
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = false
        autorizacao?.let { c.setRequestProperty("Authorization", it) }
        return c
    }

    override fun testar(log: LogConexao): Boolean = try {
        testarInterno(log)
    } catch (e: Exception) {
        log.excecao("WebDAV", e)
        false
    }

    private fun testarInterno(log: LogConexao): Boolean {
        log.passo("WebDAV: base '${perfil.urlBase}'")
        if (autorizacao == null) log.passo("WebDAV: sem usuário — acesso anônimo")
        else { log.passo("WebDAV: usuário '${perfil.usuario}' (Basic)"); log.senha(senha) }
        val nomeProva = ".photoid_teste_${System.currentTimeMillis()}.tmp"
        val alvo = url(perfil.caminhoRemoto, nomeProva)
        log.passo("WebDAV: PUT em '$alvo'")
        var cod = put(alvo, null)
        if (cod == 404 || cod == 409) {
            log.passo("WebDAV: respondeu $cod — a coleção pai não existe. Criando.")
            criarColecoes(perfil.caminhoRemoto, log)
            cod = put(alvo, null)
        }
        if (cod !in 200..299) {
            log.falha("WebDAV: PUT devolveu $cod")
            log.passo(dica(cod))
            return false
        }
        log.ok("WebDAV: gravação confirmada ($cod)")
        val delCod = try { abrir(alvo, "DELETE").let { it.connect(); it.responseCode } }
                     catch (_: Exception) { -1 }
        if (delCod in 200..299) log.ok("WebDAV: arquivo de prova removido")
        else log.passo("WebDAV: não foi possível remover o arquivo de prova ($delCod). " +
                "Ele fica lá, com 0 byte.")
        return true
    }

    private fun put(endereco: String, local: File?): Int {
        val c = abrir(endereco, "PUT")
        c.doOutput = true
        // ENVIO EM FLUXO, e não pelo buffer interno do HttpURLConnection. Sem
        // isto, a foto inteira é acumulada em memória antes de sair — e uma
        // série de fotos numa varredura derrubaria o app por falta de heap no
        // tablet, num erro que aponta a alocação e não a causa.
        if (local != null) {
            c.setFixedLengthStreamingMode(local.length())
            c.setRequestProperty("Content-Type", "application/octet-stream")
        } else {
            c.setFixedLengthStreamingMode(0L)
        }
        return try {
            c.outputStream.use { saida ->
                local?.inputStream()?.use { entrada -> entrada.copyTo(saida, 64 * 1024) }
            }
            c.responseCode
        } finally {
            try { c.disconnect() } catch (_: Exception) { }
        }
    }

    /** `MKCOL` em cada nível. 405 significa "já existe", e aqui isso é sucesso. */
    private fun criarColecoes(caminho: String, log: LogConexao?) {
        var acumulado = ""
        for (parte in caminho.split('/', '\\').filter { it.isNotBlank() }) {
            acumulado = if (acumulado.isEmpty()) parte else "$acumulado/$parte"
            val c = abrir(url(acumulado), "MKCOL")
            try {
                c.connect()
                val cod = c.responseCode
                if (cod in 200..299) log?.ok("WebDAV: criada '$acumulado'")
                else if (cod != 405) log?.passo("WebDAV: MKCOL '$acumulado' devolveu $cod")
            } catch (e: Exception) {
                log?.excecao("WebDAV MKCOL '$acumulado'", e)
            } finally {
                try { c.disconnect() } catch (_: Exception) { }
            }
        }
    }

    override fun enviar(local: File, caminhoRelativo: String, nomeRemoto: String,
                        log: LogConexao?): DestinoSync.Resultado {
        return try {
            val alvo = url(perfil.caminhoRemoto, caminhoRelativo, nomeRemoto)
            var cod = put(alvo, local)
            if (cod == 404 || cod == 409) {
                criarColecoes(listOf(perfil.caminhoRemoto, caminhoRelativo)
                    .filter { it.isNotBlank() }.joinToString("/"), log)
                cod = put(alvo, local)
            }
            when {
                cod in 200..299 -> DestinoSync.Resultado.OK
                cod == 401 || cod == 403 -> DestinoSync.Resultado.recusado("HTTP $cod — ${dica(cod)}")
                cod == 507 -> DestinoSync.Resultado.recusado("HTTP 507 — sem espaço no servidor")
                else -> DestinoSync.Resultado.falha("HTTP $cod")
            }
        } catch (e: Exception) {
            log?.excecao("WebDAV", e)
            DestinoSync.Resultado.falha("${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}")
        }
    }

    override fun tamanhoRemoto(caminhoRelativo: String, nomeRemoto: String): Long = try {
        val c = abrir(url(perfil.caminhoRemoto, caminhoRelativo, nomeRemoto), "HEAD")
        c.connect()
        val tam = if (c.responseCode in 200..299) c.getHeaderFieldLong("Content-Length", -1L) else -1L
        try { c.disconnect() } catch (_: Exception) { }
        tam
    } catch (_: Exception) { -1L }

    override fun fechar() {
        // Sem sessão para encerrar: cada requisição HTTP abre e fecha a sua.
    }

    private fun dica(cod: Int): String = when (cod) {
        401 -> "Usuário ou senha recusados. Em Nextcloud e afins, a senha do app " +
               "costuma ser uma senha de aplicativo, e não a de login."
        403 -> "Autenticou, mas a conta não pode escrever aí. É permissão no servidor."
        404, 409 -> "A pasta de destino não existe e não foi possível criá-la."
        405 -> "O servidor não aceita PUT nesse endereço. Confira se o WebDAV está " +
               "ligado e se a URL aponta a pasta, e não a página de login."
        507 -> "Sem espaço no servidor."
        else -> "Resposta HTTP $cod — copie o log inteiro."
    }
}
