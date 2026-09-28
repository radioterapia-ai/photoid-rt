package com.radioterapia.ai.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.radioterapia.ai.BaseActivity
import com.radioterapia.ai.R
import com.radioterapia.ai.sync.LogConexao
import com.radioterapia.ai.sync.PerfilStore
import com.radioterapia.ai.sync.PerfilSync
import com.radioterapia.ai.sync.SyncWorker
import com.radioterapia.ai.sync.destino.Destinos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O formulário de um destino de sincronização.
 *
 * O BOTÃO DE TESTE É O CENTRO DESTA TELA, e não o de salvar. Configuração de
 * servidor errada não dá erro na hora: dá silêncio, e o silêncio parece
 * sucesso. Quem termina o formulário precisa sair daqui sabendo se o arquivo
 * chega do outro lado — por isso o teste grava de verdade, e por isso o
 * relatório é copiável: quem resolve não está na sala.
 */
class SyncPerfilActivity : BaseActivity() {

    private lateinit var store: PerfilStore
    private var perfil: PerfilSync? = null
    private var safUri: String = ""
    private var ultimoLog: String = ""

    private val tipos = listOf(
        PerfilSync.Tipo.SMB to "SMB / Windows",
        PerfilSync.Tipo.WEBDAV to "WebDAV",
        PerfilSync.Tipo.FTP to "FTP",
        PerfilSync.Tipo.SFTP to "SFTP",
        PerfilSync.Tipo.SAF to "Google Drive / OneDrive / Dropbox",
    )
    private val protocolos = listOf("SMB2", "SMB3", "SMB1")

