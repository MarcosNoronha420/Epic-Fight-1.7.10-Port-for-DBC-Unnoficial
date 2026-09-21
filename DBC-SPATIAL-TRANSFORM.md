# Extração espacial DBC/JBRA — fronteira local

Base: `0caa399eead162b9557880664bfa236eed5183c4`.
Branch: `feature/dbc-spatial-transform`.

**Entrega parcial na fronteira autorizada.** A matemática local já compartilhada
com o renderer é pura. A transformação completa para world **não está disponível**
e não deve ser habilitada para collider. Nenhum provider fictício, escala corporal
padrão ou offset de Hand/Tool foi introduzido. Não há collider nem merge no main.

## Operadores extraídos

`client.DbcSpatialMath` depende exclusivamente de `Mat4`. Embora esteja no pacote
client junto dos chamadores existentes, compila sozinho com `Mat4`, sem cliente,
LWJGL, GL, callbacks, player, câmera, clocks ou caches. Não mantém estado de frame.
Buffers são fornecidos pelo chamador; o transform de nó mantém as allocations e
a mesma ordem aritmética que o caminho original.

| Operação | Fonte aprovada / uso atual | Contrato |
|---|---|---|
| `basisX`, `basisY`, `convertPoint` | `JbraWeightedPartRenderer.compileTree`, `compilePoint`, `skin` | Ponto Epic↔modelo: `(-x, 1.5-y, z)`. `1.5` é a origem estrutural preexistente, não ajuste novo. |
| `nodeTransform` | `JbraWeightedPartRenderer.transform` | `T * Rz * Ry * Rx`, radianos, entrada de translação já calculada como offset + rotationPoint * model scale. |
| `jointFrameToModel` | `WeaponItemMountHook.epicToJbra` | Frame de joint global: `[R*A3*R, R*t+q]`, `R=diag(-1,-1,1)`, `q=(0,1.5,0)`. Conversão também do referencial dos eixos locais. |
| `deformationToModel` | `NativeJbraSkinContext.pushNativeHeadParentPose` | Deformação de skin: `C*D*C^-1`, com `C^-1=C`. Mantém os dois `Mat4.mul` na ordem original. |

Esses contratos são diferentes. `jointFrameToModel` não é conjugação completa
`C*A*C`; `deformationToModel` não fornece origem de joint. `skinMatrices` nunca
é promovida a posição de joint. As matrizes usam row-major e vetores-coluna.

Os três chamadores visuais delegam para os operadores extraídos; não conservam
cópias independentes dos métodos de transformação removidos. Ordem de calls GL,
controle de render, seleção de partes, fallback, mount correction/cancellation,
animation e seleção de perfis não foram modificados.

`DbcRetargetMath` já era um kernel puro compartilhado de correção de pivô e socket.
Foi reutilizado/testado sem reescrita. Ele resolve a matemática quando recebe os
pivôs corretos, mas não fornece os pivôs/formas/transformações externos ausentes.
O gate de distância, a rigidificação de Hand/Elbow durante voo passivo e a escolha
do modelo permanecem no caminho visual existente; não foi duplicado um retarget
runtime de combate sem dados autoritativos.

## Dados que impedem fechar a transformação

1. **Escala corporal e colocação exterior:**
   `FirstPersonBodyRenderer1710.applyCachedPreRender` chama por reflexão
   `RenderPlayerJBRA.preRenderCallback`, sob GL identity, e lê
   `GL_MODELVIEW_MATRIX`. O comentário do caminho identifica escala race/body.
   Essa matriz não é um dado puro publicado pelo tick. Reusar seu cache violaria
   o requisito de zero renders. Escala de pixel `ModelRenderer` não substitui
   essa escala corporal.
2. **Transformações por parte:** o replay de
   `FirstPersonBodyRenderer1710.renderModelBodyTransformTree` invoca o
   `ModelBipedBody.renderBody` nativo. O código registra escala/translação externa
   distinta para cabeça, torso, braços e pernas, dependente de race/body. Portanto
   um único `modelToWorld` global pode ser insuficiente para todos os joints.
   Faltam descritores das bases por parte, ordem das operações e revisão de forma.
3. **Geometria concreta/forms:** `JbraModelAdapter.map/resolveLivePart` descobre
   aliases/main model/partes do renderer, e incrementa `topologyGeneration` nesse
   processo. Não existe um snapshot de geometria autoritativo independente de
   render. São necessários os pivôs neutros/offsets, hierarquia estática e bases
   externas da instância/form atual, com identidade player/world e revisão.
4. **Fast flight e câmera:** `DbcFlightRenderHook.plankRequested` suprime a base
   prone em WORLD-BODY e aceita sprint-held em terceira pessoa. Os argumentos de
   translate/rotate chegam do call-site nativo. Uma única transformação espacial
   independente da câmera não pode ser simultaneamente igual às duas bases
   visuais. Falta definir a base física do combate e obter seus parâmetros no tick;
   não foi escolhida uma das câmeras silenciosamente.
