# Auditoria dos JARs nativos DBC/JBRA

Auditoria concluída antes de qualquer alteração de runtime desta entrega.
Base: `063bc8c6b69713e2e6552599939b309b64da9dbd`. Referências locais apenas,
em `Reference/Native/` (capitalização real no disco). `.gitignore` cobre também
`reference/native/`. JARs, classes extraídas e decompilados não são publicados.

## Proveniência e método

| JAR | SHA-256 conferido |
|---|---|
| DragonBlockC-v1.4.85.jar | `be8c849ab107b1126018bc5b0d0d254d751129f70f3349261f1469f0c220dc9d` |
| JBRA-Client-v1.6.52.jar | `541bc7a8063879e897a27acc3a5850b02a15f8e23ee086cb8d03a36a76e23634` |
| JRMCore-v1.3.51.jar | `0614265d665b6e60456d460255c1488377cad1200ad6bd9444128d835fc28946` |

CFR 0.152, saída em `build/native-audit/`. Nomes abaixo são os nomes SRG/métodos
efetivamente presentes nos JARs; nomes locais `f1/f2/f3` são do decompilador.
Não assumir que variáveis locais sejam campos ou parâmetros públicos. A referência
decompilada é rastreável pelo hash do JAR e pelo método, não por linha de código
de um projeto nativo indisponível. Bibliotecas não foram executadas no Minecraft.

## Operações e dependências exatas

Notação: `S` escala adimensional; `T` translação em unidades de modelo; `Rx/Ry/Rz`
recebem graus no GL. GL pós-multiplica: chamadas `S;T` produzem `S*T`, portanto a
translação é escalada. Pivôs ModelRenderer estão em pixels, multiplicados por
`par7` (usualmente 0.0625); offsets já estão em unidades de modelo.

