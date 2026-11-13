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
