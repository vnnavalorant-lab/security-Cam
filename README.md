# VigiCam (Fabric 1.20.1)

Bloco "Câmera de Vigilância". Clique direito nela e você passa a ver pelo ponto de vista da câmera
(varrendo de um lado para o outro) com o shader fisheye + VHS + visão noturna verde e HUD "LIVE".
Shift (depois de soltar uma vez) sai da câmera.

## Como compilar
1. Gere um projeto base em https://fabricmc.net/develop/template (1.20.1) para ter o gradle wrapper
   (gradlew, gradle/wrapper), ou copie o wrapper de qualquer mod Fabric 1.20.1 seu.
2. Copie para ele: `src/`, `build.gradle`, `gradle.properties`, `settings.gradle` deste projeto.
3. `./gradlew build` -> o .jar fica em `build/libs/` (use o que NÃO termina em -sources).
4. Coloque o .jar na pasta mods junto com o Fabric API 0.92.x para 1.20.1.

Receita: 5 ferro, 2 vidraça e 1 redstone (III / GRG / I I).