| Origem / classe / método | Campos/entradas lidos | Ordem, escala e pivôs | Race/body/form, flight, câmera | Reprodução pura |
|---|---|---|---|---|
| JBRA / `JinRyuu.JBRA.RenderPlayerJBRA.func_77041_b(AbstractClientPlayer,float)` | static `gen`; `JRMCoreH.plyrs,data1,data2,data3,dat10,dat14,PlyrAttrbts`; gates `dnn(1,2,3,10,14)`, DBC/power; status 4/7/9/11/17; ground/pitch; `ExtendedPlayer.getUIAnim/getUIAnimID` | R/T especiais primeiro; `S(f1*f2*f3,f1*f3,f1*f2*f3)` por último | Raça/form/release/CON/gênero; voo; pitch é usado, câmera não é consultada diretamente neste método | Separar parsing e captura de dados do cálculo; não executar callback, pois há writes de entidade e de `ModelBipedDBC.y/animation`. |
| JRMCore / `JinRyuu.JRMCore.JRMCoreHDBC.DBCsizeBasedOnCns2(int[])` | atributo `[2]`, `mod_DragonBC.ConsSizeChangeOn`, `JRMCoreConfig.tmx` via `nRP9ea()` | `0.192f*min(CON,max)/max` se ligado; `0.2f` se desligado | Config/CON; sem câmera/flight | Mesma aritmética float, inputs capturados. Denominador zero não pode virar fallback válido. |
| JRMCore / `JRMCoreHDBC.DBCsizeBasedOnRace(int,int,boolean)` e `...Race2` | `mod_DragonBC.TransSizeChangeOn`; `JRMCoreH.TransSaiBlk/Sz,TransHmBlk/Sz,TransNaBlk/Sz,TransFrBlk/Sz,TransMaBulk/Size`; `godKiUserBase`, cosmic config | Tabelas de bulk e size, sem GL | Raça/form; Namek divine tem exceção; sem flight/câmera | Snapshot imutável das tabelas/config; não congelar defaults como se fossem valores ativos. |
| JBRA / `RenderPlayerJBRA.func_130009_a(AbstractClientPlayer,double,double,double,float,float)` | JYC/JFC presence; `JRMCoreHJYC.JYCAge/JYCsizeBasedOnAge`; DNS gender/breast; pregnancy; race/form/power | Define `childScl=3-2*JYCsize`; `gen`; transfere `ModelBipedDBC.f/g/p` | Addons, gênero, idade, gravidez; Oozaru força gen=1 | Capturar valores por player fora do render. `childSclGet/genGet` são estado global do renderer, não fonte autoritativa. Addons requerem auditoria de seus dados. |
| JRMCore / `JinRyuu.JRMCore.entity.ModelBipedBody.renderBody(float)` | static `f,g,p`; `field_78091_s` child; `field_78117_n` sneak; `Entity`, `rot1,rot2`; DNS breast | Bases separadas detalhadas abaixo; todas `S;T`, com stack por grupo | Gênero/idade/pregnancy; breast bounce usa entidade e fase animada; nenhuma consulta direta à câmera | Descrever cada grupo, não comprimir tudo numa matriz. Excluir detalhes não capturados. |
| JRMCore / `ModelBipedBody.<init>(float,float,int,int)` | parâmetros de construção | Head/torso `(0,par2,0)`; braços vanilla `(-5,2+par2,0)/(5,2+par2,0)`; pernas vanilla `(-1.9,12+par2,0)/(1.9,12+par2,0)`; outras partes têm árvore própria | Depende da classe/árvore concreta | Preservar qual árvore foi selecionada. Não substituir automaticamente os `-1.9/+1.9` por `-2/+2` do adaptador Epic. |
| JBRA / `JinRyuu.JBRA.ModelRendererJBRA.render(float)` | offsetXYZ, rotationPointXYZ, rotateAngleXYZ, sizeXZ, lengthY; hidden/show, children | `T(offset);T(pivot*scale);Rz;Ry;Rx;S(sizeXZ,lengthY,sizeXZ);draw;S(reciprocal)`; ramo rotacionado adiciona `T(0,lengthY*.15-.15,0)` antes dos filhos | Geometria por instância; sem consulta direta à câmera/flight | Sequência dependente do ramo, incluindo transformação dos filhos. Não reduzir à fórmula de `postRender`. |
| JBRA / `ModelRendererJBRA.postRender(float)` | pivot/angles/show/hidden | `T(pivot*scale);Rz;Ry;Rx`; não aplica o `sizeXZ/lengthY` de `render` | Sem câmera/flight | Kernel separado do desenho. Compilação de display list original não é parte da matemática pura. |
| JBRA / `ModelRendererJBRA.renderWithRotation(float)` | mesmos pivôs/ângulos | `T;Ry;Rx;Rz`, ordem diferente de `render` | Sem câmera/flight | Não reutilizar um kernel com ordem XYZ errada. |
| JBRA / `JinRyuu.JBRA.ModelBipedDBC.transRot(float,ModelRenderer)` | pivô e ângulos da parte | `T(pivot*scale);Rz;Ry;Rx`, fator rad→graus `57.295776f` | Parte pode ter sido alterada anteriormente | Pivôs/ângulos são entradas, jamais ler última matriz GL. |
| JBRA / `ModelBipedDBC.renderHairs(float,String,String)` | `f,g`, string hair, aliases `RA/LA`, rotações/pivôs, flags específicos | Escalas próprias de acessórios/raça; copia pivôs/ângulos entre partes; existem ramos FR/whandleg etc. | Form/geometria/pose nativa; não é um único socket de mão | Bloqueado para descriptor de superfície/acessórios sem mapear todos os ramos. |
| DBC / `JinRyuu.DragonBC.common.DBCClientTickHandler.onTickInGame()` | `DBCKiTech.floating`, status/release, config e estado cliente | Fonte de progressão e flags, não a matriz usada pelo callback JBRA | Flight/tick; alguns ramos são câmera de spectator | Não chamar o tick para avaliar pose; somente uma captura read-only dos valores necessários. |

