#!/usr/bin/env bash
# Animke Android imzalama anahtarını bir kez oluşturur ve GitHub'a gizli değişken olarak yükler.
# Git Bash'te çalıştır:  bash anahtar-olustur.sh
# Şifre ekranda görünmez ve hiçbir yere yazdırılmaz.
set -euo pipefail
cd "$(dirname "$0")"

REPO="iEmre1/animke-android"
DIR="anahtar"
P12="$DIR/animke-android.p12"

if [ -e "$P12" ]; then
  echo "Anahtar zaten var: $P12 — yenisini oluşturma (eski sürümleri kuranlar güncelleyemez)."
  exit 1
fi
command -v openssl >/dev/null || { echo "openssl bulunamadı (Git Bash ile çalıştır)."; exit 1; }
command -v gh >/dev/null || { echo "gh (GitHub CLI) bulunamadı."; exit 1; }

read -rsp "Anahtar şifresi (en az 8 karakter): " ANIMKE_KEY_PASS; echo
read -rsp "Şifre (tekrar): " ANIMKE_KEY_PASS2; echo
[ "$ANIMKE_KEY_PASS" = "$ANIMKE_KEY_PASS2" ] || { echo "Şifreler aynı değil."; exit 1; }
[ "${#ANIMKE_KEY_PASS}" -ge 8 ] || { echo "Şifre en az 8 karakter olmalı."; exit 1; }
export ANIMKE_KEY_PASS

mkdir -p "$DIR"
# 2048 bit RSA, ~27 yıl geçerli sertifika.
openssl req -x509 -newkey rsa:2048 -nodes -days 10000 \
  -subj "/CN=Animke/O=Animke/C=TR" \
  -keyout "$DIR/key.pem" -out "$DIR/sertifika.pem" 2>/dev/null
openssl pkcs12 -export -name animke \
  -inkey "$DIR/key.pem" -in "$DIR/sertifika.pem" \
  -out "$P12" -passout env:ANIMKE_KEY_PASS
rm -f "$DIR/key.pem"

base64 -w0 "$P12" | gh secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
printf %s "$ANIMKE_KEY_PASS" | gh secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO"
printf %s "animke" | gh secret set ANDROID_KEY_ALIAS -R "$REPO"
unset ANIMKE_KEY_PASS ANIMKE_KEY_PASS2

echo
echo "Tamam: anahtar oluşturuldu ve GitHub'a yüklendi."
echo "Parmak izi (gizli değil, ileride bağlantı doğrulaması için):"
openssl x509 -in "$DIR/sertifika.pem" -noout -fingerprint -sha256
echo
echo "ÖNEMLİ: '$P12' dosyasını ve şifreyi güvenli bir yere yedekle (USB, Drive)."
echo "Kaybolursa kullanıcılar yeni sürümleri kuramaz."
