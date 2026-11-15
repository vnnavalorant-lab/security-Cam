# VigiCam (Fabric 1.20.1)

Bloco "Câmera de Segurança". Clique direito nela e você passa a ver pelo ponto de vista da câmera
(varrendo de um lado para o outro) com o shader **fisheye + VHS + visor da câmera** (sem tingimento verde) e HUD "LIVE".
Shift (depois de soltar uma vez) sai da câmera.

**Modelo:** `security_camera.bbmodel` (Blockbench) -> `assets/vigicam/geo/security_camera.geo.json`, desenhado por um
BlockEntityRenderer. Pivô `base` = suporte fixo, sempre na extremidade do bloco e centralizado (oposta à lente);
pivô `root` = corpo da câmera, que varre de um lado para o outro (e segue a visão quando você está usando a câmera).
Ao colocar, a câmera fica **virada para o jogador**. Para trocar o modelo, exporte de novo o JSON (mesma estrutura de grupos
`base` > `root`) e a textura em `textures/entity/security_camera.png`. Ícone: `textures/item/surveillance_camera.png`.

**Visões (tecla V):** normal (VHS + fisheye + visor) -> noturna (verde) -> térmica -> normal.

**Visão térmica:** dentro da câmera aperte **V** (configurável em Controles > VigiCam) para alternar entre a
visão noturna/VHS e a visão térmica. Na térmica o mundo vira um mapa de calor (céu frio, lava/fogo quentes) e cada
entidade é colorida pela sua "temperatura" (jogador/animais quentes, mortos-vivos e golens frios, blaze/fogo
em brasa), com marcadores, nome e °C, retículo, Max/Min e barra de escala. As temperaturas ficam em `HeatUtil.java`;
para enxergar entidades através de paredes troque `XRAY` para `true` em `camera_thermal.fsh`.

## Como compilar
1. Gere um projeto base em https://fabricmc.net/develop/template (1.20.1) para ter o gradle wrapper
   (gradlew, gradle/wrapper), ou copie o wrapper de qualquer mod Fabric 1.20.1 seu.
2. Copie para ele: `src/`, `build.gradle`, `gradle.properties`, `settings.gradle` deste projeto.
3. `./gradlew build` -> o .jar fica em `build/libs/` (use o que NÃO termina em -sources).
4. Coloque o .jar na pasta mods junto com o Fabric API 0.92.x para 1.20.1.

Receita: 5 ferro, 2 vidraça e 1 redstone (III / GRG / I I).

## Drone, controle remoto e câmera manual

**Drone** (`vigicam:drone`, receita: 4 penas, 2 ferros, 1 câmera, 1 redstone)
- Clique direito num bloco para colocar. Clique direito com a **mão vazia** para pilotar; Shift + clique direito (mão vazia) ou bater nele para recolher.
- Pilotagem: **WASD** move, **Espaço** sobe, **Ctrl** desce, o mouse gira o drone/câmera, **Shift** sai. Alcance de 96 blocos.
- Ao sair da pilotagem ele **volta sozinho para a base** (o lugar onde foi colocado no chão pela última vez): sobe acima do relevo da rota, voa até cima da base e desce na vertical. Se ficar preso, tenta subir mais e, em último caso, faz um pouso de emergência. Rotores param ao pousar.
- As câmeras e o drone enxergam todos os jogadores, inclusive você mesmo. Os rotores giram 4 voltas/s (animação `on` do Blockbench).
- Mesmos shaders da câmera: **V** alterna visão noturna/térmica. Telemetria (ALT/DIST/VEL/sinal) no HUD.

**Controle remoto** (`vigicam:remote`, receita: tocha de redstone, 7 ferros, redstone)
- Shift + clique direito numa câmera ou drone: vincula/desvincula (até 8).
- Clique direito no ar: conecta ao dispositivo selecionado, de qualquer distância (dentro da distância de renderização).
- Dentro da visão, **R** passa para o próximo dispositivo da lista (segure o controle).

**Câmera manual:** dentro de uma câmera fixa, **G** liga o modo manual (você move a visão com o mouse, até ±110° do eixo da câmera);
**G** de novo volta suavemente para a varredura automática. Todas as teclas são configuráveis em Controles > VigiCam.

## Antena Amplificadora