`JinRyuu.DragonBC.common.Render.ModelBipedDBC` (DBC) e
`JinRyuu.JBRA.ModelBipedDBC` (JBRA) são classes diferentes. A segunda herda de
`ModelBipedBody`, onde `f/g/y/p/animation` estão declarados. Não misturar proprietários
de campos pelo nome simples da classe.

## Fórmula exterior do callback

Com DBC: base `b=.73f` se gen<=1, senão `.7f` (sem DBC soma `.2f`). O inicial
`.9375f` é sobrescrito por esses ramos para gen inteiro. `a=b+CONsize`, `u=bulk`,
`v=size`, `r=release` de `dat10[index].split(";")[0]`.
Para Saiyan/half, states 7/8: `r=50; a=b`.

```text
vCandidate=(v-1)*r*.02+1
v = vCandidate>v ? v : (v>1 ? vCandidate : v)
uCandidate=(u-1)*r*.02+1
u = u>1 ? uCandidate : u
d=(a-b)*(r<=50 ? .25 : .5)
a = a-b-d+d*r*.02+b
scale = (a*u*v, a*v, a*u*v)
```

Preservar agrupamento e ordem float do source; expressões algebricamente
equivalentes podem arredondar diferente. `nRP9ea()` limita `tmx` a 1.000.000.000;
abaixo de 100 retorna zero. `DBCsizeBasedOnCns2` pode dividir por zero nesse caso.

Raças: Human=0, Saiyan=1, Half-Saiyan=2, Namekian=3, Arcosian=4, Majin=5.
Helpers `rc_sai` aceitam 1/2. Namek state=3, cosmic cosmetics ativo e divine:
bulk=1.1, size=1.5; senão tabelas. Defaults da inicialização de JRMCoreH são dados
de teste possíveis, não configuração runtime garantida. Form names vêm de outras
tabelas; IDs são a referência numérica desta auditoria.

## Bases `renderBody`

Defina `f=ModelBipedBody.f`, `h=.5+.5/f`,
`headY=(f-1)/f*(2-(1.5<=f<=2 ? (2-f)/2.5 : (1<=f<1.5 ? (2*f-2)*.2 : 0)))`,
`bodyY=(f-1)*1.5`. Cada linha reinicia a stack antes das operações.

| Ramo | Grupo | S | T após S |
|---|---|---|---|
| g<=1, child | cabeça | (.75,.75,.75) | (0,16*par7,0) |
| g<=1, child | torso/braços/pernas | (.5,.5,.5) | (0,24*par7,0) |
| g<=1, adulto | cabeça | (h,h,h) | (0,headY,0) |
| g<=1, adulto | torso/braços/pernas | (1/f,1/f,1/f) | (0,bodyY,0) |
| g>1 | cabeça | (.85*h,h,.85*h) | (0,headY,0) |
| g>1 | Brightarm/Bleftarm, body | (.7/f,1/f,.7/f), na ordem original `1/f*.7` | (0,bodyY,0) |
| g>1 | rightleg/leftleg | (.85/f,1/f,.775/f), ordem `1/f*k` | (-.015 ou +.015,bodyY,sneak ? -0.0 : -.015) |

No ramo g>1 o bool child não seleciona o caminho infantil. `Bbreast/Bbreast2`,
hip/waist/bottom usam bases adicionais, pregnancy `p`, `dnsBreast`, ground/water,
sprint/sneak e `rot1/rot2`; `renderBody` também escreve rotações de breast.
Não tratar toda essa geometria como um único torso rígido. A extração inicial
pode fornecer os grupos principais acima, mantendo os demais indisponíveis.

## Flight, y, side effects e limite físico

`func_77041_b`: status 11 retorna após `S(.01,.01,.01)`; KO de `data4[2]` escreve
pitch/yaw=0, faz `Rx(-90);T(0,.8,-.1)` e y=3. UI faz Ry(-80/+80/-40/+40),
T(0,0,.3), y=4+UIAnimID e escreve animation. Flight exige airborne e
`w || data3.contains("1")`, onde `w=status7 || (status9 && contains1 && !status4)`.
Aplica `T(0,-1.5,0);Rx(w ? pitch+90 : 90)` e y=2; normal y=1.

