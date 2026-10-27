# Gravador Club (Android)

Grava o microfone o dia todo, com a tela apagada, em partes de 60 s, e manda para o servidor do Club Nexo (`/publico/gravador/parte`). Sem internet, as partes ficam na fila do aparelho e sobem sozinhas depois, em ordem.

## Instalar

1. Baixe o `gravador-club.apk` no celular (ou passe por cabo/WhatsApp).
2. Ao abrir, o Android pede para permitir "Instalar apps desconhecidos" para o app que abriu o arquivo (Chrome, Arquivos...). Permita e volte.
3. Toque em Instalar. Se o Play Protect avisar, toque em "Instalar mesmo assim".

## Primeiro uso

1. Abra o **Gravador Club**, cole o **Código do gravador** (`cnxg_...`). O servidor já vem preenchido.
2. Toque em **Começar a gravar** e conceda: **Microfone** ("Durante o uso do app") e **Notificações** (Android 13+).
3. Toque em **Liberar bateria** e escolha "Permitir" (sem restrição de bateria).
4. Pode apagar a tela. A notificação "Gravador Club gravando" fica fixa; nela há o botão Parar.

## Ajustes extras (dontkillmyapp.com)

**Samsung (One UI)**
- Configurações > Aplicativos > Gravador Club > Bateria > "Sem restrições".
- Configurações > Cuidados com o dispositivo > Bateria > Limites de uso em segundo plano: tire o Gravador Club de "Apps em suspensão" e "Apps em suspensão profunda"; desligue "Colocar apps não usados em suspensão".
- Na tela de apps recentes, toque no ícone do app e escolha "Manter aberto" (cadeado).

**Xiaomi (MIUI / HyperOS)**
- Configurações > Aplicativos > Permissões > Início automático: ligue para o Gravador Club.
- Configurações > Aplicativos > Gerenciar apps > Gravador Club > Economia de bateria: "Sem restrições".
- Em apps recentes, segure o card do app e toque no cadeado.
- Opcional: Segurança > Velocidade/Limpeza: não limpar o Gravador Club.

Outras marcas: veja a página do fabricante em dontkillmyapp.com.

## Como funciona

- Cada parte: AAC/MPEG-4, 16 kHz, mono, ~32 kbps, arquivo `fila/<inicioEpochSeg>_<durSeg>.m4a`.
- Envio: 2xx apaga; erro de rede ou 5xx espera e tenta de novo; 400/413/422 renomeia para `.recusada`; 401 para e mostra "código recusado".
- Sinal de vida a cada 60 s. Se o servidor responder `pausado: true`, o app para de gravar ("Pausado pelo Club") e volta quando vier `false`.
- Se o microfone for tomado (ligação), tenta reabrir em 10 s.

## Detalhe do servidor

`aparelho` vai como `raspberry` (único valor de aparelho aceito em produção até hoje). Para trocar para `android`, edite a constante `APARELHO` em `Multipart.kt`.

## Build

GitHub Actions (`.github/workflows/apk.yml`): `gradle test` e `gradle assembleDebug`; o APK sai como artefato `gravador-club`.
