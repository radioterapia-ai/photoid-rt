# Glossário PhotoID RT — Árabe padrão moderno (MSA)

Uma tradução fixa por termo. Quem traduzir string nova usa esta tabela sem
reabrir a escolha.

Convenções deste idioma:

- MSA compreensível em todo o mundo árabe; nada de dialeto (egípcio, golfo,
  levantino).
- Texto normal, sem marca de direção (RLM/LRM). O layout é RTL pelo recurso
  `values-ar`, não por caractere invisível dentro da string.
- `Time-Out` fica em alfabeto latino mesmo dentro da frase árabe.
- Placeholder (`%1$s`, `%1$d`) preservado com o mesmo número e a mesma ordem.
- Numeral arábico ocidental (0-9), que é o usado em prontuário e em impresso
  hospitalar na maior parte dos serviços.

    termo em portugues | traducao fixa | observacao

simulação | المحاكاة | a sessão de TC de planejamento. Não é imitação nem ensaio: é o procedimento real, e nenhuma outra palavra (تجربة, تمثيل) entra no lugar
posicionamento | الوضعية | como o paciente fica na mesa. "Fotos de posicionamento" = صور الوضعية
paciente | المريض |
prontuário | رقم الملف الطبي | número do paciente no hospital. Nunca السجل sozinho, que colide com "histórico"
registro | رقم التسجيل | o mesmo número do prontuário, na forma curta usada no rótulo do PDF. O número do conselho do profissional é رقم القيد المهني — são coisas diferentes e não podem sair iguais
ficha de posicionamento | ورقة الوضعية | a folha impressa com as fotos do setup. استمارة seria formulário a preencher; esta é folha gerada pelo app
Time-Out | Time-Out | mantido em inglês e em alfabeto latino. É o termo internacional do checklist de segurança da OMS, adotado assim nos serviços; traduzir esconderia o que é
rubricário | سجل التواقيع | a folha da equipe, com nome, cargo, número do conselho e a rubrica de cada um
rubrica | التوقيع المختصر | o árabe não tem palavra consagrada para rubrica; التوقيع sozinho é assinatura por extenso, e المختصر preserva a ideia de iniciais. Usar a forma completa também no cabeçalho de coluna
cargo | المسمى الوظيفي | a função do profissional no rubricário (médico, físico, tecnólogo)
etiqueta | ملصق التعريف | a etiqueta adesiva de identificação do paciente, com os dados e o código de barras, que o ML Kit lê
acessório | أداة التثبيت | dispositivo de imobilização (máscara termoplástica, apoio de joelho, plano inclinado). Nunca ملحق nem إكسسوار, que sugerem item avulso sem função clínica
acessórios | أدوات التثبيت | plural de أداة التثبيت
rosto | الوجه |
foto | صورة | fotografia. Onde houver risco de ler como imagem médica, صورة فوتوغرافية
fotos | صور |
tratamento | العلاج | o módulo em que as fotos da simulação são consultadas dia a dia. A sessão diária é جلسة العلاج
radioterapia | العلاج الإشعاعي | forma dominante em MSA; المعالجة الإشعاعية não é usada aqui, para não alternar
acelerador | المسرع الخطي | sempre com الخطي. المسرع sozinho é acelerador de partículas em geral
equipamento | الجهاز | a máquina que trata o paciente, campo do Time-Out. Não usar المعدات, que é o parque de equipamentos do serviço
sítio | الموضع التشريحي | o sítio anatômico tratado. الموقع sozinho lê como local geográfico
médico | الطبيب | o radio-oncologista responsável. A especialidade por extenso é طبيب الأورام الإشعاعي
físico | الفيزيائي الطبي | sempre com الطبي; sem ele vira físico de qualquer área
tecnólogo | فني العلاج الإشعاعي | quem posiciona o paciente e faz as fotos
dosimetrista | أخصائي قياس الجرعات | não há termo único consagrado em árabe. Esta forma é a mais transparente e evita a transliteração دوزيمتريست, que só circula na fala
enfermagem | التمريض |
clínica | المركز | o serviço ou instituição que usa o app. العيادة sugere consultório ambulatorial pequeno e não descreve um serviço de radioterapia
unidade | الفرع | outra unidade ou filial do mesmo serviço. الوحدة foi descartada porque em radioterapia lê-se como aparelho de tratamento (وحدة العلاج)
cadastro | بيانات المريض | o registro guardado no app: nome, prontuário, nascimento, sexo. O ato de cadastrar é تسجيل
histórico | السجل | a lista de pacientes já atendidos, سجل المرضى. Nunca التاريخ المرضي, que é a história clínica do paciente e mudaria o sentido da tela
configurações | الإعدادات |
arquivar | أرشفة | tira a foto da ficha e mantém o arquivo na pasta do paciente. Excluir é حذف, que apaga do disco. As duas são inconfundíveis em árabe e essa distinção tem que ser mantida em toda tela, botão e mensagem
arquivada | مؤرشفة | "Fotos arquivadas" = الصور المؤرشفة
descartar | تجاهل | vale só para o rascunho ainda não finalizado. Não usar حذف aqui, que fica reservado a excluir arquivo já gravado
finalizar | إنهاء | encerra a simulação e gera a ficha. Não é حفظ (salvar)
carrossel | شريط الصور | sem equivalente consagrado; الشريط الدوّار é jargão de web e não diz nada ao tecnólogo. شريط الصور descreve o que se vê na tela
impressora | الطابعة |
pen-drive | ذاكرة USB | ligada por cabo OTG. فلاشة é coloquial e fica fora
galeria | معرض الصور | a galeria do tablet, fora do app
rolo da câmera | ألبوم الكاميرا | fotos tiradas com a câmera do tablet fora do app; é uma das origens ao adicionar foto, ao lado de الصور المؤرشفة
nascimento | تاريخ الميلاد | sempre com تاريخ. الميلاد sozinho não funciona como rótulo de campo
sexo | الجنس | valores ذكر / أنثى
idade | العمر |
data da simulação | تاريخ المحاكاة |
nova simulação | محاكاة جديدة | novo curso no mesmo paciente, na mesma pasta
reirradiação | إعادة التشعيع |
rascunho | مسودة | a simulação em andamento, ainda não finalizada
observações | ملاحظات |
conferência | التحقق | a conferência humana: dos campos sugeridos pelo reconhecimento e da identificação do paciente antes de imprimir. Manter التحقق em todas as ocorrências, sem alternar com المراجعة ou التدقيق
sala de simulação | غرفة المحاكاة |