Constantes declaradas em ModelBipedBody: y_notFlying=1, y_isFlying=2, y_isKO=3,
y_isDodging1/2=4/5, y_isAttacking1/2=6/7. `func_78087_a` usa y e define pitch
de cabeça -1.0471976 rad no ramo de voo, além de consultar câmera para outros
comportamentos. **Não executar esse método dentro de um provider puro.**

O mod atual intercepta translate/rotate com `DbcFlightRenderHook`: suprime prone
sem sprint e em WORLD-BODY. Assim a geometria visual fast flight não define
uma única base física independente de câmera. O provider inicial deve recusar
fast flight/KO/UI/spectator em vez de escolher uma câmera ou chamar os callbacks.

## Conclusão antes da implementação

Há evidência para um provider **parcial de escala exterior e bases principais**, a
partir de um estado/config imutável explicitamente fornecido. Não há autorização
matemática para anunciar sockets finais Hand/Tool ou world completo: faltam o
adaptador read-only por tick, seleção/topologia de todas as formas/acessórios,
integração das bases por parte com retarget e a política física de fast flight.

A próxima branch pode implementar apenas esses componentes demonstrados, com
validade separada da completude de world/collider. Ela não deve ler os static
`gen/childScl/f/g/y` do último render, instalar um hook ASM novo, nem alterar o
renderer para mascarar as lacunas. Delegar os callbacks nativos exigiria intervenção
no bytecode que também contém side effects e ramos fora do escopo; nesta etapa,
comparar com oráculos derivados dos JARs preserva o caminho visual intacto.

## Entrega e fronteira do provider

`NativeDbcSpatialProvider` recebe `State` e `Config` imutáveis e retorna um
`Descriptor`. Config copia as tabelas ativas fornecidas; não embute defaults de
servidor. State identifica player/world por referência, tick e revisão espacial.
A validade de consumo exige também a revisão de configuração via `isValidFor`.
O consumidor deve incrementar a revisão quando qualquer entrada espacial mudar.
Não existe captura automática de estado nem integração no combate nesta etapa.

O descritor fornece escala exterior e operações S/T das seis bases principais
(cabeça, torso, dois braços, duas pernas). `copyPartMatrix` usa matriz row-major,
vetor-coluna e S*T, anterior à árvore ModelRenderer e ao retarget; exclui a escala
exterior. `isValid` valida esses componentes; `hasCompleteWorldTransform` retorna
sempre false. Nenhuma dessas bases é anunciada como posição final Hand/Tool.

Suporte parcial: fatores das tabelas das seis raças, formas não Oozaru presentes
na configuração, CON/release, variante g=1..3, divisor de idade, child e sneak.
NORMAL significa somente apresentação não prone explicitamente fornecida, y=1.
FAST, y=2..7, spectator, Oozaru e entradas ausentes são recusados sem fallback.
Formas aceitas para cálculo de escala não implicam suporte à sua geometria completa.

Dados ainda faltantes para integração: captura autoritativa por player/tick de
JYC/JFC/DBC e configuração ativa; seleção completa da topologia por forma;
composição das bases com sockets/retarget; política física de prone independente
da câmera. A menor próxima extração é um adaptador somente de leitura dessas
entradas, com fixtures por estado, seguido da composição explicitamente validada.
Nenhum callback nativo foi redirecionado: fazê-lo agora exigiria hooks em métodos
com side effects e geometria não coberta. Nenhum arquivo de runtime existente mudou.

### Body type e pivôs adicionais

