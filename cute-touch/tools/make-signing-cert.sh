#!/bin/zsh
# Create a self-signed code-signing identity in your login keychain so Cute Touch keeps its macOS
# permissions across rebuilds. Local use only; it isn't trusted by anyone else's Mac.
set -e
NAME="${1:-Cute Touch Local Signing}"
if security find-identity -p codesigning | grep -q "\"$NAME\""; then echo "\"$NAME\" already exists"; exit 0; fi
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/cfg.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -days 3650 -config "$TMP/cfg.cnf" 2>/dev/null
# macOS's importer needs the legacy PKCS#12 algorithms.
openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -out "$TMP/id.p12" -passout pass:cutetouch \
    -name "$NAME" -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1
security import "$TMP/id.p12" -k ~/Library/Keychains/login.keychain-db -P cutetouch -T /usr/bin/codesign
echo "Created \"$NAME\". Rebuild with ./build.sh"
