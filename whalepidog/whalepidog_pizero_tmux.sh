#!/bin/bash

TMUX_SESSION="pamguard"
JAR_FILE="whalepidog.jar"
CONFIG_FILE="/home/whalepi/pamguard_pizero/whalepidog_settings.json"

# Check that the config file exists
if [ ! -f "$CONFIG_FILE" ]; then
    echo "Config file not found: $CONFIG_FILE"
    exit 1
fi

# Check that the daemon field is set to true in the config file
if command -v jq &>/dev/null; then
    DAEMON=$(jq -r '.daemon' "$CONFIG_FILE")
else
    DAEMON=$(grep -o '"daemon"[[:space:]]*:[[:space:]]*[a-z]*' "$CONFIG_FILE" | sed 's/.*:[[:space:]]*//')
fi

if [ "$DAEMON" != "true" ]; then
    echo "Daemon mode is not enabled in $CONFIG_FILE (daemon=$DAEMON). Exiting."
    exit 0
fi

echo "Starting WhalePiDog watchdog"

# Check if tmux session already exists
if tmux has-session -t "$TMUX_SESSION" 2>/dev/null; then
    echo "tmux session '$TMUX_SESSION' already exists, killing it first"
    tmux kill-session -t "$TMUX_SESSION"
fi

tmux new-session -d -s "$TMUX_SESSION" java -jar "$JAR_FILE" "$CONFIG_FILE"

echo "WhalePi watchdog activating - see tmux session at"
echo "tmux attach -t pamguard"