`RenderPlayerJBRA.renderEquippedItemsJBRA(AbstractClientPlayer,float)` lê
`dnsSkinT`, `dnsBodyC1_0` ou `dnsBodyT` e seleciona `plyrSpc` via `RaceCustomSkin`
e `Specials`. Há leitura semelhante em `func_82441_a(EntityPlayer)`. Esse índice
cosmético não é `gen/g`; não deve substituir a variante corporal do descritor.
No construtor de ModelBipedBody, Brightarm/Bleftarm usam pivôs (±5,2,0), seus
childarms usam origem local zero e ângulos Z ±.122173 rad; rightleg/leftleg usam
(±2,12,0), diferentes das pernas vanilla ±1.9. Esses pivôs estão documentados,
mas não foram confundidos com as bases externas S/T retornadas pelo provider.

### Validação reproduzível

`tools/tests/run_native_spatial.ps1` recebe ECJ, CFR e Python. O gerador verifica
SHA256 dos três JARs exatos, decompila os métodos selecionados e gera oráculos
somente em `build/native-oracle/` (ignorado). Compara bits float das fórmulas
originais extraídas com o provider: 35.280 estados de escala (incluindo CON zero,
abaixo e acima do limite) e 84 casos corporais. Não é execução direta dos
bytecodes em Minecraft nem comparação de pixels/GPU. GL é substituído por
registro dos argumentos S/T nos oráculos de teste, nunca no runtime.

Testes incluem ownership dos arrays e invalidação player/world/tick/revisões.
Yaw/câmera não são entradas do descritor local; repetição prova determinismo
local, não equivalência de world yaw ou fast flight. A suíte anterior de pose
continua cobrindo 0/1/10 renders, câmeras, perfis e isolamento de estado.
Hand/Tool world e fast flight permanecem bloqueados; testes de rejeição não
constituem validação desses caminhos. As suítes combat_pose, dbc_spatial e audit
também foram executadas, preservando os golden tests e assets anteriores.

Os JARs e fontes decompiladas não fazem parte do commit. `reference/native/`
e a grafia local `Reference/Native/` estão cobertas pelo `.gitignore`.

## Continuação: procedência dos dados antes de conectar o provider

Inspeção adicional dos mesmos JARs, sem alteração de runtime. O gerador de
auditoria agora inclui `JRMCoreHJYC` e `JRMCoreHJFC` para tornar esta inspeção
reproduzível. A implementação parcial permanece no commit `5f0fe40`; esta
continuação não declara a Fase 2 concluída nem libera joint-local.

| Entrada | Origem exata | Fronteira de captura |
|---|---|---|
| ageDivisor f | JBRA `RenderPlayerJBRA.func_130009_a`: quando `JRMCoreH.JYC()`, `childScl=3-2*JRMCoreHJYC.JYCsizeBasedOnAge(player)` | Recalcular de dados autoritativos, nunca ler `childSclGet()` do render. |
| tamanho por idade | JRMCore `JRMCoreHJYC.JYCsizeBasedOnAge`: `JYearsCH.p` (nome;idade), `JYearsCConfig.pgut`, `JRMCoreH.data(nome,1/2,default)` | O helper está no JAR auditado; Years C/configuração ativa e suas fixtures não estão nos três JARs fornecidos. Não chamar setters para resolver dados. |
| modelVariant g | JBRA `func_130009_a`: sob `JFC()` e `dnn(1)`, nome em `plyrs`, DNS de `data1`, `dnsGender+1`; Oozaru força 1 | Não confundir gênero/variante com body type cosmético. Ausência de linha válida não autoriza herdar o static anterior. |
| gravidez p / breast b | JBRA mesmo método: `preg[pl]`, `dnn(30)`, `JFCgetConfigpt()*120`, e `dnsBreast(data1 DNS)` | Geometria adicional fora do descritor parcial; depende de configuração Family C. |
| NPC f/g | JRMCore `JRMCoreHJFC.modelHelper`: `EntityNPC.getNPCgrw()`, `getDNS()`, `dnsGender+1` | Escreve ModelBipedBody.f/g e mdl.b. Não usar como getter puro nem generalizar para todo mob. NPCs ficam em fase própria. |

