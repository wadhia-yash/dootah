#!/bin/zsh
# Runs the coverage scan over one app. $1 = app directory, $2 = app name.
set -e
D=/Users/yashwadhia/Documents/Dootah
APP=$1
NAME=$2
GP=$D/dootah-gradle-plugin/build/libs/dootah-gradle-plugin-0.1.0-SNAPSHOT.jar
CP=$D/dootah-compiler-plugin/build/libs/dootah-compiler-plugin-0.1.0-SNAPSHOT.jar
CT=$D/dootah-contract/build/libs/dootah-contract-0.1.0-SNAPSHOT.jar

cd $APP
START=$(date +%s)
./gradlew --init-script $D/coverage/dootah-coverage.init.gradle.kts \
  -Ddootah.gradle.plugin.jar=$GP \
  -Pdootah.plugin.jars=$CP:$CT \
  dootahCoverage "${@:3}" > /tmp/dootah-coverage-$NAME.log 2>&1
STATUS=$?
END=$(date +%s)
echo "coverage build for $NAME: exit=$STATUS in $((END-START))s"
python3 $D/coverage/report.py "$NAME" "$APP" > $D/coverage/$NAME.json
python3 -c "
import json;r=json.load(open('$D/coverage/$NAME.json'));t=r['totals']
print(f\"{r['app']}: modules={r['modules']} files={r['files']} composables={t.get('composables',0)} eligible={t.get('eligible',0)} remote={t.get('remote',0)} mixed={t.get('mixed',0)} notworth={t.get('not_worth',0)} fallback={t.get('fallback',0)} ineligible={t.get('ineligible',0)}\")
print(' still refused:', sorted(r['blocked_by'].items(), key=lambda x:-x[1])[:6])
print(' native inside lowered screens:', sorted(r['inside'].items(), key=lambda x:-x[1])[:6])
"