- **Dono:** quem coloca a antena é o dono. Só os drones pilotados por ele recebem o sinal ampliado (clique direito na antena mostra o dono).
- **Alcance:** o drone normalmente voa até 96 blocos do piloto. A antena do piloto é um segundo transmissor: o alcance dela é 96 + **50 blocos contados a partir da antena** (146). O drone pode voar em qualquer ponto dentro de 96 blocos do piloto **ou** de 146 blocos da antena. A barra de sinal do HUD usa o melhor dos dois e mostra `▲ ANTENA +50 m`.
- **Perda de sinal:** se o drone ficar fora do alcance do piloto e da antena (antena quebrada ou longe demais), o controle é solto, o jogador sai da visão do drone e ele **volta sozinho para a base** de onde saiu (o retorno automático não depende de sinal).
- **Efeitos:** ondas de rádio (anéis de brilho aditivo que se expandem da ponta e do meio do mastro), halo pulsante, faíscas de dados e LEDs vermelhos emissivos pulsando. Com um drone usando a antena (`active=true`) tudo fica mais rápido/intenso e o bloco emite mais luz.
- Receita: pára-raios + bloco de redstone + 5 barras de ferro.

## Porta Reforçada

- **Estrutura 3x3x1:** 3 blocos de largura, 3 de altura e só 1 de profundidade (Z). Precisa de espaço livre para os 9 blocos; o clique define o bloco central de baixo.
- **Só quebra com picareta de diamante** (ou netherite): `mineable/pickaxe` + `needs_diamond_tool` + `requiresTool()`. Dureza 50 / resistência 1200 (como obsidiana). Quebrar qualquer parte remove a porta inteira e solta um item só.
- **Painel de senha:** clique direito na porta fechada abre o painel de acesso (`DoorPanelScreen`, inspirado no "Terminal de Acesso"
  em HTML: chassi octogonal em degraus, parafusos, LED, visor com scanlines, cadeado pixel-art, teclado de pedra, tremor + piscada
  vermelha ao errar, mesmos sons 8-bit). Senha de 4 dígitos (teclado do mouse ou numérico/Enter/Backspace/X). Senha certa: luzes verdes
  (animação `on`) e 0,5 s depois a porta abre. Clique direito na porta **aberta** fecha e **trava** de novo (animação `off`).
- **Dono:** quem coloca a porta é o dono (guardado no BlockEntity, salvo no mundo). Ele abre **sem digitar senha** e é o único que vê
  a barra extra do painel: **[ABRIR]** e **[SENHA]** (digita 4 dígitos e confirma com OK para trocar; **[VOLTAR]** cancela).
  Os outros jogadores só recebem o teclado e precisam descobrir a senha. A senha inicial é sorteada; porta de mundo antigo/comando
  sem dono passa a ser de quem usar primeiro.
- **Segurança:** a senha fica só no servidor e nunca é enviada aos clientes; o servidor confere tudo (distância, dono, formato da senha).
- **Sons do painel:** `door_key`, `door_clear`, `door_error`, `door_unlock` (.ogg) são a síntese Web Audio do HTML reproduzida fielmente;
  para regenerar rode `python3 tools/gen_door_sounds.py` (precisa de ffmpeg com libvorbis).
- **Colisão:** fechada (travada ou só destravada) = parede 3x3x1 inteira; só depois de aberta (pivô `door` girado) fica só o quadro (batentes e verga), e dá para atravessar o vão de 2 x 2,5 blocos.
- **Quadro (`blocks`):** os cubos `block` do grupo `blocks` usam a textura do bloco vizinho (esquerda, direita, em cima ou embaixo, o primeiro encontrado, em ladrilhos de 16 px); sem bloco vizinho ficam invisíveis. No item da mão o quadro usa a textura do próprio modelo.
- **Modelo:** `reinforced_door.bbmodel` -> `geo/reinforced_door.geo.json`, textura `textures/entity/reinforced_door.png`, animações `animations/reinforced_door.animation.json` (`on` esconde as luzes `locked`/`locked2`; `off` esconde `unlocked`/`unlocked2`/`green`/`green2`). Desenho em `ReinforcedDoorRenderer`.
- Receita (ajuste em `data/vigicam/recipes/reinforced_door.json`): 5 ferros, 1 porta de ferro, 2 blocos de ferro, 1 redstone (III / IDI / BRB).

## Painel de Biometria (leitor de digital)