Fórmula observada de idade: yc começa em 1; na linha do player, idade A<=5
atribui .5; A>5 e A<=gu atribui `.5+(A-5)/(gu-5)*.5`; A>gu atribui 1;
depois aplica mínimo .5531915. As condições são sequenciais, não uma fórmula
nova com clamp arbitrário. Saiyan/half-Saiyan nas formas 7/8 ou 14 retornam 1
no helper. O renderer então aplica `3-yc*2`. Essas constantes descrevem o
bytecode decompilado; não são fallback autorizado quando a captura está ausente.

`func_130009_a` só atribui alguns campos dentro dos gates JYC/JFC e quando
encontra dados do nome. Consequentemente, copiar os static gen/childScl/preg
reproduziria potencial dependência da ordem dos renders. Isso é incompatível
com identidade por player/world/tick. Não foi corrigido o renderer nesta etapa.

Menor continuação segura: estabelecer um snapshot de entrada por tick que copie
as linhas de dados/config relevantes e registre disponibilidade e identidade;
para addons ausentes, demonstrar o ramo de ausência, sem simular dados ausentes
como se fossem presença. Para addons presentes, obter configurações e fixtures
exatas. Testar dois jogadores intercalados e dados atrasados/ausentes antes de
conectar esse snapshot ao kernel. A composição final de sockets/world e a
política de fast flight continuam sendo gates separados, mesmo após a captura.

Não há impedimento para estudar um subconjunto sem addons; porém esta entrega
não afirma ter implementado seu adaptador de captura ou validado suas posições
world. Os testes numéricos existentes continuam verificando entradas explícitas.

## Auditoria adicional: JYearsC 1.2.5 e JFamilyC 1.2.18

Esta seção foi produzida a partir dos cinco JARs presentes em `Reference/Native/`
(DragonBlockC 1.4.85, JBRA Client 1.6.52, JRMCore 1.3.51, JYearsC 1.2.5 e
JFamilyC 1.2.18). Os hashes dos cinco arquivos são verificados pelo gerador
`tools/tests/prepare_native_oracles.py`; os JARs e as fontes decompiladas ficam
fora do Git.

### JYearsC: idade e escala corporal

| Operação | JAR / classe / método | Campos e ordem observados | Unidade e dependências | Reprodução pura |
|---|---|---|---|---|
| Ler idade do jogador | JRMCore 1.3.51 / `JRMCoreH` / `getFloat(EntityPlayer,String)` | Chave literal `JRYCAge`; leitura do dado do jogador | Anos JYearsC, `float`; não depende de câmera ou GL | O adaptador deve copiar o valor e a revisão do dado, sem chamar o tick do addon |
| Atualizar idade | JYearsC 1.2.5 / `JYearsCComTickH` / `serverTick` | `JRMCoreH.getFloat`, `JRMCoreH.setFloat`, `JYearsCConfig.pls/pgut`; a cada dia em ticks 1, 6001, 12001, 18001 soma `0.25`; na dimensão DBC 23 soma `4.0` em múltiplos de 1000 | Efeito de servidor, com dano/mensagens/GUIs quando a vida termina; não é uma operação de captura | Nunca executar `serverTick` no snapshot. Ler apenas uma cópia autoritativa já resolvida |
| Configuração | JYearsC 1.2.5 / `JYearsCConfig` / `init` | `pls` clamp [20, 1000000], `pgut` clamp [10, 100000] | Dias Minecraft; `pgut` é o crescimento adulto | Copiar os valores ativos e sua revisão; ausência é `UNAVAILABLE` |
| Escala derivada | JBRA/JRMCore / `JRMCoreHJYC.JYCsizeBasedOnAge` | `yc=.5` até 5, depois `.5+(A-5)/(gu-5)*.5`, 1 acima de `gu`, mínimo `.5531915`; formas Sai/half Sai 7, 8 e 14 fixam 1; renderer usa `childScl=3-yc*2` | `A` e `gu` em anos/dias configurados; sem câmera/GL | `DbcSpatialStateSnapshot.resolveJYearsCAge` reproduz a ordem e deixa a escala indisponível se a linha/configuração faltar |

