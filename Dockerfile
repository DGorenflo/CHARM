FROM maven:3.9.2-eclipse-temurin-17


WORKDIR /app

COPY app/target/dependency/ /app/lib/


COPY app/target/original-CHARM-1.0.jar /app/app.jar

RUN mkdir -p external && echo "charm.CHARM_Matcher" > external/main_class.txt

EXPOSE 8080

ENTRYPOINT ["java", "-Xmx40g", "-Djdk.xml.maxElementDepth=0", "-cp", "/app/app.jar:/app/lib/*", "de.uni_mannheim.informatik.dws.melt.receiver_http.Main"]
