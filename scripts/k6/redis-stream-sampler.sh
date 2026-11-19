#!/bin/sh
# Samples the inventory stream's backlog every 0.25 s (M2.4a-seatmap): how far Postgres trails Redis
# under REDIS_FIRST. k6.sh copies this into the redis container and runs it there (one docker exec for
# the whole run), as it does for the Postgres sampler. Prints CSV rows:
#
#   time,pending,undelivered,unapplied,oldest_unapplied_ms
#
# pending      entries delivered to a consumer and not yet acknowledged (the consumer acknowledges
#              after its Postgres commit, D-022)
# undelivered  entries no consumer has read yet (XINFO GROUPS "lag")
# unapplied    pending + undelivered: entries Postgres hasn't applied
# oldest_unapplied_ms  age of the oldest of those by Redis' clock (stream ids are Redis milliseconds);
#              0 when there are none
#
# The stream is shared by every event, so the warm-up event's entries count too. Stops when
# /tmp/k6-sampler-stop exists, or after an hour. Needs REDIS_PASSWORD (the container's own environment).
#
# Usage: redis-stream-sampler.sh <stream key> <consumer group>

stream=${1:?stream key}
group=${2:?consumer group}
export REDISCLI_AUTH="$REDIS_PASSWORD"
end=$(( $(date +%s) + 3600 ))

while [ ! -f /tmp/k6-sampler-stop ] && [ "$(date +%s)" -lt "$end" ]; do
  # XINFO GROUPS prints field and value on alternate lines; take this group's pending, lag, last id.
  fields=$(redis-cli XINFO GROUPS "$stream" 2>/dev/null | awk -v g="$group" '
    NR % 2 == 1 { field = $0; next }
    field == "name" { mine = ($0 == g) }
    mine && field == "pending" { pending = $0 }
    mine && field == "lag" { lag = $0 }
    mine && field == "last-delivered-id" { last = $0 }
    END { print (pending == "" ? 0 : pending), (lag == "" ? "-" : lag), (last == "" ? "0-0" : last) }')
  pending=${fields%% *}
  rest=${fields#* }
  lag=${rest%% *}
  last=${rest#* }

  oldest=""
  if [ "$pending" -gt 0 ]; then
    # XPENDING's summary: count, smallest id, largest id, then the consumers.
    oldest=$(redis-cli XPENDING "$stream" "$group" | sed -n 2p)
  elif [ "$lag" != "-" ] && [ "$lag" -gt 0 ]; then
    oldest=$(redis-cli XRANGE "$stream" "($last" + COUNT 1 | sed -n 1p)
  fi
  # Redis' clock (the one stream ids are made of), read after the stream so an entry added in between
  # can't look younger than zero. TIME prints seconds and microseconds on two lines.
  clock=$(redis-cli TIME | tr '\n' ' ')
  sec=${clock%% *}
  usec=${clock#* }
  usec=${usec%% *}
  now=$(( sec * 1000 + usec / 1000 ))
  time="$(date -u -d "@$sec" +%Y-%m-%dT%H:%M:%S).$(printf %03d $(( usec / 1000 )))Z"
  age=0
  [ -n "$oldest" ] && age=$(( now - ${oldest%%-*} ))

  if [ "$lag" = "-" ]; then
    echo "$time,$pending,,,$age"   # Redis couldn't count the undelivered entries
  else
    echo "$time,$pending,$lag,$(( pending + lag )),$age"
  fi
  sleep 0.25
done
