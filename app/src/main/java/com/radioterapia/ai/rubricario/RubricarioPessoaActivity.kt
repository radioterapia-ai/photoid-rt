package com.radioterapia.ai.rubricario

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.radioterapia.ai.AppConfig
import com.radioterapia.ai.BaseActivity
import com.radioterapia.ai.R

/**
 * Adicionar ou editar uma pessoa do rubricário.
 *
 * Tela própria, e não diálogo, porque a rubrica precisa de área: assinar com o
 * dedo num quadro pequeno produz um traço que não se parece com a assinatura da
 * pessoa — e a folha existe para identificá-la.
 */
class RubricarioPessoaActivity : BaseActivity() {

    override fun tituloPadrao(): String = getString(R.string.rub_add)

    private lateinit var store: RubricarioStore
    private lateinit var cargos: List<Pair<String, String>>   // (cargo, registro)
    private var idEdicao: String? = null

    /**
     * Bloco em que a pessoa entra. Vem da tela que abriu esta — a de
     * Configurações já tem um bloco selecionado, e perguntar de novo aqui
     * repetiria uma escolha que o usuário acabou de fazer.
     */
    private var blocoId: String = RubricarioStore.ID_PADRAO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rubricario_pessoa)

        store = RubricarioStore(this)
        val config = AppConfig(this)
        cargos = config.rubricarioCargos.split("\n")
            .map { it.trim() }.filter { it.isNotBlank() }
            .map { RubricarioStore.separarCargo(it) }

        val spCargo = findViewById<Spinner>(R.id.spCargo)
        val edtNome = findViewById<EditText>(R.id.edtNome)
        val edtRegistro = findViewById<EditText>(R.id.edtRegistro)
        val txtRotuloReg = findViewById<TextView>(R.id.txtRotuloRegistro)
        val assinatura = findViewById<AssinaturaView>(R.id.viewAssinatura)

        spCargo.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            cargos.map { it.first })

        // O rótulo do campo de registro SEGUE o cargo: quem cadastra um físico vê
        // "CNEN", não "Número do conselho". Sem isso o operador precisa lembrar
        // qual sigla vale para qual cargo, que é conhecimento que o app já tem.
        spCargo.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?,
                                        pos: Int, id: Long) {
                val reg = cargos.getOrNull(pos)?.second.orEmpty()
                txtRotuloReg.text = if (reg.isBlank()) getString(R.string.rub_registro) else reg
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Modo edição: carrega o que já existe, INCLUSIVE a rubrica.
        //
        // Antes o quadro abria em branco — o traço não é reconstituível a partir
        // do PNG, então não havia o que redesenhar. Mas quem editava um nome via
        // um quadro vazio e concluía que a rubrica faltava, e assinava de novo
        // sem precisar. A imagem gravada aparece esmaecida: serve de referência,
        // não é traço, e continua valendo enquanto ninguém assinar por cima.
        blocoId = intent.getStringExtra(EXTRA_BLOCO)
            ?.takeIf { it.isNotBlank() } ?: RubricarioStore.ID_PADRAO
        idEdicao = intent.getStringExtra(EXTRA_ID)
        if (idEdicao != null) {
            store.listar().firstOrNull { it.id == idEdicao }?.let { p ->
                setToolbarTitle(getString(R.string.rub_editar))
                edtNome.setText(p.nome)
                edtRegistro.setText(p.registro)
                val idx = cargos.indexOfFirst { it.first == p.cargo }
                if (idx >= 0) spCargo.setSelection(idx)
                assinatura.definirExistente(store.bitmapAssinatura(p))
                // Editar NÃO muda a pessoa de equipe: o bloco vem do registro,
                // não da tela que abriu. Sem isto, abrir alguém da "Clínica B"
                // a partir de outro bloco a mudaria de equipe ao salvar.
                blocoId = p.blocoId
            }
        }

        // Tocar no quadro com rubrica gravada pergunta antes. O toque não vira
        // traço até a confirmação, senão a rubrica antiga já estaria perdida
        // quando a pergunta aparecesse.
        assinatura.aoTentarSobrescrever = { confirmarSubstituir(assinatura) }

        findViewById<Button>(R.id.btnLimparAssinatura).setOnClickListener {
            if (assinatura.temExistente()) confirmarSubstituir(assinatura)
            else assinatura.limpar()
        }
        findViewById<Button>(R.id.btnCancelar).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSalvar).setOnClickListener {
            val nome = edtNome.text.toString().trim()
            val cargo = cargos.getOrNull(spCargo.selectedItemPosition)?.first.orEmpty()
            if (cargo.isBlank()) {
                Toast.makeText(this, R.string.rub_falta_cargo, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (nome.isBlank()) {
                Toast.makeText(this, R.string.rub_falta_nome, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Rubrica só é exigida no cadastro NOVO. Na edição, quem não assinou
            // de novo mantém a que já tinha.
            val png = assinatura.exportar()
            if (png == null && idEdicao == null) {
                Toast.makeText(this, R.string.rub_falta_assinatura, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // Descartou a rubrica antiga e não assinou outra: salvar aqui
            // manteria em silêncio a rubrica que a pessoa acabou de mandar
            // apagar, e ela sairia da tela achando que trocou.
            if (png == null && rubricaDescartada) {
                Toast.makeText(this, R.string.rub_falta_assinatura, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val salvo = store.salvar(idEdicao, cargo, nome,
                edtRegistro.text.toString().trim(), png, blocoId)
            if (salvo == null) {
                Toast.makeText(this, R.string.export_zip_fail, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, getString(R.string.rub_salvo, nome), Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    /** A rubrica gravada foi descartada nesta edição e precisa ser refeita. */
    private var rubricaDescartada = false

    private fun confirmarSubstituir(assinatura: AssinaturaView) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.rub_substituir_q)
            .setMessage(R.string.rub_substituir_msg)
            .setPositiveButton(R.string.rub_substituir_ok) { _, _ ->
                assinatura.liberarParaNovaRubrica()
                rubricaDescartada = true
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "rub_id"
        const val EXTRA_BLOCO = "rub_bloco"

        fun abrir(origem: Activity, launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
                  id: String? = null,
                  blocoId: String = RubricarioStore.ID_PADRAO) {
            val it = Intent(origem, RubricarioPessoaActivity::class.java)
            if (id != null) it.putExtra(EXTRA_ID, id)
            it.putExtra(EXTRA_BLOCO, blocoId)
            launcher.launch(it)
        }
    }
}
