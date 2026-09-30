# VoiceStudio deployment for Bible Friend

This directory contains the recommended self-hosted VoiceStudio provider for the Kotlin/Ktor Bible Friend app.

## 1. Create the server key

Generate a long random key and keep it in the server environment:

    export OMNIVOICE_API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"

## 2. Start VoiceStudio

    docker compose up -d

The compose file pins VoiceStudio 0.5.6 and publishes port 3900 on loopback only.

Check startup:

    curl -H "Authorization: Bearer $OMNIVOICE_API_KEY" http://127.0.0.1:3900/health

List voices:

    curl -H "Authorization: Bearer $OMNIVOICE_API_KEY" http://127.0.0.1:3900/v1/audio/voices

## 3. Connect bible-verse-web

If the Ktor app runs on the same VPS host:

    export VOICESTUDIO_BASE_URL=http://127.0.0.1:3900
    export VOICESTUDIO_API_KEY="$OMNIVOICE_API_KEY"
    export VOICESTUDIO_MODEL=tts-1
    export VOICESTUDIO_VOICE=default

If the Ktor app and VoiceStudio are on different machines, expose VoiceStudio only through HTTPS or an encrypted private overlay, then set VOICESTUDIO_BASE_URL to that HTTPS endpoint.

The application intentionally rejects remote plain HTTP unless VOICESTUDIO_ALLOW_INSECURE_HTTP=true is explicitly set for a trusted private network.

## NVIDIA GPU

The same image can use NVIDIA acceleration when Docker has NVIDIA Container Toolkit configured. Add the GPU reservation appropriate for your host/Compose version, or run the image with --gpus all.

## AMD GPU

Use the VoiceStudio ROCm image matching the same release line instead:

    ghcr.io/debpalash/voicestudio:0.5.6-rocm

Follow VoiceStudio's ROCm device-passthrough instructions for /dev/kfd and /dev/dri.

## Security boundary

The browser calls only bible-verse-web. The Ktor server calls VoiceStudio and adds the Bearer key server-side. Do not expose VOICESTUDIO_API_KEY or OMNIVOICE_API_KEY to frontend JavaScript.
