FROM node:24.13.1-bookworm-slim@sha256:a81a03dd965b4052269a57fac857004022b522a4bf06e7a739e25e18bce45af2 AS node
WORKDIR /deps
COPY cloud/package*.json ./
RUN npm ci --omit=dev --ignore-scripts && npm cache clean --force
FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS validator
WORKDIR /compile
COPY validator/ ./
RUN javac -cp json.jar -d classes Validate.java PortableProgram.java
FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN apt-get update && apt-get install -y --no-install-recommends gosu && rm -rf /var/lib/apt/lists/* && useradd -u 10001 -m dootah
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/LICENSE /licenses/NODE-LICENSE
COPY notices/ /licenses/
COPY --from=node /deps/node_modules /app/cloud/node_modules
COPY --from=validator /compile/classes /app/validator
COPY --from=validator /compile/json.jar /app/validator/json.jar
COPY cloud/ /app/cloud/
COPY server/publish.mjs /app/server/publish.mjs
COPY --chown=0:0 --chmod=0755 entrypoint.sh /entrypoint.sh
# Only immutable image payloads: never mounted secrets or runtime storage.
# Preserve dependency executables, but establish readable files/traversable dirs
# independently of the build-context umask (including validator JAR/classes).
RUN find /app /licenses -type d -exec chmod 0755 {} + \
 && find /app /licenses -type f -exec chmod u=rwX,go=rX {} + \
 && chown -R 0:0 /app /licenses \
 && mkdir -p /run/input && chown 0:0 /run/input && chmod 0700 /run/input
ENV NODE_ENV=production DOOTAH_MODE=production DOOTAH_JAVA=/opt/java/openjdk/bin/java DOOTAH_VALIDATOR_CLASSPATH=/app/validator:/app/validator/json.jar
WORKDIR /app/cloud
ENTRYPOINT ["/entrypoint.sh"]
CMD ["node", "server.mjs"]
HEALTHCHECK --interval=20s --timeout=15s --start-period=30s CMD node -e 'fetch("http://127.0.0.1:3100/ready").then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))'