`JYearsCComTickH` também envia dados de proximidade e altera estado do jogador;
por isso não é um provider espacial. O JYearsC JAR não contém `JYearsCH.p`, que
é a tabela de linhas nome/idade usada pelo helper do JRMCore. O estado de idade
continua sendo um campo explícito do snapshot, não uma leitura tardia do último
render.

### JFamilyC: DNS, gênero e NPCs

| Operação | JAR / classe / método | Campos e ordem observados | Limite para o player |
|---|---|---|---|
| Configuração familiar | JFamilyC 1.2.18 / `FamilyCConfig.init` | `cls` [20,1000000], `gut` [10,100000], `pt` [1,50], `mc` [0,10], `dcr`; `cpt` é lido com default de código 52 embora a propriedade/comentário diga 4 | São regras de família, não uma base de joint; revisão deve invalidar dados que dependam delas |
| Alterar DNS de player | JFamilyC / `FamilyCComJFCGen` / `func_71515_b` | Usa `JRMCoreH.dnsGender`, `dnsGenderSet` e grava `jrmcDNS` no player | Comando tem side effect; não deve ser chamado pelo snapshot. A captura recebe DNS já persistido e uma revisão |
| Modelo NPC | JFamilyC / `RenderJFC.func_77029_c` e `ModelBipedJFC.setRotationAngles` | `EntityNPC.getDNS/getDNSH/getNPCgrw`, `JRMCoreH.dnsGender+1`, `dnsBreast`; escalas/pivôs são aplicados no renderer NPC | Caminho exclusivo de `EntityNPC`; não é evidência para `RenderPlayerJBRA` nem para um player humanoide genérico |
| Dados NPC | JFamilyC / `EntityNPC.getNPCgrw`, `getDNS`, `getDNSH` | Crescimento e DNS sincronizados por data watcher | Pode ser uma fonte futura para um adaptador de NPC, com identidade própria; não misturar cache de player |

`FamilyCComJFCsoc` gera DNS de criança e executa spawn/remoção, além de usar
campos estáticos de seleção. Esses campos são comandos e não uma fonte espacial
determinística. O único dado reutilizável nesta fase é a semântica dos parsers
DNS quando uma captura autoritativa fornecer uma revisão; não se copia modelo,
textura ou offsets de `ModelJFC`.

## Limite de captura e snapshot implementado

`DbcSpatialStateSnapshot` é um DTO imutável em `combat` com identidade de
player/world, tick, serial da execução, revisão geométrica, race/form/estado,
body type, `modelVariant`, gênero/DNS revision, idade/crescimento, escalas,
flight/apresentação e disponibilidade independente para DBC, JRMCore, JYearsC,
JFamilyC, corpo, idade, geometria e flight. `Cache` usa identidade do player e
compara todos esses campos; renders/câmeras não são chaves e não repetem a
avaliação no mesmo estado. A sobrecarga de `NativeDbcSpatialProvider.evaluate`
aceita somente snapshots utilizáveis e delega ao mesmo kernel já auditado.

Estados ausentes, atrasados ou incompatíveis permanecem explicitamente
`UNAVAILABLE` e produzem descritor inválido quando são necessários. JYearsC
`NOT_APPLICABLE` preserva o caso comprovado sem addon com divisor 1; isso não é
um valor inventado para um addon ausente. JFamilyC não é inferido de statics do
renderer. Não existe ainda um adaptador live que leia Minecraft/JRMCore sem
acoplar side effects; criar esse adaptador é a próxima fronteira segura.

O snapshot não altera `FirstPersonBodyRenderer1710`, WORLD-BODY, Tool_R, JBRA,
flight, dash, clips, timing, CPS, guard, damage ou weapon behavior. Também não
implementa collider, sockets finais ou transformação completa para world.
