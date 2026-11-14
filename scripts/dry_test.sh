#!/usr/bin/env bash

DRY_DIR=vendor/dry4java

JAR="$DRY_DIR/target/dry4java-0.1.0-SNAPSHOT.jar"
LONG_THRESHOLD=10
read -r groups locations long < <(
  java -jar "$JAR" ./src | tee /dev/stderr | awk -v thr="$LONG_THRESHOLD" '
    /^DUPLICATE/ { g++; next }
    match($0, /:[0-9]+-[0-9]+/) {
      split(substr($0, RSTART+1, RLENGTH-1), r, "-")
      if (r[2] - r[1] + 1 > thr) l++
      n++
    }
    END { print g+0, n+0, l+0 }'
)
echo "duplicate groups: $groups, long blocks: $long"
GROUP_THRESHOLD=5
(( groups > GROUP_THRESHOLD )) && { echo "Too many duplicate groups"; exit 1; }
(( long   > LONG_THRESHOLD   )) && { echo "Too many long duplicates";  exit 2; }
exit 0
