set -eux

SBT="./project/scripts/sbt" # if run on CI
# SBT="sbt" # if run locally

SOURCE="tests/pos/HelloWorld.scala"
MAIN="HelloWorld"
TASTY="HelloWorld.tasty"
EXPECTED_OUTPUT="hello world"

OUT=$(mktemp -d)
OUT1=$(mktemp -d)
tmp=$(mktemp)

clear_out()
{
  local out="$1"
  rm -rf "$out/*"
}
