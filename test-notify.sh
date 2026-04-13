#!/usr/bin/env bash
# Quick test script for sending nstrfy notifications
#
# Usage:
#   ./test-notify.sh "Hello world"                          # public, plain JSON
#   ./test-notify.sh "Title" "Message body"                 # public, plain JSON
#   ./test-notify.sh "Title" "Message body" urgent          # public, urgent priority
#   NPUB=npub1... ./test-notify.sh "Secret" "For you only"  # encrypted to recipient
#   TOPIC=alerts ./test-notify.sh "Deploy done"
#   RELAY=wss://nos.lol ./test-notify.sh "test"

set -e

TOPIC="${TOPIC:-test}"
RELAY="${RELAY:-wss://nos.lol}"
PRIORITY="${3:-default}"

if [ -z "$1" ]; then
    echo "Usage: $0 <title> [message] [priority]"
    echo ""
    echo "  priority: min, low, default, high, urgent"
    echo ""
    echo "Environment:"
    echo "  NPUB=npub1...   recipient for encrypted/inbox delivery (default: none = public)"
    echo "  TOPIC=test      topic name (default: test)"
    echo "  RELAY=wss://...  relay URL (default: wss://nos.lol)"
    echo ""
    echo "Examples:"
    echo "  $0 'Hello'                                  # public notification"
    echo "  $0 'Deploy' 'v2.0 is live' high             # public, high priority"
    echo "  NPUB=npub1abc... $0 'Secret' 'Eyes only'    # encrypted to recipient"
    echo "  TOPIC=alerts $0 'Server down' '' urgent"
    exit 1
fi

TITLE="$1"
MESSAGE="${2:-$1}"
TIMESTAMP=$(date +%s)

# Build JSON payload
PAYLOAD=$(cat <<EOF
{"version":"1.0","title":"$TITLE","message":"$MESSAGE","priority":"$PRIORITY","timestamp":$TIMESTAMP,"topic":"$TOPIC"}
EOF
)

# Generate ephemeral sender key
SENDER_KEY=$(nak key generate)
SENDER_PUB=$(echo "$SENDER_KEY" | nak key public)

echo "Payload: $PAYLOAD"

if [ -n "$NPUB" ]; then
    # Encrypted (inbox) mode: NIP-44 encrypt + #p tag
    RECIPIENT_HEX=$(nak decode "$NPUB" -p 2>/dev/null || echo "$NPUB")
    echo "Encrypting to $NPUB ..."
    CONTENT=$(nak encrypt --sec "$SENDER_KEY" -p "$RECIPIENT_HEX" "$PAYLOAD")
    D_TAG="${TIMESTAMP}-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')"

    nak event \
        --kind 30078 \
        --content "$CONTENT" \
        -t "p=$RECIPIENT_HEX" \
        -t "d=$D_TAG" \
        --sec "$SENDER_KEY" \
        "$RELAY"
    echo ""
    echo "Sent encrypted notification to $NPUB on topic '$TOPIC' via $RELAY"
else
    # Public mode: plain JSON content, no #p tag
    D_TAG="${TIMESTAMP}-$(head -c4 /dev/urandom | od -An -tx1 | tr -d ' \n')"

    nak event \
        --kind 30078 \
        --content "$PAYLOAD" \
        -t "d=$D_TAG" \
        --sec "$SENDER_KEY" \
        "$RELAY"
    echo ""
    echo "Sent public notification on topic '$TOPIC' via $RELAY"
    echo "Sender pubkey: $SENDER_PUB"
    echo "(Add this pubkey to the topic's allowlist if whitelist is enabled)"
fi