- **Quando aparece:** o dono que erra a senha `MAX_OWNER_FAILS` (3) vezes seguidas no teclado tem o painel trocado por `BiometryPanelScreen`
  (inspirado no "Terminal Biométrico Minecraft" em HTML: mesmo chassi octogonal, LED de 3 estados, visor com scanlines, cadeado pixel-art,
  barra de progresso e, embaixo, o leitor em relevo com sensor de vidro, digital em pixel art e laser). Clicar na porta de novo dentro da
  janela de 60 s abre direto o leitor.
- **Como usa:** **segure** o leitor (botão esquerdo do mouse, ou Espaço/Enter) por 1,5 s. Âmbar "LENDO...", a digital acende de cima para baixo
  em ciano, um pulso 8-bit a cada 135 ms cada vez mais agudo. Soltar antes = erro, "LEITURA FALHOU", tremor e volta a trancado em 0,42 s.
  Completo = verde "ACESSO LIBERADO" / "DIGITAL VALIDADA"; a tela fecha após 3 s (no HTML é o momento em que ele trancaria de novo).
- **Segurança:** ao completar, o cliente manda `ACT_BIO_DONE`; o servidor só libera se for o dono, com a janela de biometria aberta
  (`startBiometry`) e há pelo menos 1 s. Senha certa de qualquer um fecha a janela.
- **Sons:** `bio_scan_0..11` (um por pulso, com o tom do progresso), `bio_fail` e `bio_success`, gerados por `tools/gen_door_sounds.py`.

## Pendrive Hacker

- **Item:** `vigicam:pendrive_hacker` (textura `textures/item/pendrive_hacker.png`). Não se gasta. Receita **sem forma**: 1 estrela do Nether + 1 barra de netherite + 1 esmeralda
  (`data/vigicam/recipes/pendrive_hacker.json`).
- **Como usa:** com o pendrive em qualquer mão, clique direito numa **Antena Amplificadora de outro jogador** (a "antena/satélite"): abre o painel
  `HackPanelScreen`, o "Terminal de Antena - Puzzle USB" do HTML (chassi octogonal, LED vermelho / âmbar-ciano / verde, visor CRT com a antena em pixel art
  vermelha -> verde, botão AGUARDANDO USB / TRAVAR SINAL / EJETAR USB, porta USB-PORT e o pendrive verde embaixo).
  Arraste o pendrive até a porta (ou clique nele, ou Enter/Espaço). No visor aparece a **QUEBRA DE SINAL**: 3 estágios, o cursor oscila e é preciso
  clicar em **TRAVAR SINAL** (ou Espaço/Enter) com ele dentro da zona ciano. Cada estágio é mais rápido e com a zona mais estreita (68/102/140 %/s,
  28/20/14 %). Errar treme o chassi, pisca o visor e **volta ao estágio 1**. Clicar no pendrive encaixado ejeta.
- **Sons:** os mesmos do HTML, nos mesmos momentos: `hack_usb_connect`, `hack_usb_eject`, `hack_tick_1..3` (pulso ao cruzar o meio, mais agudo a cada
  estágio), `hack_stage_lock_1/2`, `hack_sync_fail`, `hack_victory`. Gerados por `tools/gen_hack_sounds.py` (mesma escala de volume do painel da porta).
- **Ao vencer:** por **5 minutos** (`HackManager.DURATION_SECONDS`) o hacker passa a ser o *dono efetivo* de **tudo** que o dono da antena colocou:
  - **Portas:** abre sem senha (clicar na porta abre direto). Não troca a senha (a troca ficaria depois do acesso).
  - **Câmeras e drones:** só o dono efetivo vê/pilota/vincula/recolhe. O dono real fica sem acesso durante a invasão e, se estava numa câmera/drone, é desconectado.
  - **Antenas:** as antenas da vítima passam a dar o bônus de alcance (+50) aos drones do hacker, não aos da vítima.
  - Aviso na action bar do hacker (contagem nos últimos 10 s) e no chat da vítima, no começo e no fim.
  - Quando o tempo acaba a concessão some sozinha e tudo volta ao dono: o campo de dono guardado nos blocos/entidades **nunca é alterado**.
  - Só uma invasão por vez por vítima; não dá para invadir a própria antena nem antenas sem dono. As concessões ficam só em memória (reiniciar o servidor encerra o acesso).