    private val escolherPasta = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // PERMISSÃO PERSISTENTE, senão o acesso morre quando a Activity morre —
        // e a sincronização roda justamente com o app fechado.
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        safUri = uri.toString()
        findViewById<TextView>(R.id.txtSyncPastaSaf).text =
            com.radioterapia.ai.util.StorageLocal.treeUriParaCaminho(uri) ?: uri.toString()
    }

    override fun tituloPadrao(): String = getString(R.string.sync_profile_title)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_perfil)
        store = PerfilStore(this)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        perfil = if (id.isBlank()) store.novo(getString(R.string.sync_new_name), PerfilSync.Tipo.SMB)
                 else store.obter(id)
        val p = perfil ?: run { finish(); return }

        montarSeletores(p)
        preencher(p)
        ligarDicas()

        findViewById<Button>(R.id.btnSyncEscolherPasta).setOnClickListener {
            try { escolherPasta.launch(null) }
            catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_generic,
                    e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnSyncTestar).setOnClickListener { testar() }
        findViewById<Button>(R.id.btnSyncCopiarLog).setOnClickListener { copiarLog() }
        findViewById<Button>(R.id.btnSyncSalvar).setOnClickListener { salvar(sair = true) }
        findViewById<Button>(R.id.btnSyncRemover).setOnClickListener { confirmarRemocao() }
    }

    // ------------------------------------------------------------- montagem

    private fun montarSeletores(p: PerfilSync) {
        val spTipo = findViewById<Spinner>(R.id.spSyncTipo)
        spTipo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            tipos.map { it.second })
        spTipo.setSelection(tipos.indexOfFirst { it.first == p.tipo }.coerceAtLeast(0))
        spTipo.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?,
                                        pos: Int, id: Long) = mostrarBlocos(tipos[pos].first)
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val spProt = findViewById<Spinner>(R.id.spSyncProtocolo)
        spProt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, protocolos)
        spProt.setSelection(protocolos.indexOf(p.protocolo).coerceAtLeast(0))
    }

    /**
     * Mostra só o que o tipo escolhido usa.
     *
     * Campos vazios de outro protocolo não atrapalham o motor — ele não os lê —,
     * mas atrapalham quem preenche: um campo "Share" visível num destino WebDAV
     * é uma pergunta sem resposta, e alguém vai inventar uma.
     */
    private fun mostrarBlocos(tipo: PerfilSync.Tipo) {
        fun ver(id: Int, visivel: Boolean) {
            findViewById<View>(id).visibility = if (visivel) View.VISIBLE else View.GONE
        }
        val servidor = tipo == PerfilSync.Tipo.SMB || tipo == PerfilSync.Tipo.FTP ||
                       tipo == PerfilSync.Tipo.SFTP
        ver(R.id.blocoServidor, servidor)
        ver(R.id.blocoProtocolo, tipo == PerfilSync.Tipo.SMB)
        ver(R.id.blocoShare, tipo == PerfilSync.Tipo.SMB)
        ver(R.id.blocoDominio, tipo == PerfilSync.Tipo.SMB)
        ver(R.id.blocoWebdav, tipo == PerfilSync.Tipo.WEBDAV)
        ver(R.id.blocoSaf, tipo == PerfilSync.Tipo.SAF)
        ver(R.id.blocoCredenciais, tipo != PerfilSync.Tipo.SAF)
        ver(R.id.blocoCaminho, true)
        ver(R.id.blocoDireta, servidor || tipo == PerfilSync.Tipo.WEBDAV)
    }

    private fun preencher(p: PerfilSync) {
        findViewById<EditText>(R.id.edtSyncNome).setText(p.nome)
        findViewById<SwitchMaterial>(R.id.swSyncAtivo).isChecked = p.ativo
        findViewById<EditText>(R.id.edtSyncHost).setText(p.host)
        findViewById<EditText>(R.id.edtSyncPorta).setText(if (p.porta > 0) p.porta.toString() else "")
        findViewById<EditText>(R.id.edtSyncShare).setText(p.share)
        findViewById<EditText>(R.id.edtSyncUrl).setText(p.urlBase)
        findViewById<EditText>(R.id.edtSyncUsuario).setText(p.usuario)
        findViewById<EditText>(R.id.edtSyncDominio).setText(p.dominio)
        findViewById<EditText>(R.id.edtSyncSenha).setText(store.senha(p.id))
        findViewById<EditText>(R.id.edtSyncCaminho).setText(p.caminhoRemoto)
        findViewById<CheckBox>(R.id.chkSyncDireta).isChecked = p.injecaoDireta
        safUri = p.safUri
        findViewById<TextView>(R.id.txtSyncPastaSaf).text =
            if (safUri.isBlank()) getString(R.string.sync_no_folder)
            else (try { com.radioterapia.ai.util.StorageLocal.treeUriParaCaminho(
                android.net.Uri.parse(safUri)) } catch (_: Exception) { null } ?: safUri)
        mostrarBlocos(p.tipo)
    }

    private fun coletar(): PerfilSync {
        val base = perfil!!
        val tipo = tipos[findViewById<Spinner>(R.id.spSyncTipo).selectedItemPosition].first
        fun txt(id: Int) = findViewById<EditText>(id).text?.toString()?.trim().orEmpty()
        return base.copy(
            nome = txt(R.id.edtSyncNome).ifBlank { getString(R.string.sync_new_name) },
            tipo = tipo,
            ativo = findViewById<SwitchMaterial>(R.id.swSyncAtivo).isChecked,
            host = txt(R.id.edtSyncHost),
            porta = txt(R.id.edtSyncPorta).toIntOrNull() ?: 0,
            protocolo = protocolos[findViewById<Spinner>(R.id.spSyncProtocolo).selectedItemPosition],
            share = txt(R.id.edtSyncShare),
            urlBase = txt(R.id.edtSyncUrl),
            usuario = txt(R.id.edtSyncUsuario),
            dominio = txt(R.id.edtSyncDominio),
            caminhoRemoto = txt(R.id.edtSyncCaminho),
            injecaoDireta = findViewById<CheckBox>(R.id.chkSyncDireta).isChecked,
            safUri = safUri,
        )
    }

    // ---------------------------------------------------------------- ações

    private fun salvar(sair: Boolean) {
        val p = coletar()
        // O DESTINO MUDOU, ENTÃO O HISTÓRICO NÃO VALE MAIS. Pasta remota nova é
        // um destino vazio; o índice do destino anterior diria que já está tudo
        // lá, e nada subiria — um erro que só aparece quando alguém procura uma
        // foto no servidor e não a encontra.
        val anterior = store.obter(p.id)
        if (anterior != null && destinoMudou(anterior, p)) {
            com.radioterapia.ai.sync.IndiceEnviados(this, p.id).limpar()
        }
        store.salvar(p)
        store.salvarSenha(p.id, findViewById<EditText>(R.id.edtSyncSenha).text?.toString().orEmpty())
        perfil = p
        SyncWorker.reprogramar(this)
        if (sair) {
            Toast.makeText(this, R.string.sync_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun destinoMudou(a: PerfilSync, b: PerfilSync): Boolean =
        a.tipo != b.tipo || a.host != b.host || a.share != b.share ||
        a.urlBase != b.urlBase || a.safUri != b.safUri || a.caminhoRemoto != b.caminhoRemoto

    private fun confirmarRemocao() {
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.sync_delete)
            .setMessage(R.string.sync_delete_q)
            .setPositiveButton(R.string.sync_delete) { _, _ ->
                perfil?.let { store.remover(it.id) }
                SyncWorker.reprogramar(this)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
        // BUTTON_POSITIVE é -1, não 1. Com o literal, pintarBotoesDialog chamava
        // getButton(1), que devolve null, e pintarBotao é nulo-seguro — então o
        // botão que apaga um destino inteiro saía cinza, igual ao "Cancelar",
        // sem erro e sem log. Era o único dos cinco pontos de chamada do app que
        // passava número cru em vez da constante.
        mostrarDialogPintado(b, destrutivo = android.content.DialogInterface.BUTTON_POSITIVE)
    }

    private fun testar() {
        salvar(sair = false)
        val p = perfil ?: return
        val senha = findViewById<EditText>(R.id.edtSyncSenha).text?.toString().orEmpty()
        val resumo = findViewById<TextView>(R.id.txtSyncTesteResumo)
        val logView = findViewById<TextView>(R.id.txtSyncLog)
        val btnCopiar = findViewById<Button>(R.id.btnSyncCopiarLog)

        resumo.visibility = View.VISIBLE
        /*
            RESULTADO DO TESTE: fundo da escada tonal, texto de primeiro plano.

            Antes o fundo era pastel (#FFFDE7 / #C8E6C9 / #FFCDD2) e o texto
            herdava text_primary (#FFFFFF): 1,07:1 no amarelo, 1,34:1 no verde,
            1,41:1 no vermelho. Nao era contraste baixo — era texto invisivel, e
            justamente a frase que o KDoc desta tela chama de centro dela. De
            quebra, amarelo e vermelho pastel como estado de sistema e o que a
            Regra do Alerta Clinico proibe.
         */
        resumo.text = getString(R.string.sync_testing)
        resumo.setBackgroundColor(ContextCompat.getColor(this@SyncPerfilActivity, R.color.bg_elevated))
        resumo.setTextColor(ContextCompat.getColor(this@SyncPerfilActivity, R.color.text_secondary))
        logView.visibility = View.GONE
        btnCopiar.visibility = View.GONE

        CoroutineScope(Dispatchers.Main).launch {
            val log = LogConexao()
            val ok = withContext(Dispatchers.IO) {
                val d = Destinos.criar(this@SyncPerfilActivity, p, senha)
                try { d.testar(log) } finally { d.fechar() }
            }
            ultimoLog = log.texto()
            resumo.text = getString(if (ok) R.string.sync_test_ok else R.string.sync_test_fail)
            resumo.setBackgroundColor(ContextCompat.getColor(this@SyncPerfilActivity, R.color.bg_elevated))
            resumo.setTextColor(ContextCompat.getColor(this@SyncPerfilActivity,
                if (ok) R.color.confirm_green else R.color.error_red_fg))
            // O RELATÓRIO SÓ APARECE NA FALHA. No sucesso ele seria cinquenta
            // linhas de texto técnico embaixo de um "deu certo" — ruído que
            // treina a pessoa a ignorar a área onde, um dia, vai estar a
            // explicação de que ela precisa.
            logView.visibility = if (ok) View.GONE else View.VISIBLE
            btnCopiar.visibility = if (ok) View.GONE else View.VISIBLE
            logView.text = ultimoLog
        }
    }

    private fun copiarLog() {
        if (ultimoLog.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("PhotoID RT", ultimoLog))
        Toast.makeText(this, R.string.sync_log_copied, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- dicas

    private fun ligarDicas() {
        fun dica(idBotao: Int, titulo: Int, corpo: Int) {
            findViewById<TextView>(idBotao).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(titulo).setMessage(corpo)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
        dica(R.id.tipHost, R.string.sync_f_host, R.string.sync_tip_host)
        dica(R.id.tipShare, R.string.sync_f_share, R.string.sync_tip_share)
        dica(R.id.tipUser, R.string.sync_f_user, R.string.sync_tip_user)
        dica(R.id.tipDomain, R.string.sync_f_domain, R.string.sync_tip_domain)
        dica(R.id.tipPath, R.string.sync_f_path, R.string.sync_tip_path)
        dica(R.id.tipUrl, R.string.sync_f_url, R.string.sync_tip_url)
        dica(R.id.tipSaf, R.string.sync_f_folder, R.string.sync_tip_saf)
        dica(R.id.tipDirect, R.string.sync_f_direct, R.string.sync_tip_direct)
    }

    companion object {
        const val EXTRA_ID = "perfil_id"
    }
}