5. **Estados especiais:** `NativeJbraSkinContext.renderPart` entrega `y=3..7`
   (KO/UI) ao JBRA nativo. Aplicar indiscriminadamente os joints Epic nesses estados
   seria incorreto. Falta um contrato explícito de suporte/invalidade por estado.
6. **Sockets e Tool:** `rightHandSocketDelta` e `frameModelOffset` são publicados
   durante draw/reset de frame e consumidos por `WeaponItemMountHook`. A correção
   depende dos dados acima; não foi lida para combate. Converter Hand_L/Tool_L na
   base local não prova um mount nativo esquerdo: o mount existente é Tool_R.

O source nativo desses callbacks e fixtures versionadas das formas/transforms não
estão no repositório. A fixture existente contém poses Epic brutas. Os objetos
ModelRenderer sintéticos dos testes são explicitamente locais, não capturas de
formas DBC. Não se declarou cobertura inexistente de transformação global nativa.

## Menor extração proposta para continuar

Identificar/fixar as versões nativas JBRA/JRMCore e coletar fixtures de cada ramo
suportado de `preRenderCallback` e `renderBody`, incluindo transformações por parte.
Extrair as expressões desses ramos para um provider de dados puros que receba os
mesmos valores de race/body/form e retorne os descritores. Renderer e combate
devem chamar esse provider, preservando a sequência das operações do draw.

O descritor deve incluir player/world, tick, revisão de geometria/form, escalas e
pivôs/bases por parte, estado suportado e parâmetros autoritativos de voo. A
política espacial de fast flight deve ser explícita e separada da câmera; os dois
modos visuais atuais precisam permanecer preservados. Antes de suportar um ramo,
comparar seus descritores/posições com fixtures nativas de normal/voo/sprint/form.

Só então alimentar `ModelToWorld` (por parte, se necessário), ligar os sockets
puros à pose capturada e validar origem/eixos de Hand/Tool em world. Não recuperar
GL, invocar render no tick, reutilizar a última matriz, nem usar o `topologyGeneration`
visual como substituto automático da revisão autoritativa.

## Testes executados e limites

```powershell
./tools/tests/run_dbc_spatial.ps1 -CompilerJar <ecj-4.6.1.jar> -LwjglJar <lwjgl-2.x.jar> -Python <python>
./tools/tests/run_combat_pose.ps1 -CompilerJar <ecj-4.6.1.jar>
./tools/tests/run_audit.ps1 -CompilerJar <ecj-4.6.1.jar> -Python <python>
```

O runner espacial gera oráculos dos renderers do commit aprovado em `build/`,
compila os fontes reais de cliente/render com os símbolos LWJGL e compara seus
métodos de matemática. Somente a entrada Forge/resource anchor e Tessellator são
doubles de compilação, fora de `src/main`; qualquer draw Tessellator falha no teste.
Não é build Forge completo nem teste GPU. A extração de cabeça usa literalmente
a constante e as duas operações do source aprovado, isoladas de GL.

| Pedido | Resultado / cobertura |
|---|---|
| Normal e escalas | PASS local: pivôs neutros de árvores ModelRenderer sintéticas e quatro escalas de pixel; escala corporal nativa pendente. |
| Yaw 0/90/180/-90 | PASS bit a bit no transform local de nó; não é validação do yaw/world do callback nativo. |
| Forms/estados DBC | BLOCKED: não existem fixtures nativas no projeto. Estados `y=3..7` não ganharam suporte. |
| Hand_R/L, Tool_R/L | PASS: 1.452 poses source/DBC (242 clips × 3 tempos × 2 perfis), frames locais, mount matemático, skin e socket kernel. |
| Cabeça/mount | PASS bit a bit na conjugação de cabeça e composição completa de correção/cancelamento do mount; geometria face/hair nativa não desenhada. |
| Voo normal | Clips de voo incluídos, composição local/transições comparada com baseline; colocação exterior ainda pendente. |
| Fast flight | BLOCKED para world: base prone nativa/câmera não extraída; comportamento visual não alterado. |
| Generic/DBC | Ambos os perfis de pose testados. Não foi aplicado DBC retarget ao renderer genérico. |
| Player/world | Snapshot de origem invalida leitura após troca, sem cache espacial visual novo. |
| 0/1/10 renders | PASS no harness de composição real da Fase 1; operador local também invariável a 0/1/10 chamadas de matemática do renderer. Nenhum draw OpenGL executado. |

Suite espacial: **622.593 asserções**, além das **3.587.358** da Fase 1.
Regressões: 4.536 composições/skin bit a bit iguais ao baseline aprovado da auditoria,
968 hierarquias finitas, 480 matrizes golden, timing/CPS, armas/mount, collider
existente e profiler OFF/ON preservados. Assets não editados.

FirstPersonBodyRenderer1710/WORLD-BODY, flight gate, dash, clips e combat timing
permaneceram byte a byte intactos. Os arquivos de renderer/head/mount tocados
receberam somente delegações matemáticas. Nenhuma diferença foi encontrada nas
operações cobertas. Confirmação visual final no Minecraft continua necessária;
esta entrega não afirma equivalência global de formas/voo sem os dados faltantes.