- **Dono das câmeras e drones:** agora cada câmera e drone guarda quem o colocou (NBT `Owner`). Dispositivos de mundos antigos, sem dono, continuam livres.
  Se preferir que câmeras e drones continuem livres para todos (e o pendrive valha só para portas e antenas), troque `HackManager.RESTRICT_ACCESS` para `false`.
- **Segurança:** o puzzle é jogado no cliente, mas o servidor acompanha a sessão (`HACK_ACTION`: inserir / etapa / erro / ejetar): confere antena, dono, distância
  (12 blocos), pendrive na mão, as 3 etapas em ordem e um intervalo mínimo de 250 ms entre elas. Só o servidor concede o acesso.

## Controle remoto: painel de dispositivos

Agora **botão direito no controle remoto abre primeiro o painel** (`RemotePanelScreen`, o "Controle Remoto Minecraft" do HTML) e só depois, lá dentro, se escolhe o que fazer.

- **Abas** CAMERAS / DRONES / PORTAS (com a contagem) e, no topo, os contadores ONLINE / OFFLINE.
- **Cartões** (2 colunas, com rolagem pela roda do mouse): ícone, nome, **ID colorido** (CAM-01, DRN-01, DOOR-01), coordenadas, seta + distância + direção (N, NE, L, SE, S, SO, O, NO, e ACIMA/ABAIXO),
  estado (ONLINE / OFFLINE, e TRANCADA / ABERTA nas portas) e, nos drones, a barra de sinal.
- **PING:** o dispositivo apita e solta partículas **na cor dele** no mundo por 3,2 s (`RemotePings`); o cartão e o ponto do radar piscam.
- **Lixeira:** 1º clique mostra "SIM?" (3 s), o 2º tira o dispositivo do controle. **APAGAR TODOS** pede confirmação ("CONFIRMAR?").
- **Radar de 120 blocos** com todos os dispositivos (câmera = quadrado, drone = losango, porta = retângulo), varredura animada, o selecionado em destaque e as informações dele embaixo.
  Clicar num ponto seleciona e vai até o cartão.
- **Duplo clique** num cartão ou ponto abre o menu: **VER VIDEOS AO VIVO** (câmera), **PILOTAR DRONE**, **ABRIR PORTA / FECHAR PORTA**. Dispositivo OFFLINE aparece com os botões desativados.
- **Sons:** os beeps do HTML (`remote_tab`, `remote_arm`, `remote_remove`, `remote_remove_all`, `remote_ping`, `remote_menu`, `remote_door_lock`, `remote_door_open`, `remote_watch`, `remote_pilot`), gerados por `tools/gen_remote_sounds.py`.
- **Portas no controle:** Shift + botão direito numa porta reforçada vincula/desvincula (até 8 dispositivos no total). Só o dono da porta (ou quem invadiu a rede dele com o Pendrive Hacker) vincula e comanda; abrir/fechar à distância não pede a senha.
- **Cor e ID** de cada dispositivo são dados na hora de vincular e não mudam mais (vínculos antigos ganham os seus na primeira vez que o painel abre).
- Offline = bloco/entidade não carregado, destruído, ou sem acesso (por exemplo, o acesso do pendrive acabou). Dispositivos em outra dimensão aparecem com o nome da dimensão.
- A tecla "próximo dispositivo" (R), dentro da visão, passa para a próxima câmera/drone da lista e pula as portas.
- Diferenças em relação ao HTML: o botão "RESTAURAR DEMO" não existe (era só da demonstração) e, como o mod não tem bateria nos drones, a barra mostra o **sinal** (cai com a distância).

### Ajustes do painel do controle

- **Portas: ABRIR TODAS / FECHAR TODAS** (rodapé da aba PORTAS). Abre, ou fecha e tranca, todas as portas do controle de uma vez. Pula as offline, longe (mais de 160 blocos), de outra dimensão ou sem acesso, e o rodapé avisa quantas foram. Só atua nas portas que o jogador pode comandar (dono / hacker).
- **Numeração igual à do painel, fora dele:** o HUD da visão mostra o mesmo ID do painel (CAM-02, DRN-01) e o tooltip do controle lista por tipo ("Portas (3): DOOR-01, DOOR-02, DOOR-04"). A lista é a mesma do painel, então apagar pelo painel atualiza tudo.
- Apagar dispositivos no painel agora mantém o "dispositivo atual" da tecla R no mesmo dispositivo (antes ele podia passar para outro, deslocando a ordem).
- Lixeira centralizada no botão.
