# syntax=docker/dockerfile:1

# --- Construction du jar, puis découpage en couches (dépendances d'abord : elles changent rarement) ---
FROM eclipse-temurin:21-jdk AS construction
WORKDIR /src

# Dépendances seules d'abord : cette couche reste en cache tant que pom.xml ne change pas
COPY --chmod=755 mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Puis le code (les tests tournent hors de l'image : ils ont besoin de Docker pour Testcontainers)
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/audit-api-*.jar extract --layers --launcher --destination /extrait

# --- Exécution : JRE seul, utilisateur non root ---
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=construction /extrait/dependencies/ ./
COPY --from=construction /extrait/spring-boot-loader/ ./
COPY --from=construction /extrait/snapshot-dependencies/ ./
COPY --from=construction /extrait/application/ ./
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
